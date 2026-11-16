package tv.dumbtv.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Keeps the rotating wallpapers fresh: every day or week it fetches a few new pictures and
 * drops the oldest, so the set turns over by itself. The same three sources as
 * {@code tcl.sh hintergruende edel}, none of which needs an account: satellite views from
 * Google Earth View, featured photographs of landmarks from Wikimedia Commons and European
 * paintings from the Metropolitan Museum's open-access collection.
 *
 * Only files it recognises by name (erde-, ort-, kunst-) are ever deleted; pictures the owner
 * put on the TV under other names stay.
 */
final class Gallery {
    private static final long DAY = 86_400_000L, RETRY = 30 * 60_000L;
    /** How many fetched pictures are kept; older ones make room for new ones. */
    private static final int KEEP = 36;
    private static final String[] KINDS = {"erde-", "ort-", "kunst-"};
    private static final String[] SIGHTS = {
        "Featured pictures of castles", "Featured pictures of bridges",
        "Featured pictures of cityscapes", "Featured pictures of skylines",
    };
    private static final String[] TOPICS = {"landscape", "sea", "river", "mountain", "garden", "sky", "harbor"};
    private static final String MET = "https://collectionapi.metmuseum.org/public/collection";
    private static final String COMMONS = "https://commons.wikimedia.org/w/api.php";

    private static Gallery instance;

    private final Context app;
    private final SharedPreferences store;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private volatile boolean busy;
    private long tried;

    static synchronized Gallery get(Context c) {
        if (instance == null) instance = new Gallery(c.getApplicationContext());
        return instance;
    }

    private Gallery(Context c) {
        app = c;
        store = c.getSharedPreferences("gallery", Context.MODE_PRIVATE);
    }

