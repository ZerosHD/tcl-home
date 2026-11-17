package tv.dumbtv.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Weather and prayer times for the lock screen, from two free services that need no account
 * and no key: open-meteo.com and aladhan.com. Both get the coordinates of the town picked in
 * Options, nothing else. Every answer is kept, so the lock screen has something to show at
 * once and while the TV is offline. Football lives in {@link Soccer}.
 */
final class Feed {
    static final String KEY_PLACE = "place";
    /** Name|latitude|longitude, the form the place is stored in. */
    static final String[] PLACES = {
        "Aachen|50.78|6.08", "Augsburg|48.37|10.90", "Berlin|52.52|13.40", "Bielefeld|52.02|8.53",
        "Bochum|51.48|7.22", "Bonn|50.73|7.10", "Braunschweig|52.27|10.52", "Bremen|53.08|8.80",
        "Bremerhaven|53.55|8.58", "Chemnitz|50.83|12.92", "Darmstadt|49.87|8.65",
        "Dortmund|51.51|7.47", "Dresden|51.05|13.74", "Duisburg|51.43|6.76",
        "Düsseldorf|51.23|6.78", "Erfurt|50.98|11.03", "Essen|51.46|7.01",
        "Frankfurt am Main|50.11|8.68", "Freiburg|47.99|7.85", "Gelsenkirchen|51.52|7.10",
        "Göttingen|51.53|9.93", "Hagen|51.36|7.47", "Halle (Saale)|51.48|11.97",
        "Hamburg|53.55|10.00", "Hamm|51.68|7.82", "Hannover|52.37|9.74", "Heidelberg|49.40|8.69",
        "Heilbronn|49.14|9.22", "Herne|51.54|7.22", "Ingolstadt|48.77|11.42",
        "Karlsruhe|49.01|8.40", "Kassel|51.31|9.48", "Kiel|54.32|10.14", "Koblenz|50.36|7.59",
        "Köln|50.94|6.96", "Krefeld|51.34|6.59", "Leipzig|51.34|12.37", "Leverkusen|51.03|6.98",
        "Lübeck|53.87|10.69", "Ludwigshafen|49.48|8.44", "Magdeburg|52.13|11.63",
        "Mainz|50.00|8.27", "Mannheim|49.49|8.47", "Mönchengladbach|51.19|6.44",
        "Mülheim an der Ruhr|51.43|6.88", "München|48.14|11.58", "Münster|51.96|7.63",
        "Neuss|51.20|6.69", "Nürnberg|49.45|11.08", "Oberhausen|51.47|6.85",
        "Offenbach|50.10|8.76", "Oldenburg|53.14|8.21", "Osnabrück|52.28|8.05",
        "Paderborn|51.72|8.75", "Pforzheim|48.89|8.70", "Potsdam|52.40|13.06",
        "Regensburg|49.02|12.10", "Reutlingen|48.49|9.21", "Rostock|54.09|12.14",
        "Saarbrücken|49.24|6.99", "Solingen|51.17|7.08", "Stuttgart|48.78|9.18",
        "Trier|49.75|6.64", "Ulm|48.40|9.99", "Wiesbaden|50.08|8.24", "Wolfsburg|52.42|10.79",
        "Wuppertal|51.26|7.15", "Würzburg|49.79|9.93",
        "Graz|47.07|15.44", "Innsbruck|47.27|11.39", "Linz|48.31|14.29", "Salzburg|47.80|13.04",
        "Wien|48.21|16.37", "Basel|47.56|7.59", "Bern|46.95|7.45", "Zürich|47.38|8.54",
    };

    static final String[] PRAYER_NAMES = {"Fajr", "Shuruq", "Dhuhr", "Asr", "Maghrib", "Isha"};
    private static final String[] PRAYER_KEYS = {"Fajr", "Sunrise", "Dhuhr", "Asr", "Maghrib", "Isha"};

    private static final long MINUTE = 60_000L, HOUR = 60 * MINUTE;
    private static final long WEATHER_EVERY = 30 * MINUTE, WEATHER_STALE = 3 * HOUR;
    private static final long RETRY = 2 * MINUTE;
    private static Feed instance;

    private final Context app;
    private final SharedPreferences store;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    /** When each fetch was last started, in elapsed time, so a failure is not retried at once. */
    private final Map<String, Long> tried = new HashMap<>();

    /** One reading: now, and today's range. Immutable, so the lock screen can read it from its own thread. */
    static final class Weather {
        final String place;
        final int temperature, code, high, low, rain;
        final boolean day;
        final long at;
        /** The coming days, tomorrow first: date (yyyy-MM-dd), weather code, high, low, chance of rain. */
        String[] dates = new String[0];
        int[] codes = new int[0], highs = new int[0], lows = new int[0], rains = new int[0];

