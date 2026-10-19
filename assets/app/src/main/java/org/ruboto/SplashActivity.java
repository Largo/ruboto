package org.ruboto;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;


public class SplashActivity extends Activity {
    private int splash = 0;
    private TextView progressText;
    // Set on the UI thread, read on the JRuby start-up thread.
    private volatile boolean cancelled = false;
    // Set on the JRuby start-up thread, read on the UI thread.
    private volatile boolean userActivityStarted = false;
    private static SplashActivity current;
    private static final long FALLBACK_FINISH_DELAY_MS = 5000;
    private static final int INSTALL_REQUEST_CODE = 4242;

    public void onCreate(Bundle bundle) {
        Log.d("SplashActivity onCreate:");
        splash = getResources().getIdentifier("splash", "layout", getPackageName());
        current = this;
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        super.onCreate(bundle);
        if (Build.VERSION.SDK_INT >= 33) {
            BackCallback.register(this);
        }
        if (!JRubyAdapter.isInitialized()) {
            initJRuby(true);
        }
    }

    public void onResume() {
        Log.d("SplashActivity onResume: ");
        super.onResume();
    }

    public void onPause() {
        Log.d("SplashActivity onPause: ");
        super.onPause();
    }

    // Called by EntryPointActivity once the user Activity exists.  Finishing
    // the splash any earlier (right after startActivity, or through
    // noHistory while the screen is off) leaves the process without an
    // Activity until the new one attaches.  Devices that keep no background
    // processes kill such an "empty" process at once, which turns the launch
    // into an endless restart loop.
    static void userActivityCreated() {
        SplashActivity splash = current;
        if (splash != null && splash.userActivityStarted) {
            splash.finish();
        }
    }

    // Fallback for user Activities that are not EntryPointActivities: once
    // the splash is stopped after starting them, they cover it.  Not while the
    // screen is off: then the splash is stopped because of that, possibly
    // before the user Activity exists.
    public void onStop() {
        super.onStop();
        if (userActivityStarted && isInteractive()) {
            finish();
        }
    }

    private boolean isInteractive() {
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        return power == null || power.isInteractive();
    }

    public void onDestroy() {
        Log.d("SplashActivity onDestroy: ");
        if (current == this) {
            current = null;
        }
        // Finished some other way before the user Activity was started (back
        // that reached neither handler below): do not start it any more.  Not
        // when recreated for a configuration change, the start-up thread then
        // still has to start it.
        if (isFinishing() && !userActivityStarted) {
            cancelled = true;
        }
        super.onDestroy();
    }

    // Back while JRuby is starting leaves the app.  The start-up thread keeps
    // running, but no longer starts the user Activity.
    public void onBackPressed() {
        cancelStartUp();
    }

    private void cancelStartUp() {
        cancelled = true;
        finishAndRemoveTask();
    }

    // Apps targeting API 36 get predictive back on Android 16+, where
    // onBackPressed() is no longer called.  Below that, or without the opt-in,
    // the platform ignores the callback and calls onBackPressed() as before.
    // A class of its own so that the API 33 types are only resolved there.
    private static final class BackCallback {
        static void register(final SplashActivity splash) {
            splash.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, new OnBackInvokedCallback() {
                        public void onBackInvoked() {
                            splash.cancelStartUp();
                        }
                    });
        }
    }

    private void initJRuby(final boolean firstTime) {
        showProgress();
        new Thread(new Runnable() {
            public void run() {
                final boolean jrubyOk = JRubyAdapter.setUpJRuby(SplashActivity.this);
                if (cancelled) {
                    return;
                }
                if (jrubyOk) {
                    Log.d("SplashActivity onResume: JRuby OK");
                    preloadUserActivityScript();
                    startUserActivity();
                } else {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            Toast.makeText(SplashActivity.this, "Failed to initialize Ruboto Core.", Toast.LENGTH_LONG).show();
                            TextView textView = progressText != null ? progressText
                                    : (TextView) findViewById(android.R.id.text1);
                            if (textView != null) {
                                textView.setText("Woops!  Ruboto failed to initialize properly!  I am not sure how to proceed from here.  If you can, please file an error report at http://ruboto.org/");
                            }
                        }
                    });
                }
            }
        }).start();
    }

    private void showProgress() {
        if (splash > 0) {
            Log.i("SplashActivity Showing splash");
            setContentView(splash);
        } else {
            // A plain spinner instead of the deprecated ProgressDialog, which
            // also put a second window on screen during start-up.
            Log.i("SplashActivity Showing progress");
            LinearLayout layout = new LinearLayout(this);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setGravity(Gravity.CENTER);
            layout.addView(new ProgressBar(this));
            progressText = new TextView(this);
            progressText.setText("Starting...");
            progressText.setGravity(Gravity.CENTER);
            layout.addView(progressText);
            setContentView(layout);
        }
    }

    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        Log.d("SplashActivity onActivityResult: " + requestCode + ", " + resultCode + ", " + data);
        Log.d("SplashActivity onActivityResult: " + INSTALL_REQUEST_CODE + ", " + RESULT_OK + ", " + RESULT_CANCELED);
        if (requestCode == INSTALL_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                Log.d("SplashActivity onActivityResult: Install OK.");
            } else if (resultCode == RESULT_CANCELED) {
                Log.d("SplashActivity onActivityResult: Install canceled.");
                if (!JRubyAdapter.isInitialized()) {
                    finish();
                }
                // EMXIF
            } else {
                Log.e("SplashActivity onActivityResult: resultCode: " + resultCode);
            }
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    // Evaluate the script for the Activity we are about to start, while we are
    // still on the background thread and the splash is covering for us.  Doing it
    // in that Activity's onCreate blocks the UI thread past the input dispatcher's
    // 5 second focus timeout and the app is killed before it draws.
    private void preloadUserActivityScript() {
        Intent userIntent = (Intent) getIntent().getParcelableExtra(Intent.EXTRA_INTENT);
        if (userIntent != null && userIntent.getComponent() != null) {
            ScriptLoader.preloadScript(userIntent.getComponent().getClassName());
        }
    }

    private void startUserActivity() {
        if (!cancelled && getIntent().hasExtra(Intent.EXTRA_INTENT)) {
            userActivityStarted = true;
            startActivity((Intent)getIntent().getParcelableExtra(Intent.EXTRA_INTENT));
            // Last resort for targets that neither report back nor cover the
            // splash completely (e.g. a translucent non-Ruboto Activity); by
            // now the new Activity has long been attached to the process.
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                public void run() {
                    if (!isFinishing()) {
                        finish();
                    }
                }
            }, FALLBACK_FINISH_DELAY_MS);
        }
    }

}