    /** Fetches a round of new pictures when one is due. Cheap; called whenever the app shows. */
    void refresh(final Config cfg) {
        String mode = cfg.get(Config.GALLERY);
        if ("off".equals(mode) || busy) return;
        final boolean weekly = "weekly".equals(mode);
        final long now = System.currentTimeMillis();
        long last = store.getLong("last", 0);
        // "now < last" is a clock that was set back; then one round resets the stamp
        if (now >= last && now - last < (weekly ? 7 * DAY : DAY)) return;
        long up = SystemClock.elapsedRealtime();
        if (tried != 0 && up - tried < RETRY) return;
        tried = up;
        if (!online()) return;
        final File dir = cfg.wallpaperDir();
        busy = true;
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!dir.isDirectory() && !dir.mkdirs()) return;
                    int each = weekly ? 3 : 1;
                    int got = earth(dir, each) + sights(dir, each) + art(dir, each);
                    if (got > 0) {
                        store.edit().putLong("last", now).apply();
                        prune(cfg);
                    }
                } catch (RuntimeException | OutOfMemoryError e) {
                    Log.w("dumbtv", "gallery: " + e);
                } finally {
                    busy = false;
                }
            }
        });
    }

    @SuppressWarnings("deprecation")
    private boolean online() {
        ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo active = cm == null ? null : cm.getActiveNetworkInfo();
        return active != null && active.isConnected();
    }

    // ---------------------------------------------------------------- the three sources

    /** Earth View has no public list; its ids run from about 1000 to 2450 with gaps. */
    private int earth(File dir, int want) {
        int got = 0;
        for (int tries = 0; got < want && tries < want * 6 + 4; tries++) {
            int id = 1000 + random.nextInt(1450);
            File to = new File(dir, "erde-" + id + ".jpg");
            if (to.exists()) continue;
            if (save("https://www.gstatic.com/prettyearth/assets/full/" + id + ".jpg", to, 0)) got++;
        }
        return got;
    }

    /** Commons' featured pictures: landscape format, sharp, and not a panorama strip. */
    private int sights(File dir, int want) {
        int got = 0;
        for (int tries = 0; got < want && tries < 6; tries++) {
            try {
                String cat = SIGHTS[random.nextInt(SIGHTS.length)];
                char letter = "ABCDEFGHIJKLMNOPRSTUVW".charAt(random.nextInt(22));
                JSONObject query = new JSONObject(Feed.http(COMMONS + "?action=query&format=json"
                        + "&generator=categorymembers&gcmtitle=" + Feed.encode("Category:" + cat)
                        + "&gcmtype=file&gcmlimit=12&gcmsort=sortkey&gcmstartsortkeyprefix=" + letter
                        + "&prop=imageinfo&iiprop=" + Feed.encode("url|size|mime") + "&iiurlwidth=1920"))
                        .optJSONObject("query");
                JSONObject pages = query == null ? null : query.optJSONObject("pages");
                if (pages == null) continue;
                for (Iterator<String> it = pages.keys(); it.hasNext() && got < want; ) {
                    String id = it.next();
                    JSONArray infos = pages.getJSONObject(id).optJSONArray("imageinfo");
                    JSONObject info = infos == null ? null : infos.optJSONObject(0);
                    if (info == null || !"image/jpeg".equals(info.optString("mime"))) continue;
                    int w = info.optInt("width"), h = info.optInt("height");
                    if (w < 3000 || w * 10 < h * 13 || w * 10 > h * 21) continue;
                    String url = info.optString("thumburl");
                    if (url.isEmpty()) continue;
                    File to = new File(dir, "ort-" + id + ".jpg");
                    if (to.exists()) continue;
                    if (save(url, to, 0)) got++;
                }
            } catch (IOException | JSONException e) {
                Log.w("dumbtv", "gallery sights: " + e);
            }
        }
        return got;
    }

    /** European paintings clearly wider than tall, so the TV does not cut half of them away. */
    private int art(File dir, int want) {
        int got = 0;
        for (int tries = 0; got < want && tries < 4; tries++) {
            try {
                String search = MET + "/v1.1/search?hasImages=true&isPublicDomain=true&departmentId=11&q="
                        + TOPICS[random.nextInt(TOPICS.length)];
                int total = new JSONObject(Feed.http(search + "&limit=1&offset=0")).optInt("total");
                if (total <= 0) continue;
                int offset = total > 20 ? random.nextInt(total - 20) : 0;
                JSONArray ids = new JSONObject(Feed.http(search + "&limit=20&offset=" + offset))
                        .optJSONArray("objectIDs");
                for (int i = 0; ids != null && i < ids.length() && got < want; i++) {
                    int id = ids.optInt(i);
                    File to = new File(dir, "kunst-" + id + ".jpg");
                    if (id <= 0 || to.exists()) continue;
                    JSONObject o = new JSONObject(Feed.http(MET + "/v1/objects/" + id));
                    String url = o.optString("primaryImage");
                    if (url.isEmpty() || !wide(o.optJSONArray("measurements"))) continue;
                    // the museum photographs the canvas with its edge and a black surround
                    if (save(url, to, 4)) got++;
                }
            } catch (IOException | JSONException e) {
                Log.w("dumbtv", "gallery art: " + e);
            }
        }
        return got;
    }

    /** True when the painting itself (the last measurement listed) is wider than 5:4. */
    private static boolean wide(JSONArray measurements) {
        double w = 0, h = 0;
        for (int i = 0; measurements != null && i < measurements.length(); i++) {
            JSONObject m = measurements.optJSONObject(i);
            JSONObject box = m == null ? null : m.optJSONObject("elementMeasurements");
            if (box == null || !box.has("Width") || !box.has("Height")) continue;
            w = box.optDouble("Width", 0);
            h = box.optDouble("Height", 0);
        }
        return h > 0 && w > h * 1.25;
    }

    // ---------------------------------------------------------------- files

    /**
     * Downloads {@code url} to {@code to}. Large originals are stored scaled down, and
     * {@code trim} percent is cut off every side first. False, and no file, when anything fails.
     */
    private boolean save(String url, File to, int trim) {
        File tmp = new File(to.getPath() + ".part");
        try {
            download(url, tmp);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(tmp.getPath(), o);
            if (o.outWidth < 1200 || o.outHeight < 700) return false;
            if (trim == 0 && o.outWidth <= 3840 && tmp.length() <= 3_000_000) {
                return tmp.renameTo(to);
            }
            // halve until it would drop under full HD: between 1920 and 3839 pixels wide
            o.inSampleSize = 1;
            while (o.outWidth / (o.inSampleSize * 2) >= 1920) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false;
            Bitmap full = BitmapFactory.decodeFile(tmp.getPath(), o);
            if (full == null) return false;
            int dx = full.getWidth() * trim / 100, dy = full.getHeight() * trim / 100;
            Bitmap cut = trim == 0 ? full
                    : Bitmap.createBitmap(full, dx, dy, full.getWidth() - 2 * dx, full.getHeight() - 2 * dy);
            OutputStream out = new FileOutputStream(to);
            try {
                cut.compress(Bitmap.CompressFormat.JPEG, 90, out);
            } finally {
                out.close();
                if (cut != full) cut.recycle();
                full.recycle();
            }
            return to.length() > 0;
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            Log.w("dumbtv", "gallery " + to.getName() + ": " + e);
            to.delete();
            return false;
        } finally {
            tmp.delete();
        }
    }

    private static void download(String url, File to) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "tcl-home");
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            InputStream in = c.getInputStream();
            OutputStream out = new FileOutputStream(to);
            try {
                byte[] buf = new byte[16384];
                long total = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    total += n;
                    if (total > 30_000_000) throw new IOException("picture too large");
                }
            } finally {
                out.close();
            }
        } finally {
            c.disconnect();
        }
    }

    /** Deletes the oldest fetched pictures beyond {@link #KEEP}. */
    private static void prune(Config cfg) {
        List<File> mine = new ArrayList<>();
        for (File f : cfg.wallpapers()) {
            for (String kind : KINDS) {
                if (f.getName().startsWith(kind)) mine.add(f);
            }
        }
        Collections.sort(mine, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        for (int i = 0; i < mine.size() - KEEP; i++) {
            if (!mine.get(i).delete()) Log.w("dumbtv", "gallery: could not delete " + mine.get(i));
        }
    }
}
