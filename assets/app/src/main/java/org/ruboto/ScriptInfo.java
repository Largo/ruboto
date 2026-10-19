package org.ruboto;

import org.jruby.RubyClass;

public class ScriptInfo {
    public static final String CLASS_NAME_KEY = "RUBOTO_CLASS_NAME";
    public static final String SCRIPT_NAME_KEY = "RUBOTO_SCRIPT_NAME";
    private String rubyClassName;
    private String scriptName;
    private volatile Object rubyInstance;

    // Callback state for the generated component classes, indexed by the
    // callback number the generator assigns; see ScriptLoader.rubyCallback().
    // Counts rather than flags, so that a handler re-entered on the same
    // thread stays marked until the outermost call returns.
    private volatile int[] activeCallbacks = new int[0];
    private volatile HandlerCache handlerCache;

    // Which Ruby method handles each callback, valid while the Ruby class and
    // its method tables are unchanged: defining, removing or including methods
    // anywhere in its ancestry bumps RubyModule.getGeneration().  An entry is
    // null until resolved, NO_HANDLER, or the name of the Ruby method; a stale
    // read on another thread only resolves the entry again.
    private static final Object NO_HANDLER = new Object();

    private static final class HandlerCache {
        final RubyClass rubyClass;
        final int generation;
        volatile Object[] handlers = new Object[0];

        HandlerCache(RubyClass rubyClass) {
            this.rubyClass = rubyClass;
            this.generation = rubyClass.getGeneration();
        }
    }

   /**
    True while the Ruby handler of the callback runs.  The super call from the
    handler always comes back on the thread that entered it, so a stale answer
    on another thread only costs that thread a ScriptLoader.isSuperCall().
    */
    public boolean isCallbackActive(int index) {
        int[] active = activeCallbacks;
        return index < active.length && active[index] > 0;
    }

    public synchronized void enterCallback(int index) {
        if (index >= activeCallbacks.length) {
            int[] grown = new int[Math.max(index + 1, 2 * activeCallbacks.length)];
            System.arraycopy(activeCallbacks, 0, grown, 0, activeCallbacks.length);
            activeCallbacks = grown;
        }
        activeCallbacks[index]++;
    }

    public synchronized void exitCallback(int index) {
        activeCallbacks[index]--;
    }

    String handlerFor(RubyClass klass, int index, String camelName, String snakeName) {
        HandlerCache cache = handlerCache;
        if (cache == null || cache.rubyClass != klass || cache.generation != klass.getGeneration()) {
            handlerCache = cache = new HandlerCache(klass);
        }
        Object[] handlers = cache.handlers;
        Object handler = index < handlers.length ? handlers[index] : null;
        if (handler == null) {
            String name = ScriptLoader.resolveHandler(klass, camelName, snakeName);
            handler = name == null ? NO_HANDLER : name;
            synchronized (cache) {
                handlers = cache.handlers;
                if (index >= handlers.length) {
                    Object[] grown = new Object[Math.max(index + 1, 2 * handlers.length)];
                    System.arraycopy(handlers, 0, grown, 0, handlers.length);
                    handlers = grown;
                }
                handlers[index] = handler;
                cache.handlers = handlers;
            }
        }
        return handler == NO_HANDLER ? null : (String) handler;
    }

    public boolean isReadyToLoad() {
        return rubyClassName != null || scriptName != null;
    }

    public boolean isLoaded() {
        return rubyInstance != null;
    }

    public void setFromIntent(android.content.Intent intent) {

        // FIXME(uwe):  Deprecated as of Ruboto 0.13.0.  Remove in june 2014 (twelve months).
        android.os.Bundle configBundle = intent.getBundleExtra("Ruboto Config");
        if (configBundle != null) {
            if (configBundle.containsKey("ClassName")) {
                setRubyClassName(configBundle.getString("ClassName"));
            }
            if (configBundle.containsKey("Script")) {
                setScriptName(configBundle.getString("Script"));
            }
        }
        // EMXIF

        if (intent.hasExtra(CLASS_NAME_KEY)) {
            setRubyClassName(intent.getStringExtra(CLASS_NAME_KEY));
        }
        if (intent.hasExtra(SCRIPT_NAME_KEY)) {
            setScriptName(intent.getStringExtra(SCRIPT_NAME_KEY));
        }
    }

    public String getRubyClassName() {
        if (rubyClassName == null && scriptName != null) {
            return Script.toCamelCase(scriptName);
         }
        return rubyClassName;
    }

    public void setRubyClassName(String name) {
        rubyClassName = name;
    }

    public Object getRubyInstance() {
        return rubyInstance;
    }

    public void setRubyInstance(Object instance) {
        rubyInstance = instance;
    }

    public String getScriptName() {
        if (scriptName == null && rubyClassName != null) {
            return Script.toSnakeCase(rubyClassName) + ".rb";
        }
        return scriptName;
    }

    public void setScriptName(String name) {
        scriptName = name;
    }

}
