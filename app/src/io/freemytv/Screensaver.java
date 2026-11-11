package io.freemytv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.service.dreams.DreamService;
import android.view.KeyEvent;

/**
 * The Free My TV screensaver (enabled in Free My TV mode via `settings put secure screensaver_components`). The system
 * still decides when it starts and when the TV turns off (e.g. after 10 min idle, then 2 h later), as before: the app
 * doesn't change those timings. Any key exits; with Watch from the screensaver on (Home Settings), OK first opens the
 * title on screen in its app.
 */
public class Screensaver extends DreamService {
    private DreamView view;

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        Watchdog.install(this);
        Ui.init(this);
        // Local build: always interactive, so a key first fades the clock out (DreamView.leave), then closes.
        setInteractive(true);
        setFullscreen(true);
        setScreenBright(true);
        view = new DreamView(this);
        setContentView(view);
    }

    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean closing;

    /** Local build: the Mi button (RemoteKeys, in the :keys process) asks a running screensaver to close gently. */
    private static final String ACTION_LEAVE = "io.freemytv.LEAVE_SCREENSAVER";

    /** If the screensaver is on, it fades out and opens the home screen; otherwise `otherwise` runs (on the main thread). */
    static void leaveOr(Context c, Runnable otherwise) {
        Intent ask = new Intent(ACTION_LEAVE).setPackage(c.getPackageName());
        c.sendOrderedBroadcast(ask, null, new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (getResultCode() != 1) otherwise.run();
            }
        }, null, 0, null, null);
    }

    private static final String EXTRA_THEN = "then";

    /**
     * Local build: closes a running screensaver gently (its clock fades out), then `then` runs; without a screensaver,
     * `then` runs at once. On the main thread either way.
     */
    static void leaveThen(Context c, Runnable then) {
        Intent ask = new Intent(ACTION_LEAVE).setPackage(c.getPackageName()).putExtra(EXTRA_THEN, true);
        c.sendOrderedBroadcast(ask, null, new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (getResultCode() != 1) then.run();
                else new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(then, DreamView.LEAVE_MS + 50);
            }
        }, null, 0, null, null);
    }

    private final BroadcastReceiver leave = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            setResultCode(1);
            if (closing) return;
            closing = true;
            if (view != null) view.leave();
            boolean callerOpens = intent.getBooleanExtra(EXTRA_THEN, false);
            handler.postDelayed(() -> {
                // Over the home screen, closing is enough (opening it again would reset and slide its focus);
                // over another app, the home screen opens, as the Mi button does (unless the caller opens something).
                if (!callerOpens && !HomeActivity.shown) {
                    try {
                        startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (RuntimeException e) {
                        AppLog.w("screensaver: couldn't open home: " + e.getMessage());
                    }
                }
                finish();
            }, DreamView.LEAVE_MS);
        }
    };
    private boolean listening;

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        // OK acts when it's released, so the app it opens doesn't get the release; any other key closes.
        if (!closing && (ok ? event.getAction() == KeyEvent.ACTION_UP : event.getAction() == KeyEvent.ACTION_DOWN)) {
            closing = true;
            if (ok && Prefs.saverWatch() && view != null && view.watch()) {
                finish();   // a title opens in its app: no fade
            } else if (view != null) {
                view.leave();
                handler.postDelayed(this::finish, DreamView.LEAVE_MS);
            } else {
                finish();
            }
        }
        return true;
    }

    @Override public void onDreamingStarted() {
        super.onDreamingStarted();
        if (view != null) view.start();
        if (!listening) {
            registerReceiver(leave, new IntentFilter(ACTION_LEAVE));
            listening = true;
        }
    }

    @Override public void onDreamingStopped() {
        stopListening();
        if (view != null) view.stop();
        super.onDreamingStopped();
    }

    private void stopListening() {
        if (!listening) return;
        listening = false;
        try {
            unregisterReceiver(leave);
        } catch (IllegalArgumentException ignored) {
        }
    }

    @Override public void onDetachedFromWindow() {
        stopListening();
        if (view != null) view.stop();
        super.onDetachedFromWindow();
    }
}
