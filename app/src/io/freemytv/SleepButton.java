package io.freemytv;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.Toast;

/**
 * Local addition (not in upstream Free My TV): the sleep timer as a top button. A short list of times; the timer itself
 * is Settings' own (SleepTimer, which puts the TV to sleep through its ADB).
 */
final class SleepButton {
    private SleepButton() {}

    private static final int[] MINUTES = {0, 15, 30, 45, 60, 90, 120};

    static void press(final Activity a) {
        int left = SleepTimer.minutesLeft(a);
        String[] names = new String[MINUTES.length];
        for (int i = 0; i < MINUTES.length; i++) names[i] = MINUTES[i] == 0 ? "Вимкнути таймер" : SleepTimer.duration(MINUTES[i]);
        new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(left == 0 ? "Таймер сну" : "Таймер сну: залишилось " + SleepTimer.duration(left))
                .setItems(names, (d, which) -> {
                    int m = MINUTES[which];
                    SleepTimer.set(a, m);
                    Toast.makeText(a.getApplicationContext(),
                            m == 0 ? "Таймер сну вимкнено" : "Телевізор вимкнеться через " + SleepTimer.duration(m),
                            Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("Скасувати", null)
                .show();
    }
}
