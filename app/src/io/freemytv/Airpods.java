package io.freemytv;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Local addition (not in upstream Free My TV): the "AirPods" button. One press connects the AirPods the TV is already
 * paired with (Android TV's own Bluetooth menu has no "Connect" for a paired device). Pressed while they're connected,
 * it offers to disconnect. If they don't answer in a few seconds (busy with the iPhone or Mac, or still in the case),
 * it asks whether to connect them to the TV, and keeps trying for a while on "Yes".
 * BluetoothA2dp.connect/disconnect are hidden on Android 9 (greylisted, reachable by reflection); where that fails the
 * button opens the TV's Bluetooth settings instead.
 */
final class Airpods {
    private Airpods() {}

    private static final long FIRST_WAIT = 7_000, RETRY_EVERY = 4_000, RETRY_FOR = 25_000, POLL = 500;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean busy;

    static void press(final Activity a) {
        if (busy) {
            toast(a, "Вже підключаю AirPods…");
            return;
        }
        final BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
        if (bt == null) {
            openSettings(a);
            return;
        }
        if (!bt.isEnabled()) {
            try {
                bt.enable();
            } catch (SecurityException e) {
                openSettings(a);
                return;
            }
            toast(a, "Вмикаю Bluetooth…");
            MAIN.postDelayed(() -> press(a), 3_000);
            return;
        }
        final BluetoothDevice dev = find(bt);
        if (dev == null) {
            toast(a, "AirPods не знайдено серед спарених пристроїв. Спаруйте їх у меню Bluetooth.");
            openSettings(a);
            return;
        }
        busy = true;
        boolean ok = bt.getProfileProxy(a.getApplicationContext(), new BluetoothProfile.ServiceListener() {
            @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
                MAIN.post(() -> start(a, bt, (BluetoothA2dp) proxy, dev));
            }

            @Override public void onServiceDisconnected(int profile) {}
        }, BluetoothProfile.A2DP);
        if (!ok) {
            busy = false;
            openSettings(a);
        }
    }

    /** The paired AirPods: the first bonded device whose name says AirPods. */
    private static BluetoothDevice find(BluetoothAdapter bt) {
        try {
            for (BluetoothDevice d : bt.getBondedDevices()) {
                String name = d.getName();
                if (name != null && name.toLowerCase(Locale.ROOT).contains("airpods")) return d;
            }
        } catch (SecurityException ignored) {
        }
        return null;
    }

    private static void start(final Activity a, final BluetoothAdapter bt, final BluetoothA2dp a2dp, final BluetoothDevice dev) {
        if (state(a2dp, dev) == BluetoothProfile.STATE_CONNECTED) {
            done(bt, a2dp);
            if (a.isFinishing()) return;
            new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("AirPods уже підключені")
                    .setMessage("Відключити їх від телевізора?")
                    .setPositiveButton("Відключити", (d, w) -> disconnect(a, bt, dev))
                    .setNegativeButton("Ні", null)
                    .show();
            return;
        }
        allow(a, bt, a2dp, dev, true);
        if (!call(a2dp, "connect", dev)) {
            done(bt, a2dp);
            toast(a, "Телевізор не дозволяє підключити напряму. Відкриваю Bluetooth.");
            openSettings(a);
            return;
        }
        toast(a, "Підключаю AirPods…");
        waitFor(a, bt, a2dp, dev, FIRST_WAIT, () -> {
            done(bt, a2dp);
            if (a.isFinishing()) return;
            new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("AirPods не відповідають")
                    .setMessage("AirPods зайняті іншим пристроєм. Підключити до телевізора?")
                    .setPositiveButton("Так", (d, w) -> insist(a, bt, dev))
                    .setNegativeButton("Ні", null)
                    .show();
        });
    }

    /** "Yes": a fresh proxy, then connect again every few seconds until they come (or RETRY_FOR runs out). */
    private static void insist(final Activity a, final BluetoothAdapter bt, final BluetoothDevice dev) {
        busy = true;
        toast(a, "Підключаю AirPods до телевізора… Відкрийте кейс поруч.");
        bt.getProfileProxy(a.getApplicationContext(), new BluetoothProfile.ServiceListener() {
            @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
                final BluetoothA2dp a2dp = (BluetoothA2dp) proxy;
                allow(a, bt, a2dp, dev, true);
                final long until = System.currentTimeMillis() + RETRY_FOR;
                MAIN.post(new Runnable() {
                    @Override public void run() {
                        if (state(a2dp, dev) == BluetoothProfile.STATE_CONNECTED) {
                            connected(a, bt, a2dp, dev);
                            return;
                        }
                        if (System.currentTimeMillis() > until) {
                            allow(a, bt, a2dp, dev, false);
                            done(bt, a2dp);
                            toast(a, "Не вдалося підключити AirPods. Відкрийте кейс біля телевізора й спробуйте ще раз.");
                            return;
                        }
                        if (state(a2dp, dev) != BluetoothProfile.STATE_CONNECTING) call(a2dp, "connect", dev);
                        MAIN.postDelayed(this, RETRY_EVERY);
                    }
                });
            }

            @Override public void onServiceDisconnected(int profile) {}
        }, BluetoothProfile.A2DP);
    }

    private static void waitFor(final Activity a, final BluetoothAdapter bt, final BluetoothA2dp a2dp, final BluetoothDevice dev,
                                long wait, final Runnable failed) {
        final long until = System.currentTimeMillis() + wait;
        MAIN.post(new Runnable() {
            @Override public void run() {
                if (state(a2dp, dev) == BluetoothProfile.STATE_CONNECTED) {
                    connected(a, bt, a2dp, dev);
                } else if (System.currentTimeMillis() > until) {
                    failed.run();
                } else {
                    MAIN.postDelayed(this, POLL);
                }
            }
        });
    }

    private static void connected(Activity a, BluetoothAdapter bt, BluetoothA2dp a2dp, BluetoothDevice dev) {
        call(a2dp, "setActiveDevice", dev);   // the sound goes to them, not the TV's speakers
        allow(a, bt, a2dp, dev, false);       // connected now; they don't come back on their own later
        done(bt, a2dp);
        toast(a, "AirPods підключено");
    }

    private static void disconnect(final Activity a, final BluetoothAdapter bt, final BluetoothDevice dev) {
        bt.getProfileProxy(a.getApplicationContext(), new BluetoothProfile.ServiceListener() {
            @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
                BluetoothA2dp a2dp = (BluetoothA2dp) proxy;
                allow(a, bt, a2dp, dev, false);
                boolean ok = call(a2dp, "disconnect", dev);
                bt.closeProfileProxy(BluetoothProfile.A2DP, a2dp);
                MAIN.post(() -> toast(a, ok ? "AirPods відключено" : "Не вдалося відключити AirPods"));
            }

            @Override public void onServiceDisconnected(int profile) {}
        }, BluetoothProfile.A2DP);
    }

    /**
     * Local build: whether Android may connect them on its own. Off (priority 0) except while the button connects them,
     * so taking them out of the case near the TV no longer pulls them over: only the button does. Android 9 refuses
     * connect() at priority 0, hence on (100) just before. A2DP and, where the TV uses it, the headset profile.
     */
    private static void allow(Activity a, BluetoothAdapter bt, BluetoothA2dp a2dp, BluetoothDevice dev, boolean on) {
        int priority = on ? 100 : 0;
        setPriority(a2dp, dev, priority);
        try {
            bt.getProfileProxy(a.getApplicationContext(), new BluetoothProfile.ServiceListener() {
                @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    setPriority(proxy, dev, priority);
                    bt.closeProfileProxy(BluetoothProfile.HEADSET, proxy);
                }

                @Override public void onServiceDisconnected(int profile) {}
            }, BluetoothProfile.HEADSET);
        } catch (RuntimeException ignored) {
        }
    }

    private static void setPriority(Object proxy, BluetoothDevice dev, int priority) {
        try {
            Method m = hidden(proxy.getClass(), "setPriority", BluetoothDevice.class, int.class);
            Object ok = m.invoke(proxy, dev, priority);
            AppLog.w("AirPods: priority " + priority + " -> " + ok);
        } catch (Throwable t) {
            AppLog.w("AirPods: setPriority failed: " + t);
        }
    }

    /**
     * A hidden framework method. setPriority is on Android 9's dark greylist, so a plain getMethod from the app throws
     * NoSuchMethodException; looked up through reflection on Class itself, the caller is the boot class path and
     * the check lets it through (works up to Android 10).
     */
    private static Method hidden(Class<?> c, String name, Class<?>... params) throws Exception {
        try {
            Method get = Class.class.getDeclaredMethod("getMethod", String.class, Class[].class);
            return (Method) get.invoke(c, name, params);
        } catch (java.lang.reflect.InvocationTargetException e) {
            return c.getMethod(name, params);
        }
    }

    private static int state(BluetoothA2dp a2dp, BluetoothDevice dev) {
        try {
            return a2dp.getConnectionState(dev);
        } catch (RuntimeException e) {
            return BluetoothProfile.STATE_DISCONNECTED;
        }
    }

    /** A hidden BluetoothA2dp method taking the device (connect, disconnect, setActiveDevice). */
    private static boolean call(BluetoothA2dp a2dp, String name, BluetoothDevice dev) {
        try {
            Method m = hidden(BluetoothA2dp.class, name, BluetoothDevice.class);
            Object r = m.invoke(a2dp, dev);
            return !(r instanceof Boolean) || (Boolean) r;
        } catch (Throwable t) {
            AppLog.w("AirPods: " + name + " failed: " + t);
            return false;
        }
    }

    private static void done(BluetoothAdapter bt, BluetoothA2dp a2dp) {
        busy = false;
        try {
            bt.closeProfileProxy(BluetoothProfile.A2DP, a2dp);
        } catch (RuntimeException ignored) {
        }
    }

    private static void openSettings(Activity a) {
        Intent[] tries = {
                new Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
                new Intent().setClassName("com.android.tv.settings", "com.android.tv.settings.accessories.AddAccessoryActivity"),
                new Intent(Settings.ACTION_SETTINGS)};
        for (Intent i : tries) {
            try {
                a.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
            }
        }
    }

    private static void toast(Activity a, String text) {
        Toast.makeText(a.getApplicationContext(), text, Toast.LENGTH_LONG).show();
    }
}