        Weather(String place, int temperature, int code, boolean day, int high, int low, int rain, long at) {
            this.place = place;
            this.temperature = temperature;
            this.code = code;
            this.day = day;
            this.high = high;
            this.low = low;
            this.rain = rain;
            this.at = at;
        }

        String sky() {
            return Feed.sky(code);
        }

        /** The coming days as one cache line: date,code,high,low,rain;... */
        String daysLine() {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < dates.length; i++) {
                if (i > 0) b.append(';');
                b.append(dates[i]).append(',').append(codes[i]).append(',').append(highs[i])
                        .append(',').append(lows[i]).append(',').append(rains[i]);
            }
            return b.toString();
        }

        void readDays(String line) {
            String[] parts = line.isEmpty() ? new String[0] : line.split(";");
            dates = new String[parts.length];
            codes = new int[parts.length];
            highs = new int[parts.length];
            lows = new int[parts.length];
            rains = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                String[] p = parts[i].split(",");
                dates[i] = p[0];
                codes[i] = Integer.parseInt(p[1]);
                highs[i] = Integer.parseInt(p[2]);
                lows[i] = Integer.parseInt(p[3]);
                rains[i] = Integer.parseInt(p[4]);
            }
        }
    }

    private volatile Weather weather;
    private volatile String prayerKey = "";
    private volatile String[] prayers;

    static synchronized Feed get(Context c) {
        if (instance == null) instance = new Feed(c.getApplicationContext());
        return instance;
    }

    private Feed(Context c) {
        app = c;
        store = c.getSharedPreferences("feed", Context.MODE_PRIVATE);
        try {
            String[] w = store.getString("weather2", "").split("\n");
            if (w.length >= 8) {
                Weather cached = new Weather(w[0], Integer.parseInt(w[1]), Integer.parseInt(w[2]), "1".equals(w[3]),
                        Integer.parseInt(w[4]), Integer.parseInt(w[5]), Integer.parseInt(w[6]), Long.parseLong(w[7]));
                if (w.length >= 9) cached.readDays(w[8]);
                weather = cached;
            }
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException ignored) {
            // an unreadable cache is simply fetched again
        }
        String[] p = store.getString("prayer", "").split("\n");
        if (p.length == 2 && p[1].split(",").length == PRAYER_KEYS.length) {
            prayers = p[1].split(",");
            prayerKey = p[0];
        }
    }

    // ---------------------------------------------------------------- settings

    /** {name, latitude, longitude}, or null while no town is picked. */
    static String[] place(Config cfg) {
        String[] p = cfg.get(KEY_PLACE, "").split("\\|");
        if (p.length != 3) return null;
        try {
            Double.parseDouble(p[1]);
            Double.parseDouble(p[2]);
        } catch (NumberFormatException e) {
            return null;
        }
        return p;
    }

    // ---------------------------------------------------------------- what the lock screen shows

    /** The weather for the picked town, or null when it is off, has no town or is too old to trust. */
    Weather weather(Config cfg) {
        Weather w = weather;
        if (w == null || place(cfg) == null || !"on".equals(cfg.get(Config.WEATHER))) return null;
        if (!cfg.get(KEY_PLACE, "").equals(w.place)) return null;
        if (System.currentTimeMillis() - w.at > WEATHER_STALE) return null;
        return w;
    }

    /** Today's six times as HH:mm in the order of {@link #PRAYER_NAMES}, or null. */
    String[] prayers(Config cfg) {
        String key = prayerKey(cfg);
        return key != null && key.equals(prayerKey) ? prayers : null;
    }

    // ---------------------------------------------------------------- fetching

    /** Starts whatever is due. Cheap; the lock screen calls it every few seconds. */
    void refresh(Config cfg) {
        if (!online()) return;
        long now = System.currentTimeMillis();
        final String[] place = place(cfg);
        final String placeKey = cfg.get(KEY_PLACE, "");

        Weather had = weather;
        if (place != null && "on".equals(cfg.get(Config.WEATHER))
                && (had == null || !placeKey.equals(had.place) || had.dates.length == 0 || now - had.at > WEATHER_EVERY)
                && due("weather", RETRY)) {
            io.execute(new Runnable() {
                @Override
                public void run() {
                    fetchWeather(place, placeKey);
                }
            });
        }

        final String key = prayerKey(cfg);
        if (key != null && !key.equals(prayerKey) && due("prayer", RETRY)) {
            final String method = cfg.get(Config.PRAYER);
            io.execute(new Runnable() {
                @Override
                public void run() {
                    fetchPrayers(place, method, key);
                }
            });
        }

    }

    /** True, and remembered, when this fetch was not started within the last {@code every} ms. */
    private boolean due(String what, long every) {
        long now = SystemClock.elapsedRealtime();
        Long last = tried.get(what);
        if (last != null && now - last < every) return false;
        tried.put(what, now);
        return true;
    }

    private String prayerKey(Config cfg) {
        String method = cfg.get(Config.PRAYER);
        if ("off".equals(method) || place(cfg) == null) return null;
        String day = new SimpleDateFormat("dd-MM-yyyy", Locale.US).format(new Date());
        return day + "|" + cfg.get(KEY_PLACE, "") + "|" + method;
    }

    @SuppressWarnings("deprecation")
    private boolean online() {
        ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo active = cm == null ? null : cm.getActiveNetworkInfo();
        return active != null && active.isConnected();
    }

    private void fetchWeather(String[] place, String placeKey) {
        try {
            JSONObject all = new JSONObject(http("https://api.open-meteo.com/v1/forecast?latitude="
                    + place[1] + "&longitude=" + place[2]
                    + "&current=temperature_2m,weather_code,is_day"
                    + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                    + "&forecast_days=7&timezone=auto"));
            JSONObject now = all.getJSONObject("current"), day = all.getJSONObject("daily");
            Weather w = new Weather(placeKey,
                    (int) Math.round(now.getDouble("temperature_2m")),
                    now.getInt("weather_code"),
                    now.optInt("is_day", 1) == 1,
                    (int) Math.round(day.getJSONArray("temperature_2m_max").getDouble(0)),
                    (int) Math.round(day.getJSONArray("temperature_2m_min").getDouble(0)),
                    // the chance of rain is missing for some places; -1 hides it
                    (int) Math.round(day.getJSONArray("precipitation_probability_max").optDouble(0, -1)),
                    System.currentTimeMillis());
            JSONArray dates = day.getJSONArray("time"), codes = day.getJSONArray("weather_code"),
                    highs = day.getJSONArray("temperature_2m_max"), lows = day.getJSONArray("temperature_2m_min"),
                    rains = day.getJSONArray("precipitation_probability_max");
            int n = Math.max(0, dates.length() - 1);
            w.dates = new String[n];
            w.codes = new int[n];
            w.highs = new int[n];
            w.lows = new int[n];
            w.rains = new int[n];
            for (int i = 0; i < n; i++) {
                w.dates[i] = dates.getString(i + 1);
                w.codes[i] = codes.optInt(i + 1, 0);
                w.highs[i] = (int) Math.round(highs.optDouble(i + 1, 0));
                w.lows[i] = (int) Math.round(lows.optDouble(i + 1, 0));
                w.rains[i] = (int) Math.round(rains.optDouble(i + 1, -1));
            }
            weather = w;
            store.edit().putString("weather2", w.place + "\n" + w.temperature + "\n" + w.code + "\n"
                    + (w.day ? "1" : "0") + "\n" + w.high + "\n" + w.low + "\n" + w.rain + "\n" + w.at + "\n" + w.daysLine()).apply();
        } catch (IOException | JSONException | RuntimeException e) {
            Log.w("dumbtv", "weather: " + e);
        }
    }

    private void fetchPrayers(String[] place, String method, String key) {
        try {
            String day = key.substring(0, key.indexOf('|'));
            JSONObject t = new JSONObject(http("https://api.aladhan.com/v1/timings/" + day
                    + "?latitude=" + place[1] + "&longitude=" + place[2] + "&method=" + method))
                    .getJSONObject("data").getJSONObject("timings");
            String[] times = new String[PRAYER_KEYS.length];
            StringBuilder joined = new StringBuilder();
            for (int i = 0; i < times.length; i++) {
                // "05:29", on some servers followed by " (CET)"
                times[i] = t.getString(PRAYER_KEYS[i]).trim().substring(0, 5);
                if (i > 0) joined.append(',');
                joined.append(times[i]);
            }
            prayers = times;
            prayerKey = key;
            store.edit().putString("prayer", key + "\n" + joined).apply();
        } catch (IOException | JSONException | RuntimeException e) {
            Log.w("dumbtv", "prayer times: " + e);
        }
    }

    static String encode(String part) throws IOException {
        return URLEncoder.encode(part, "UTF-8").replace("+", "%20");
    }

    static String http(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(12000);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "tcl-home");
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > 4_000_000) throw new IOException("answer too large");
            }
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    /** WMO weather codes, as open-meteo reports them. */
    static String sky(int code) {
        switch (code) {
            case 0: return "Klar";
            case 1: return "Überwiegend klar";
            case 2: return "Teils bewölkt";
            case 3: return "Bedeckt";
            case 45: case 48: return "Nebel";
            case 51: case 53: case 55: return "Nieselregen";
            case 56: case 57: return "Gefrierender Niesel";
            case 61: case 63: case 65: return "Regen";
            case 66: case 67: return "Gefrierender Regen";
            case 71: case 73: case 75: return "Schnee";
            case 77: return "Schneegriesel";
            case 80: case 81: case 82: return "Regenschauer";
            case 85: case 86: return "Schneeschauer";
            case 95: return "Gewitter";
            case 96: case 99: return "Gewitter mit Hagel";
            default: return "";
        }
    }
}
