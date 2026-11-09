package io.freemytv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Local addition (not in upstream Free My TV): Bing's photo of the day as "My Photos". Every 12 hours it asks
 * bing.com for the latest ones (no key, no account), drops the new ones into the photo inbox and lets Photos import
 * them; Photos then puts one behind the home screen each hour and shows them all on the screensaver. Only the newest
 * KEEP stay. Talks to www.bing.com only.
 */
final class Bing {
    private Bing() {}

    private static final String ARCHIVE = "https://www.bing.com/HPImageArchive.aspx?format=js&mkt=en-US&n=8&idx=";
    private static final long EVERY = 12 * 3_600_000L;
    private static final int KEEP = 45, TIMEOUT = 15_000;
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static boolean running;

    /** Cheap when nothing is due (one file's date). `done` runs on the main thread after new photos were imported. */
    static void refresh(Context c, final Runnable done) {
        final Context app = c.getApplicationContext();
        final File stamp = new File(app.getFilesDir(), "bing.stamp");
        if (Math.abs(System.currentTimeMillis() - stamp.lastModified()) < EVERY && stamp.exists()) return;
        synchronized (Bing.class) {
            if (running) return;
            running = true;
        }
        worker.execute(() -> {
            int added = 0;
            try {
                added = fetch(app);
                touch(stamp);
            } catch (Exception e) {
                AppLog.w("bing: " + e);
            } finally {
                synchronized (Bing.class) {
                    running = false;
                }
            }
            if (added > 0) main.post(() -> Photos.importInbox(app, done));
        });
    }

    private static int fetch(Context c) throws Exception {
        File seenFile = new File(c.getFilesDir(), "bing-seen.txt");
        Set<String> seen = new HashSet<>();
        if (seenFile.exists()) for (String s : new String(read(seenFile), StandardCharsets.UTF_8).split("\n")) if (!s.isEmpty()) seen.add(s);
        // Newest first from Bing; the first run also takes the week before (idx 7), so it starts with ~15 photos.
        List<String[]> images = new ArrayList<>();
        for (int idx : seen.isEmpty() ? new int[] {7, 0} : new int[] {0}) {
            JSONArray list = new JSONObject(new String(get(ARCHIVE + idx), StandardCharsets.UTF_8)).getJSONArray("images");
            for (int i = list.length() - 1; i >= 0; i--) {   // oldest first: the order Photos imports them in
                JSONObject im = list.getJSONObject(i);
                images.add(new String[] {im.getString("startdate"), im.getString("urlbase")});
            }
        }
        File inbox = Photos.privateInbox(c);
        if (!inbox.isDirectory() && !inbox.mkdirs()) throw new IOException("no inbox");
        int added = 0;
        StringBuilder newSeen = new StringBuilder();
        for (String[] im : images) {
            String id = im[0];
            if (seen.contains(id)) continue;
            byte[] jpg = get("https://www.bing.com" + im[1] + "_1920x1080.jpg");
            File part = new File(inbox, ".bing-" + id + ".part");   // hidden while written: Photos skips it
            try (OutputStream os = new FileOutputStream(part)) {
                os.write(jpg);
            }
            if (!part.renameTo(new File(inbox, "bing-" + id + ".jpg"))) {
                part.delete();
                continue;
            }
            seen.add(id);
            newSeen.append(id).append('\n');
            added++;
        }
        if (added > 0) {
            try (OutputStream os = new FileOutputStream(seenFile, true)) {
                os.write(newSeen.toString().getBytes(StandardCharsets.UTF_8));
            }
            Photos.trim(c, KEEP - added);
        }
        AppLog.w("bing: " + added + " new photos");
        return added;
    }

    private static byte[] get(String url) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(TIMEOUT);
        h.setReadTimeout(TIMEOUT);
        h.setRequestProperty("User-Agent", "FreeMyTV");
        try {
            if (h.getResponseCode() != 200) throw new IOException(url + ": HTTP " + h.getResponseCode());
            try (InputStream in = h.getInputStream()) {
                return readAll(in);
            }
        } finally {
            h.disconnect();
        }
    }

    private static byte[] read(File f) throws IOException {
        try (InputStream in = new java.io.FileInputStream(f)) {
            return readAll(in);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static void touch(File f) throws IOException {
        if (!f.exists()) new FileOutputStream(f).close();
        f.setLastModified(System.currentTimeMillis());
    }
}
