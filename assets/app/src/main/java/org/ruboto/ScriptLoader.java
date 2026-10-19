package org.ruboto;

import android.content.Context;
import android.os.Looper;
import android.widget.Toast;

import org.jruby.Ruby;
import org.jruby.RubyClass;
import org.jruby.javasupport.Java;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class ScriptLoader {
    // Scripts are evaluated on their own thread to get a stack as large as the
    // UI thread's: the JRuby interpreter recurses deeply.  ART only adds about
    // 1 MB to the size requested here, so 128 KB made for a 1.1 MB stack.
    private static final long SCRIPT_STACK_SIZE = 8 * 1024 * 1024;

    private static final Map<String, Boolean> methodCache = new ConcurrentHashMap<String, Boolean>();

   /**
    Return true if the Ruby class defines the given method.

    The generated component classes ask this before forwarding any Android
    callback.  Answering it means evaluating Ruby, and callbacks such as
    onUserInteraction fire once per input event, so the uncached version put a
    full parse of the predicate on the UI thread for every touch and every
    rotary tick.  On a watch that is enough to miss the input dispatcher's 5
    second deadline and be killed.

    The answer only changes when a script is evaluated, so it is cached until
    then; see clearMethodCache().
    */
    public static boolean hasRubyMethod(String rubyClassName, String methodName, boolean includeInherited) {
        final String key = rubyClassName + (includeInherited ? "#all#" : "#own#") + methodName;
        Boolean cached = methodCache.get(key);
        if (cached != null) {
            return cached;
        }
        Boolean defined = (Boolean) JRubyAdapter.runScriptlet(rubyClassName
                + ".instance_methods(" + includeInherited + ").any?{|m| m.to_sym == :" + methodName + "}");
        methodCache.put(key, defined);
        return defined;
    }

   /**
    Drop the cached method lookups.  Called whenever a script is evaluated,
    since that is the only thing that can change the answers.
    */
    public static void clearMethodCache() {
        methodCache.clear();
    }

   /**
    Return true if we are called from JRuby.
    */
    public static boolean isCalledFromJRuby() {
        StackTraceElement[] stackTraceElements = Thread.currentThread().getStackTrace();
        int maxLookBack = Math.min(8, stackTraceElements.length);
        for(int i = 0; i < maxLookBack ; i++){
            if (stackTraceElements[i].getClassName().startsWith("org.jruby.javasupport.JavaMethod")) {
                return true;
            }
        }
        return false;
    }

    ///////////////////////////////////////////////////////////////////////////
    //
    // Script loading
    //

    // Line-anchored and with the name quoted: `class XHelper`, comments and
    // strings mentioning the class do not count.
    private static boolean definesClass(String script, String rubyClassName) {
        return Pattern.compile("^[ \\t]*class[ \\t]+(?:::)?" + Pattern.quote(rubyClassName) + "\\b", Pattern.MULTILINE)
                .matcher(script).find();
    }

    // Make the Ruby constant refer to the Java proxy class, so that the script's
    // `class X` reopens the proxy instead of defining a new Ruby class.
    private static void bindJavaProxy(String rubyClassName, Class<?> javaClass) {
        ThreadContext context = JRubyAdapter.getRuntime().getCurrentContext();
        context.runtime.getObject().setConstant(context, rubyClassName, Java.getProxyClass(context, javaClass));
    }

    private static IRubyObject topLevelConstant(String name) {
        ThreadContext context = JRubyAdapter.getRuntime().getCurrentContext();
        return context.runtime.getObject().getConstantAt(context, name);
    }

   /**
    Evaluate the script on a thread with a large stack, and rethrow on this
    thread what the evaluation threw: join() alone would drop it, and the
    script thread's uncaught exception would kill the process.
    */
    private static void evaluate(final Script script, final String source, String what) {
        final Throwable[] failure = new Throwable[1];
        Thread t = new Thread(null, new Runnable() {
            public void run() {
                long loadStart = System.currentTimeMillis();
                try {
                    JRubyAdapter.setScriptFilename(script.getAbsolutePath());
                    JRubyAdapter.runScriptlet(source);
                } catch (Throwable e) {
                    failure[0] = e;
                }
                Log.d("Script load took " + (System.currentTimeMillis() - loadStart) + "ms");
            }
        }, "ScriptLoader " + what, SCRIPT_STACK_SIZE);
        t.start();
        try {
            t.join();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted loading script.", ie);
        }
        if (failure[0] instanceof RuntimeException) throw (RuntimeException) failure[0];
        if (failure[0] instanceof Error) throw (Error) failure[0];
        if (failure[0] != null) throw new RuntimeException(failure[0]);
    }

   /**
    Define the Ruby class backing the given Activity ahead of time.

    Called from SplashActivity while the splash is up, so the script is evaluated
    there instead of in the Activity's onCreate.  loadScript() joins the script
    thread on whatever thread called it; when that is the UI thread, evaluating a
    script of any size starves the window focus handoff of its 5 second deadline
    and the app is killed with an ANR before it can draw.  Once the class is
    defined here, loadScript() finds it and returns without evaluating anything.

    Failures are logged and swallowed: loadScript() still runs afterwards and
    remains the authority on reporting a broken script.
    */
    public static void preloadScript(String javaClassName) {
        try {
            final Class<?> javaClass = Class.forName(javaClassName);
            final String rubyClassName = javaClass.getSimpleName();
            if (topLevelConstant(rubyClassName) != null) {
                Log.d("Ruby class already defined: " + rubyClassName);
                return;
            }
            final Script rubyScript = new Script(Script.toSnakeCase(rubyClassName) + ".rb");
            if (!rubyScript.exists()) {
                Log.d("No script to preload for: " + rubyClassName);
                return;
            }
            final String script = rubyScript.getContents();
            if (!definesClass(script, rubyClassName)) {
                Log.d("Script does not define " + rubyClassName + ", leaving it to loadScript");
                return;
            }
            Log.d("Preloading script: " + rubyScript.getName());
            bindJavaProxy(rubyClassName, javaClass);
            try {
                evaluate(rubyScript, script, "preload for " + rubyClassName);
                clearMethodCache();
            } catch (Throwable e) {
                // Leave the reporting to loadScript, which knows the component.
                // Unbind the proxy so that loadScript evaluates the script again.
                Log.e("Preloading " + rubyClassName + " failed: " + e);
                ThreadContext context = JRubyAdapter.getRuntime().getCurrentContext();
                context.runtime.getObject().deleteConstant(context, rubyClassName);
            }
        } catch (Throwable t) {
            Log.e("Failed to preload script: " + t);
        }
    }

    public static void loadScript(final RubotoComponent component) {
        try {
            final ScriptInfo info = component.getScriptInfo();
            final String rubyClassName = info.getRubyClassName();
            if (info.getScriptName() != null) {
                IRubyObject rubyClass = topLevelConstant(rubyClassName);
                final Script rubyScript = new Script(info.getScriptName());
                Object rubyInstance;
                if (rubyScript.exists()) {
                    rubyInstance = component;
                    boolean hasBackingJavaClass = rubyClassName.equals(component.getClass().getSimpleName());
                    // The constant is the Java proxy once preloadScript() or an
                    // earlier instance of this component evaluated the script.
                    if (!hasBackingJavaClass || rubyClass != Java.getProxyClass(JRubyAdapter.getRuntime(), component.getClass())) {
                        if (JRubyAdapter.isDebugBuild()) Log.d("Loading script: " + info.getScriptName());
                        final String script = rubyScript.getContents();
                        if (!definesClass(script, rubyClassName)) {
                            throw new RuntimeException("Expected file " + info.getScriptName()
                                    + " to define class " + rubyClassName);
                        }
                        if (hasBackingJavaClass) {
                            bindJavaProxy(rubyClassName, component.getClass());
                        } else {
                            // Script defines methods on the meta class
                            rubyClass = (IRubyObject) JRubyAdapter.runRubyMethod(IRubyObject.class, component, "singleton_class");
                            ThreadContext context = JRubyAdapter.getRuntime().getCurrentContext();
                            context.runtime.getObject().setConstant(context, rubyClassName, rubyClass);
                        }
                        evaluate(rubyScript, script, "for " + rubyClassName);
                        clearMethodCache();
                    }
                } else if (rubyClass != null) {
                    // We have a predefined Ruby class without corresponding Ruby source file.
                    if (JRubyAdapter.isDebugBuild()) Log.d("Create separate Ruby instance for class: " + rubyClassName);
                    rubyInstance = JRubyAdapter.runRubyMethod(rubyClass, "new");
                    JRubyAdapter.runRubyMethod(rubyInstance, "instance_variable_set", "@ruboto_java_instance", component);
                } else {
                    // Neither script file nor predefined class
                    Log.e("Missing script and class.  Either script or predefined class must be present.");
                    throw new RuntimeException("Either script or predefined class must be present.");
                }
                info.setRubyInstance(rubyInstance);
            }
            persistObjectProxy(component);
        } catch(IOException e){
            Log.e("Script failed", e);
            // Toast needs a Looper; components other than activities may be
            // created on any thread.
            if (component instanceof Context && Looper.myLooper() == Looper.getMainLooper()) {
                Toast.makeText((Context) component, "Script failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> proxies() {
        return (Map<Object, Object>) topLevelConstant("RUBOTO_JAVA_PROXIES");
    }

    // Same as `Java::<component class>.__persistent__ = true`: keep the Ruby
    // wrapper (and the singleton methods and instance variables on it) for as
    // long as the Java object lives.
    private static void persistObjectProxy(RubotoComponent component) {
        Ruby runtime = JRubyAdapter.getRuntime();
        ((RubyClass) Java.getProxyClass(runtime, component.getClass())).getRealClass().setCacheProxy(true);
        Object instance = component.getScriptInfo().getRubyInstance();
        proxies().put(instance, instance);
    }

    public static void unloadScript(RubotoComponent component) {
        proxies().remove(component.getScriptInfo().getRubyInstance());
    }

}
