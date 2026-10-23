package org.ruboto;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

/**
 * This Activity acts as an entry point to the app.  JRuby has to be running
 * before its Ruby script can handle the Activity's callbacks.
 *
 * On a cold start the Activity stays on screen and shows a spinner while
 * JRuby starts on a background thread.  Once JRuby is up (and the Activity's
 * script is loaded), the Ruby on_create runs, followed by the Ruby handlers
 * of the callbacks Android has made in the meantime (on_start,
 * on_restore_instance_state, on_post_create, on_resume, on_post_resume,
 * on_window_focus_changed).  Their `super` calls do not reach Android a
 * second time.
 *
 * If the app has a res/layout/splash.xml, SplashActivity shows that layout
 * instead while JRuby starts, and this Activity is started again afterwards
 * (override useSplashActivity() to choose).  Use that when the Ruby on_create
 * must run before the window is set up, e.g. for request_window_feature.
 */
public class EntryPointActivity extends org.ruboto.RubotoActivity {
    // The start-up shared by the EntryPointActivities waiting for JRuby.
    // UI thread only.
    private static boolean startingJRuby = false;
    private static final ArrayList<EntryPointActivity> waitingForJRuby = new ArrayList<EntryPointActivity>();

    private boolean waitingForRuby = false;
    private Bundle createState;
    private View placeholder;
    // The callback whose Ruby handler is being replayed; its super call
    // returns at once instead of reaching Android again.
    private String replaying = null;
    private boolean started = false;
    private boolean resumed = false;
    private boolean destroyed = false;

    public void onCreate(Bundle bundle) {
        if ("onCreate".equals(replaying)) return;
        Log.d("EntryPointActivity onCreate:");

        if (JRubyAdapter.isInitialized() && !startingJRuby) {
            getScriptInfo().setRubyClassName(getClass().getSimpleName());
            super.onCreate(bundle);
            SplashActivity.userActivityCreated();
            return;
        }

        if (useSplashActivity()) {
            showSplash();
            finish();
            super.onCreate(bundle);
            return;
        }

        // No Ruby class name yet, so RubotoActivity only prepares the
        // Activity (theme, intent extras) and calls Android's onCreate.
        super.onCreate(bundle);
        if (isFinishing()) return;
        waitingForRuby = true;
        createState = bundle;
        showPlaceholder();
        waitForJRuby(this);
    }

   /**
    Whether to start through SplashActivity (finish this Activity and start it
    again once JRuby is up) instead of waiting for JRuby in this Activity.
    True if the app has a res/layout/splash.xml.
    */
    protected boolean useSplashActivity() {
        return getResources().getIdentifier("splash", "layout", getPackageName()) != 0;
    }

    private void showSplash() {
        Intent splashIntent = new Intent(this, SplashActivity.class);
        splashIntent.putExtra(Intent.EXTRA_INTENT, futureIntent());
        startActivity(splashIntent);
    }

    // The Intent to to call when done. Defaults to calling this Activity again.
    // Override to change.
    protected Intent futureIntent() {
        if (getIntent().getAction() == null || !getIntent().getAction().equals(Intent.ACTION_VIEW)) {
            return new Intent(getIntent()).setAction(Intent.ACTION_VIEW);
        } else {
            return getIntent();
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    //
    // Waiting for JRuby
    //

    private static void waitForJRuby(EntryPointActivity activity) {
        waitingForJRuby.add(activity);
        if (startingJRuby) return;
        startingJRuby = true;
        final android.content.Context appContext = activity.getApplicationContext();
        final String preloadClassName = activity.getClass().getName();
        final Handler mainHandler = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            public void run() {
                final boolean jrubyOk = JRubyAdapter.setUpJRuby(appContext);
                if (jrubyOk) {
                    // Evaluate the script here rather than on the UI thread,
                    // where a large script would block input past the ANR
                    // timeout.
                    ScriptLoader.preloadScript(preloadClassName);
                }
                mainHandler.post(new Runnable() {
                    public void run() {
                        jrubyStarted(jrubyOk);
                    }
                });
            }
        }, "Ruboto start-up").start();
    }

    private static void jrubyStarted(boolean jrubyOk) {
        startingJRuby = false;
        ArrayList<EntryPointActivity> ready = new ArrayList<EntryPointActivity>(waitingForJRuby);
        waitingForJRuby.clear();
        for (EntryPointActivity activity : ready) {
            activity.rubyReady(jrubyOk);
        }
    }

