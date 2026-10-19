package org.ruboto;

import android.content.Context;
import android.content.pm.ApplicationInfo;

import org.jruby.Ruby;
import org.jruby.RubyArray;
import org.jruby.RubyHash;
import org.jruby.RubyModule;
import org.jruby.RubyString;
import org.jruby.embed.LocalContextScope;
import org.jruby.embed.LocalVariableBehavior;
import org.jruby.exceptions.RaiseException;
import org.jruby.javasupport.JavaUtil;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;

import java.io.File;
import java.io.PrintStream;

public class JRubyAdapter {
    // Set up on the splash thread and read on the UI thread, so volatile;
    // initialized is written last.
    private static volatile org.jruby.embed.ScriptingContainer ruby;
    private static volatile Ruby runtime;
    private static volatile boolean isDebugBuild = false;
    private static volatile PrintStream output = null;
    private static volatile boolean initialized = false;
    private static LocalContextScope localContextScope = LocalContextScope.SINGLETON; // FIXME(uwe):  Why not CONCURRENT ?  Help needed!
    private static LocalVariableBehavior localVariableBehavior = LocalVariableBehavior.TRANSIENT;

    public static Object get(String name) {
        return ruby.get(name);
    }

    public static String getScriptFilename() {
        return ruby.getScriptFilename();
    }

   /**
    The runtime behind the container, for lookups that need not evaluate Ruby.
    */
    public static Ruby getRuntime() {
        return runtime;
    }

    public static Object runRubyMethod(Object receiver, String methodName, Object... args) {
        return ruby.runRubyMethod(Object.class, receiver, methodName, args);
    }

    @SuppressWarnings("unchecked")
    public static <T> T runRubyMethod(Class<T> returnType, Object receiver, String methodName, Object... args) {
        return ruby.runRubyMethod(returnType, receiver, methodName, args);
    }

    public static boolean isDebugBuild() {
        return isDebugBuild;
    }

    public static boolean isInitialized() {
        return initialized;
    }

    public static void put(String name, Object object) {
        ruby.put(name, object);
    }

    public static Object runScriptlet(String code) {
        return ruby.runScriptlet(code);
    }

    public static boolean setUpJRuby(Context appContext) {
        return setUpJRuby(appContext, output == null ? System.out : output);
    }

