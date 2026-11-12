package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Sees the remote's keys before any app does, so the app buttons work from inside Netflix, Disney and the rest. The
 * system waits for it on every key (up to half a second), so it runs in a process of its own and does next to nothing.
 */
public class RemoteKeyService extends AccessibilityService {
    @Override protected boolean onKeyEvent(KeyEvent event) {
        return RemoteKeys.onKey(this, event);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        // Local build: what is in front (for the Mi button's HDMI toggle). Only window changes come here, and only a
        // reference is kept.
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.getPackageName() != null) {
            RemoteKeys.front(this, String.valueOf(event.getPackageName()), event.getClassName());
        }
    }

    @Override public void onInterrupt() {}

    // Local build: the key filter pauses for a few seconds whenever the volume changes, so a held Vol+/− repeats
    // (see RemoteKeys.paused).
    private static final long PAUSE_MS = 5_000;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable resume = () -> filterKeys(true);
    private final android.content.BroadcastReceiver volume = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context c, android.content.Intent i) {
            filterKeys(false);
            handler.removeCallbacks(resume);
            handler.postDelayed(resume, PAUSE_MS);
        }
    };
    private boolean listening;

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        if (!listening && RemoteKeys.XIAOMI) {
            registerReceiver(volume, new android.content.IntentFilter("android.media.VOLUME_CHANGED_ACTION"));
            listening = true;
        }
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(resume);
        if (listening) {
            try {
                unregisterReceiver(volume);
            } catch (IllegalArgumentException ignored) {
            }
            listening = false;
        }
        super.onDestroy();
    }

    private void filterKeys(boolean on) {
        android.accessibilityservice.AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) return;
        boolean now = (info.flags & android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS) != 0;
        RemoteKeys.paused = !on;
        if (now == on) return;
        if (on) info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        else info.flags &= ~android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        setServiceInfo(info);
        AppLog.w("remote keys " + (on ? "filtered again" : "paused for the volume"));
    }
}
