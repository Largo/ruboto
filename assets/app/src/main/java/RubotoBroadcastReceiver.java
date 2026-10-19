package THE_PACKAGE;

import java.io.IOException;
import org.ruboto.ScriptLoader;

public class THE_RUBOTO_CLASS THE_ACTION THE_ANDROID_CLASS {
    private final ScriptInfo scriptInfo = new ScriptInfo();

    public ScriptInfo getScriptInfo() {
        return scriptInfo;
    }

    public THE_RUBOTO_CLASS() {
        super();
        scriptInfo.setRubyClassName(getClass().getSimpleName());
        if (JRubyAdapter.isInitialized()) {
            ScriptLoader.loadScript(this);
        }
    }

    public void onReceive(android.content.Context context, android.content.Intent intent) {
        try {
            // The Ruby handler's `super` lands here; BroadcastReceiver.onReceive is abstract.
            if (scriptInfo.isCallbackActive(0) && ScriptLoader.isSuperCall("onReceive")) {
                return;
            }
            if (!scriptInfo.isLoaded()) {
                if (JRubyAdapter.setUpJRuby(context)) {
                    ScriptLoader.loadScript(this);
                } else {
                    // FIXME(uwe): What to do if the Ruboto Core platform is missing?
                }
            }

            String rubyMethod = ScriptLoader.rubyCallback(this, 0, "onReceive", "on_receive");
            if (rubyMethod == null) {
                return;
            }
            scriptInfo.enterCallback(0);
            try {
                JRubyAdapter.runRubyMethod(scriptInfo.getRubyInstance(), rubyMethod, new Object[]{context, intent});
            } finally {
                scriptInfo.exitCallback(0);
            }
        } catch(Exception e) {
            Log.e("onReceive failed", e);
        }
    }

}