    public static synchronized boolean setUpJRuby(Context appContext, PrintStream out) {
        if (!initialized) {
            try {
                ApplicationInfo appInfo = appContext.getPackageManager()
                        .getApplicationInfo(appContext.getPackageName(), 0);
                isDebugBuild = (appInfo.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
                final String apkName = appInfo.sourceDir;
                Log.d("Setting up JRuby " + org.jruby.runtime.Constants.VERSION
                        + " (" + (isDebugBuild ? "DEBUG" : "RELEASE") + ")"
                        + "  Max memory: " + (Runtime.getRuntime().maxMemory() / (1024 * 1024)) + "MB");
                setSystemProperties(appContext);

                //////////////////////////////////
                //
                // Set jruby.home
                // This needs to be set before the ScriptingContainer is initialized
                //
                System.setProperty("jruby.home", "jar:file:" + apkName + "!/META-INF/jruby.home");

                //////////////////////////////////
                //
                // Determine Output
                //

                if (out != null) {
                    output = out;
                }

                //////////////////////////////////
                //
                // Disable rubygems
                //
                // RubyGems cannot scan gem specifications inside the APK's
                // jar: URI (path expansion fails), so it must stay disabled.
                // Bundled gems are loaded via the load path instead.
                org.jruby.RubyInstanceConfig config = new org.jruby.RubyInstanceConfig();
                config.setDisableGems(true);
                final File filesDir = appContext.getFilesDir();
                if (filesDir != null) {
                    config.setCurrentDirectory(filesDir.getPath());
                }

                ClassLoader classLoader = JRubyAdapter.class.getClassLoader();
                config.setLoader(classLoader);

                if (output != null) {
                    config.setOutput(output);
                    config.setError(output);
                }

                // This will become the global runtime and be used by our ScriptingContainer
                runtime = Ruby.newInstance(config);

                //////////////////////////////////
                //
                // Create the ScriptingContainer
                //
                ruby = new org.jruby.embed.ScriptingContainer(localContextScope, localVariableBehavior);

                // FIXME(uwe): Write tutorial on profiling.
                // container.getProvider().getRubyInstanceConfig().setProfilingMode(mode);

                Thread.currentThread().setContextClassLoader(classLoader);

                String scriptsDir = scriptsDirName(appContext);
                setUpLoadPath(apkName, scriptsDir);
                if (filesDir != null) {
                    ruby.setCurrentDirectory(filesDir.getPath());
                } else {
                    Log.e("Unable to find app files dir!");
                    if (new File(scriptsDir).exists()) {
                        ruby.setCurrentDirectory(scriptsDir);
                    }
                }

                // Set through the Java API: these used to be scriptlets, each
                // parsed and interpreted on the start-up path.
                ThreadContext context = runtime.getCurrentContext();
                RubyModule object = runtime.getObject();
                IRubyObject app = JavaUtil.convertJavaToUsableRubyObject(runtime, appContext.getApplicationContext());
                runtime.getGlobalVariables().set("$package_name", runtime.newString(appContext.getPackageName()));
                object.setConstant(context, "APK_PATH", runtime.newString("jar:file:" + apkName + "!"));
                object.setConstant(context, "RUBOTO_JAVA_PROXIES", RubyHash.newHash(runtime));
                object.setConstant(context, "APPLICATION_CONTEXT", app);
                runtime.getGlobalVariables().set("$application_context", app);

                // Without an environment.rb the require only raises a LoadError,
                // after searching the whole load path and indexing the APK.
                if (new Script("environment.rb").exists()) {
                    try {
                        runtime.getLoadService().require("environment");
                    } catch (RaiseException e) {
                        if (!runtime.getLoadError().isInstance(e.getException())) {
                            throw e;
                        }
                        Log.e("Loading environment.rb failed: " + e.getMessage());
                    }
                }

                initialized = true;
            } catch (Throwable e) {
                // Anything thrown here would otherwise end the start-up thread and
                // take the process down under the splash.
                handleInitException(e);
            }
        }
        return initialized;
    }

    private static void setSystemProperties(Context appContext) {
        // Android reports java.specification.version as "1.6" but JRuby 10
        // expects a simple integer (e.g. "11"). Override before JRuby loads.
        System.setProperty("java.specification.version", "11");
        System.setProperty("jruby.backtrace.style", "normal"); // normal raw full mri
        System.setProperty("jruby.bytecode.version", "11");
        // System.setProperty("jruby.compile.backend", "DALVIK");
        System.setProperty("jruby.compile.mode", "OFF"); // OFF OFFIR JITIR? FORCE FORCEIR
        // ART has no java.lang.invoke.SwitchPoint, so keep JRuby off the
        // invokedynamic-based call site and constant caching paths.
        System.setProperty("jruby.compile.invokedynamic", "false");
        // Android has no virtual threads (Thread.ofVirtual), so Fibers and
        // external Enumerators must run on platform threads.
        System.setProperty("jruby.fiber.vthread", "false");
        // ART cannot load bytecode generated at runtime, so bind the core
        // classes' methods with MethodHandles instead of generated invokers.
        System.setProperty("jruby.invokedynamic.handles", "true");
        System.setProperty("jruby.interfaces.useProxy", "true");
        System.setProperty("jruby.ir.passes", "LocalOptimizationPass,DeadCodeElimination");
        System.setProperty("jruby.management.enabled", "false");
        System.setProperty("jruby.native.enabled", "false");
        System.setProperty("jruby.objectspace.enabled", "false");
        System.setProperty("jruby.rewrite.java.trace", "true");

        // Uncomment these to debug/profile Ruby source loading
        // Analyse the output: grep "LoadService:   <-" | cut -f5 -d- | cut -c2- | cut -f1 -dm | awk '{total = total + $1}END{print total}'
        // System.setProperty("jruby.debug.loadService", "true");
        // System.setProperty("jruby.debug.loadService.timing", "true");

        // Used to enable JRuby to generate proxy classes
        System.setProperty("jruby.ji.upper.case.package.name.allowed", "true");
        System.setProperty("jruby.class.cache.path", appContext.getDir("dex", 0).getAbsolutePath());
        System.setProperty("java.io.tmpdir", appContext.getCacheDir().getAbsolutePath());
        System.setProperty("sun.arch.data.model", "64");
    }

    public static void setScriptFilename(String name) {
        ruby.setScriptFilename(name);
    }

   /**
    Put the directory or URI first on the Ruby load path (and on the path
    Ruboto looks up activity scripts in).  The `!/` and `uri:classloader:/`
    forms are stored without the trailing slash: JRuby compares $: entries with
    the paths of already-loaded features by prefix length, and with the slash
    no require ever matches, so every repeated require searches again.
    */
    public static Boolean addLoadPath(String dir) {
        boolean isUri = dir.startsWith("jar:") || dir.startsWith("uri:") || dir.startsWith("file:");
        if (!isUri && !new File(dir).exists()) {
            Log.i("Extra scripts dir not present: " + dir);
            return false;
        }
        if (isUri && (dir.endsWith(":/") || dir.endsWith("!/"))) {
            dir = dir.substring(0, dir.length() - 1);
        }
        Log.i("Added directory to load path: " + dir);
        Script.addDir(dir);
        unshiftLoadPath(dir);
        return true;
    }

    private static void unshiftLoadPath(String dir) {
        ThreadContext context = runtime.getCurrentContext();
        RubyArray loadPath = (RubyArray) runtime.getLoadService().getLoadPath();
        RubyString entry = runtime.newString(dir);
        loadPath.delete(context, entry, org.jruby.runtime.Block.NULL_BLOCK);
        loadPath.unshift(context, entry);
    }

    private static void setUpLoadPath(String apkName, String scriptsDir) {
        ThreadContext context = runtime.getCurrentContext();
        RubyArray loadPath = (RubyArray) runtime.getLoadService().getLoadPath();
        // JRuby's default $: has jruby.home's site_ruby, which is not packaged.
        // A jar: entry that does not exist is taken for a relative file path,
        // so every require would stat and canonicalise it under the cwd.
        for (int i = loadPath.getLength() - 1; i >= 0; i--) {
            if (loadPath.eltInternal(i).toString().endsWith("/site_ruby")) {
                loadPath.delete_at(context, i);
            }
        }
        // The APK root holds the app's scripts, ruboto/* and the bundled gems.
        // No separate uri:classloader: entry: JRuby falls back to the class
        // loader for anything not found on $:.
        unshiftLoadPath("jar:file:" + apkName + "!");
        // The scripts dir only has content when scripts were pushed to a
        // development build; empty, it would only add a stat to every require.
        Script.addDir(scriptsDir);
        String[] scripts = new File(scriptsDir).list();
        if (scripts != null && scripts.length > 0) {
            Log.i("Added directory to load path: " + scriptsDir);
            unshiftLoadPath(scriptsDir);
        }
    }

    public static void setLocalContextScope(LocalContextScope val) {
        localContextScope = val;
    }

    public static void setLocalVariableBehavior(LocalVariableBehavior val) {
        localVariableBehavior = val;
    }

    // Private methods

    private static void handleInitException(Throwable e) {
        Log.e("Exception starting JRuby", e);
        ruby = null;
        runtime = null;
    }

    private static String scriptsDirName(Context context) {
        File storageDir;
        if (isDebugBuild()) {
            storageDir = context.getExternalFilesDir(null);
            if (storageDir == null) {
                Log.e("Development mode active, but sdcard is not available.  Make sure you have added\n<uses-permission android:name='android.permission.WRITE_EXTERNAL_STORAGE' />\nto your AndroidManifest.xml file.");
                storageDir = context.getFilesDir();
            }
        } else {
            storageDir = context.getFilesDir();
        }
        File scriptsDir = new File(storageDir, "scripts");
        if ((!scriptsDir.exists() && !scriptsDir.mkdirs())) {
            Log.e("Unable to create the scripts dir.");
            scriptsDir = new File(context.getFilesDir(), "scripts");
        }
        return scriptsDir.getAbsolutePath();
    }

    public static void setOutputStream(PrintStream out) {
        if (ruby == null) {
            output = out;
        } else {
            ruby.setOutput(out);
            ruby.setError(out);
        }
    }
}
