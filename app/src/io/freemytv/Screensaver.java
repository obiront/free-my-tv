package io.freemytv;

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
    }

    @Override public void onDreamingStopped() {
        if (view != null) view.stop();
        super.onDreamingStopped();
    }

    @Override public void onDetachedFromWindow() {
        if (view != null) view.stop();
        super.onDetachedFromWindow();
    }
}