    private void rubyReady(boolean jrubyOk) {
        if (destroyed || isFinishing()) return;
        if (!jrubyOk) {
            showStartupError();
            return;
        }
        if (JRubyAdapter.isDebugBuild()) Log.d("EntryPointActivity JRuby ready: " + getClass().getName());
        waitingForRuby = false;
        removePlaceholder();
        getScriptInfo().setRubyClassName(getClass().getSimpleName());
        ScriptLoader.loadScript(this);
        Bundle state = createState;
        createState = null;
        replay("onCreate", "on_create", state);
        // Ruby on_create may have finished the Activity or started another one.
        if (destroyed || isFinishing()) return;
        if (started) replay("onStart", "on_start");
        if (state != null) replay("onRestoreInstanceState", "on_restore_instance_state", state);
        replay("onPostCreate", "on_post_create", state);
        if (resumed) {
            replay("onResume", "on_resume");
            replay("onPostResume", "on_post_resume");
        }
        if (hasWindowFocus()) replay("onWindowFocusChanged", "on_window_focus_changed", true);
        // The options menu was built while no Ruby handler could answer.
        invalidateOptionsMenu();
        try {
            // The Activity was displayed with the placeholder; this marks the
            // point where it shows its real content ("Fully drawn" in logcat).
            reportFullyDrawn();
        } catch (RuntimeException e) {
            Log.d("reportFullyDrawn failed: " + e);
        }
    }

    private void replay(String camelName, String snakeName, Object... args) {
        String handler = ScriptLoader.rubyHandler(this, camelName, snakeName);
        if (handler == null) return;
        String outer = replaying;
        replaying = camelName;
        try {
            JRubyAdapter.runRubyMethod(getScriptInfo().getRubyInstance(), handler, args);
        } finally {
            replaying = outer;
        }
    }

    // A plain spinner in the content area.  Not set with setContentView, so
    // that the Ruby on_create can still request window features without an
    // exception (they no longer change the window decor, though).
    private void showPlaceholder() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.addView(new ProgressBar(this));
        ViewGroup content = (ViewGroup) findViewById(android.R.id.content);
        content.addView(layout, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        placeholder = layout;
    }

    private void removePlaceholder() {
        if (placeholder != null && placeholder.getParent() instanceof ViewGroup) {
            ((ViewGroup) placeholder.getParent()).removeView(placeholder);
        }
        placeholder = null;
    }

    private void showStartupError() {
        Toast.makeText(this, "Failed to initialize Ruboto Core.", Toast.LENGTH_LONG).show();
        if (placeholder instanceof LinearLayout) {
            TextView text = new TextView(this);
            text.setGravity(Gravity.CENTER);
            text.setText("Woops!  Ruboto failed to initialize properly!  I am not sure how to proceed from here.  If you can, please file an error report at http://ruboto.org/");
            ((LinearLayout) placeholder).addView(text);
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    //
    // Life cycle bookkeeping for the replay.  A call while its Ruby handler is
    // replayed is that handler's super call: Android already ran it.
    //

    public void onStart() {
        if ("onStart".equals(replaying)) return;
        started = true;
        super.onStart();
    }

    public void onRestoreInstanceState(Bundle savedInstanceState) {
        if ("onRestoreInstanceState".equals(replaying)) return;
        super.onRestoreInstanceState(savedInstanceState);
    }

    public void onPostCreate(Bundle savedInstanceState) {
        if ("onPostCreate".equals(replaying)) return;
        super.onPostCreate(savedInstanceState);
    }

    public void onResume() {
        if ("onResume".equals(replaying)) return;
        resumed = true;
        super.onResume();
    }

    public void onPostResume() {
        if ("onPostResume".equals(replaying)) return;
        super.onPostResume();
    }

    public void onWindowFocusChanged(boolean hasFocus) {
        if ("onWindowFocusChanged".equals(replaying)) return;
        super.onWindowFocusChanged(hasFocus);
    }

    public void onPause() {
        resumed = false;
        super.onPause();
    }

    public void onStop() {
        started = false;
        super.onStop();
    }

    public void onDestroy() {
        destroyed = true;
        if (waitingForRuby) {
            waitingForJRuby.remove(this);
        }
        super.onDestroy();
    }
}
