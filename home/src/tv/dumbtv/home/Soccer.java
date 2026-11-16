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

import java.io.IOException;
import java.text.Normalizer;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Football for the lock screen, from ESPN's public scoreboard (site.api.espn.com). It needs no
 * account, but it is not a documented service: if its answers change shape, the card simply
 * stays empty until this class is adapted.
 *
 * Two things are kept. For every favourite club its next fixtures in any competition, so the
 * card can announce a match days ahead. And for every league of interest today's matches with
 * live scores, refreshed every minute while one of them is running.
 */
final class Soccer {
    /** ESPN league slugs offered in Options; home.conf may name any other one. */
    static final String[][] LEAGUES = {
        {"ger.1", "Bundesliga"}, {"ger.2", "2. Bundesliga"}, {"ger.dfb_pokal", "DFB-Pokal"},
        {"eng.1", "Premier League"}, {"esp.1", "LaLiga"}, {"tur.1", "Süper Lig"},
        {"ita.1", "Serie A"}, {"fra.1", "Ligue 1"}, {"uefa.champions", "Champions League"},
        {"uefa.europa", "Europa League"}, {"uefa.europa.conf", "Conference League"},
    };
    static final String KEY_LEAGUES = "soccer.leagues";
    static final String KEY_CLUBS = "soccer.clubs";
    /** Stored instead of an empty list, which Config would drop and so turn back into the default. */
    static final String NONE = "none";
    private static final String DEFAULT_LEAGUES = "ger.1,ger.2,eng.1,esp.1,tur.1,uefa.champions";
    /** Arminia Bielefeld, Borussia Dortmund, Bayern München, Real Madrid, Galatasaray, Bournemouth. */
    private static final String DEFAULT_CLUBS = "2506,124,132,86,432,349";

    /**
     * Rivalries worth a mark, as two fragments of the clubs' names (lower case, no accents).
     * A match is a derby when each side's full name contains one of the two.
     */
    private static final String[][] DERBIES = {
        {"arsenal", "tottenham"}, {"liverpool", "everton"}, {"manchester united", "manchester city"},
        {"liverpool", "manchester united"}, {"arsenal", "chelsea"}, {"chelsea", "tottenham"},
        {"arsenal", "manchester united"}, {"newcastle", "sunderland"}, {"west ham", "tottenham"},
        {"crystal palace", "brighton"}, {"leeds", "manchester united"},
        {"dortmund", "schalke"}, {"bayern", "dortmund"}, {"cologne", "gladbach"}, {"koln", "gladbach"},
        {"hamburg", "bremen"}, {"hamburg", "pauli"}, {"hertha", "union berlin"},
        {"bielefeld", "paderborn"}, {"bielefeld", "munster"}, {"nurnberg", "furth"},
        {"nuremberg", "furth"}, {"hannover", "braunschweig"}, {"stuttgart", "karlsruhe"},
        {"real madrid", "barcelona"}, {"real madrid", "atletico"}, {"barcelona", "espanyol"},
        {"sevilla", "betis"}, {"athletic club", "real sociedad"},
        {"galatasaray", "fenerbahce"}, {"galatasaray", "besiktas"}, {"fenerbahce", "besiktas"},
        {"trabzonspor", "galatasaray"}, {"trabzonspor", "fenerbahce"}, {"trabzonspor", "besiktas"},
        {"internazionale", "ac milan"}, {"juventus", "internazionale"}, {"roma", "lazio"},
        {"juventus", "torino"},
    };

    private static final long MINUTE = 60_000L, HOUR = 60 * MINUTE;
    private static final long FIXTURES_EVERY = 30 * MINUTE, DAY_EVERY = 10 * MINUTE, LIVE_EVERY = MINUTE;
    private static final long NEXT_EVERY = 60 * MINUTE;
    /** How far ahead a favourite's next match is announced. */
    private static final long AHEAD = 8 * 24 * HOUR;
    private static final long AROUND = 3 * HOUR;
    private static final int TOP_LINES = 6, PAGE_LINES = 6, FIXTURES_KEPT = 3;

    static final class Match {
        String id = "", league = "", state = "pre", clock = "";
        long kickoff;
        int home, away, goalsHome, goalsAway;
        String homeName = "", awayName = "";
        boolean derby;

        boolean live() {
            return "in".equals(state);
        }

        boolean over() {
            return "post".equals(state);
        }

        boolean has(List<String> clubs) {
            return clubs.contains(String.valueOf(home)) || clubs.contains(String.valueOf(away));
        }
    }

    /**
     * One screenful of the football card: the favourite clubs' matches, or one league's matches
     * of a day (a part of them when they do not fit). {@code withDay} asks for the day in front
     * of each kick-off time, for a page whose matches are on different days.
     */
    static final class Page {
        final String title;
        final List<Match> matches;
        final boolean withDay;

        Page(String title, List<Match> matches, boolean withDay) {
            this.title = title;
            this.matches = matches;
            this.withDay = withDay;
        }
    }

    private static final class Day {
        final String date;
        final List<Match> matches;

        Day(String date, List<Match> matches) {
            this.date = date;
            this.matches = matches;
        }
    }

    private static Soccer instance;

    private final Context app;
    private final SharedPreferences store;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    /** When each fetch was last started, in elapsed time. Only touched on the main thread. */
    private final Map<String, Long> tried = new HashMap<>();
    /** League slug to its matches of one day. */
    private final Map<String, Day> days = new ConcurrentHashMap<>();
    /** League slug to its next day of matches, shown while there is none today. */
    private final Map<String, Day> upcoming = new ConcurrentHashMap<>();
    /** Club id to its next fixtures. */
    private final Map<String, List<Match>> fixtures = new ConcurrentHashMap<>();

    static synchronized Soccer get(Context c) {
        if (instance == null) instance = new Soccer(c.getApplicationContext());
        return instance;
    }

    private Soccer(Context c) {
        app = c;
        store = c.getSharedPreferences("soccer", Context.MODE_PRIVATE);
        for (Map.Entry<String, ?> e : store.getAll().entrySet()) {
            String key = e.getKey();
            try {
                if (key.startsWith("day.")) {
                    JSONObject o = new JSONObject(String.valueOf(e.getValue()));
                    days.put(key.substring(4), new Day(o.getString("d"), decode(o.getJSONArray("m"))));
                } else if (key.startsWith("next.")) {
                    JSONObject o = new JSONObject(String.valueOf(e.getValue()));
                    upcoming.put(key.substring(5), new Day(o.getString("d"), decode(o.getJSONArray("m"))));
                } else if (key.startsWith("club.")) {
                    fixtures.put(key.substring(5), decode(new JSONArray(String.valueOf(e.getValue()))));
                }
            } catch (JSONException ignored) {
                // fetched again
            }
        }
    }

    // ---------------------------------------------------------------- settings

    static List<String> leagues(Config cfg) {
        return csv(cfg.get(KEY_LEAGUES, DEFAULT_LEAGUES));
    }

    static List<String> clubs(Config cfg) {
        return csv(cfg.get(KEY_CLUBS, DEFAULT_CLUBS));
    }

    private static List<String> csv(String value) {
        List<String> out = new ArrayList<>();
        for (String s : value.split(",")) {
            String t = s.trim();
            if (!t.isEmpty() && !NONE.equals(t)) out.add(t);
        }
        return out;
    }

    private static String leagueName(String slug) {
        for (String[] l : LEAGUES) {
            if (l[0].equals(slug)) return l[1];
        }
        return slug;
    }

    // ---------------------------------------------------------------- what the lock screen shows

    /**
     * The first page: matches of the favourite clubs, today's and the next ones, plus any
     * derby today or coming up. Running matches first, then by kick-off.
     */
    List<Match> top(Config cfg) {
        List<Match> out = new ArrayList<>();
        if ("off".equals(cfg.get(Config.FOOTBALL))) return out;
        long now = System.currentTimeMillis();
        String date = date(now);
        List<String> clubs = clubs(cfg);
        Map<String, Match> byId = new LinkedHashMap<>();
        for (Day d : days.values()) {
            if (!date.equals(d.date)) continue;
            for (Match m : d.matches) {
                if (m.has(clubs) || m.derby) byId.put(m.id, m);
            }
        }
        for (String club : clubs) {
            List<Match> next = fixtures.get(club);
            if (next == null) continue;
            for (Match m : next) {
                // A fixture is only the announcement; once today's list has the match, that one counts.
                if (byId.containsKey(m.id)) continue;
                if (m.kickoff < now - AROUND || m.kickoff - now > AHEAD) continue;
                byId.put(m.id, m);
            }
        }
        for (Day d : upcoming.values()) {
            for (Match m : d.matches) {
                if (!m.derby || byId.containsKey(m.id)) continue;
                if (m.kickoff < now - AROUND || m.kickoff - now > AHEAD) continue;
                byId.put(m.id, m);
            }
        }
        out.addAll(byId.values());
        Collections.sort(out, new Comparator<Match>() {
            @Override
            public int compare(Match a, Match b) {
                int ra = rank(a), rb = rank(b);
                if (ra != rb) return ra - rb;
                return ra == 2 ? Long.compare(b.kickoff, a.kickoff) : Long.compare(a.kickoff, b.kickoff);
            }
        });
        return out.size() > TOP_LINES ? new ArrayList<>(out.subList(0, TOP_LINES)) : out;
    }

    private static int rank(Match m) {
        return m.live() ? 0 : m.over() ? 2 : 1;
    }

    /**
     * What the card leafs through: first the favourite clubs, then one page per chosen league
     * with today's matches or, on a day without any, the league's next day of matches.
     */
    List<Page> pages(Config cfg) {
        List<Page> out = new ArrayList<>();
        String mode = cfg.get(Config.FOOTBALL);
        if ("off".equals(mode)) return out;
        List<Match> mine = top(cfg);
        if (!mine.isEmpty()) out.add(new Page("Meine Vereine", mine, true));
        if (!"all".equals(mode)) return out;
        long now = System.currentTimeMillis();
        String date = date(now);
        for (String league : leagues(cfg)) {
            Day d = days.get(league);
            String when = "Heute";
            if (d == null || !date.equals(d.date) || d.matches.isEmpty()) {
                d = upcoming.get(league);
                if (d == null || d.matches.isEmpty()) continue;
                long first = Long.MAX_VALUE;
                for (Match m : d.matches) first = Math.min(first, m.kickoff);
                // yesterday's results, or a matchday too far away to care about yet
                if (first < now - AROUND || first - now > AHEAD) continue;
                when = date.equals(date(first)) ? "Heute"
                        : new SimpleDateFormat("EE dd.MM.", Locale.GERMAN).format(new Date(first));
            }
            List<Match> all = new ArrayList<>(d.matches);
            Collections.sort(all, new Comparator<Match>() {
                @Override
                public int compare(Match a, Match b) {
                    return Long.compare(a.kickoff, b.kickoff);
                }
            });
            int parts = (all.size() + PAGE_LINES - 1) / PAGE_LINES;
            for (int p = 0; p < parts; p++) {
                out.add(new Page(leagueName(league) + " \u00B7 " + when, new ArrayList<>(
                        all.subList(p * PAGE_LINES, Math.min(all.size(), (p + 1) * PAGE_LINES))), false));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- fetching

    /** Starts whatever is due. Cheap; the lock screen calls it every few seconds. */
    void refresh(Config cfg) {
        String mode = cfg.get(Config.FOOTBALL);
        if ("off".equals(mode) || !online()) return;
        long now = System.currentTimeMillis();
        final String date = date(now);
        List<String> clubs = clubs(cfg);

        for (final String club : clubs) {
            if (!due("club." + club, FIXTURES_EVERY)) continue;
            io.execute(new Runnable() {
                @Override
                public void run() {
                    fetchFixtures(club);
                }
            });
        }

        if ("all".equals(mode)) {
            for (final String league : leagues(cfg)) {
                if (!due("next." + league, NEXT_EVERY)) continue;
                io.execute(new Runnable() {
                    @Override
                    public void run() {
                        fetchNext(league);
                    }
                });
            }
        }

        // Today's list is wanted for the chosen leagues and for any competition a favourite plays in today.
        Set<String> wanted = new LinkedHashSet<>();
        if ("all".equals(mode)) wanted.addAll(leagues(cfg));
        for (String club : clubs) {
            List<Match> next = fixtures.get(club);
            if (next == null) continue;
            for (Match m : next) {
                boolean near = date.equals(date(m.kickoff)) || Math.abs(m.kickoff - now) < AROUND;
                if (near && !m.league.isEmpty()) wanted.add(m.league);
            }
        }
        for (Map.Entry<String, Day> e : days.entrySet()) {
            if (!date.equals(e.getValue().date)) continue;
            for (Match m : e.getValue().matches) {
                if (m.has(clubs)) wanted.add(e.getKey());
            }
        }

        for (final String league : wanted) {
            boolean hot = false;
            Day d = days.get(league);
            if (d != null && date.equals(d.date)) {
                for (Match m : d.matches) {
                    if (m.live() || !m.over() && now >= m.kickoff - 10 * MINUTE && now < m.kickoff + AROUND) {
                        hot = true;
                    }
                }
            }
            if (!due("day." + league + date, hot ? LIVE_EVERY : DAY_EVERY)) continue;
            io.execute(new Runnable() {
                @Override
                public void run() {
                    fetchDay(league, date);
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

    @SuppressWarnings("deprecation")
    private boolean online() {
        ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo active = cm == null ? null : cm.getActiveNetworkInfo();
        return active != null && active.isConnected();
    }

    private static final String BASE = "https://site.api.espn.com/apis/site/v2/sports/soccer/";

    private void fetchFixtures(String club) {
        try {
            JSONArray events = new JSONObject(Feed.http(BASE + "all/teams/" + Feed.encode(club)
                    + "/schedule?fixture=true")).optJSONArray("events");
            long now = System.currentTimeMillis();
            List<Match> next = new ArrayList<>();
            for (int i = 0; events != null && i < events.length() && next.size() < FIXTURES_KEPT; i++) {
                JSONObject e = events.getJSONObject(i);
                JSONObject league = e.optJSONObject("league");
                Match m = parse(e, league == null ? "" : league.optString("slug"));
                if (m.kickoff >= now - AROUND) next.add(m);
            }
            fixtures.put(club, next);
            store.edit().putString("club." + club, encode(next).toString()).apply();
        } catch (IOException | JSONException | ParseException | RuntimeException e) {
            Log.w("dumbtv", "fixtures " + club + ": " + e);
        }
    }

    private void fetchDay(String league, String date) {
        try {
            JSONArray events = new JSONObject(Feed.http(BASE + Feed.encode(league)
                    + "/scoreboard?dates=" + date)).optJSONArray("events");
            List<Match> all = new ArrayList<>();
            for (int i = 0; events != null && i < events.length(); i++) {
                all.add(parse(events.getJSONObject(i), league));
            }
            days.put(league, new Day(date, all));
            JSONObject o = new JSONObject();
            o.put("d", date);
            o.put("m", encode(all));
            store.edit().putString("day." + league, o.toString()).apply();
        } catch (IOException | JSONException | ParseException | RuntimeException e) {
            Log.w("dumbtv", "football " + league + ": " + e);
        }
    }

    /** Without a date the scoreboard answers with the league's current or next day of matches. */
    private void fetchNext(String league) {
        try {
            JSONArray events = new JSONObject(Feed.http(BASE + Feed.encode(league) + "/scoreboard"))
                    .optJSONArray("events");
            List<Match> all = new ArrayList<>();
            for (int i = 0; events != null && i < events.length(); i++) {
                all.add(parse(events.getJSONObject(i), league));
            }
            String date = all.isEmpty() ? "" : date(all.get(0).kickoff);
            upcoming.put(league, new Day(date, all));
            JSONObject o = new JSONObject();
            o.put("d", date);
            o.put("m", encode(all));
            store.edit().putString("next." + league, o.toString()).apply();
        } catch (IOException | JSONException | ParseException | RuntimeException e) {
            Log.w("dumbtv", "football next " + league + ": " + e);
        }
    }

    /** One event, as the scoreboard and the team schedule both list it. */
    private static Match parse(JSONObject e, String league) throws JSONException, ParseException {
        Match m = new Match();
        m.id = e.optString("id");
        m.league = league;
        // "2026-11-21T15:00Z"
        SimpleDateFormat utc = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
        utc.setTimeZone(TimeZone.getTimeZone("UTC"));
        m.kickoff = utc.parse(e.getString("date")).getTime();
        JSONObject game = e.getJSONArray("competitions").getJSONObject(0);
        JSONObject status = e.optJSONObject("status");
        if (status == null) status = game.optJSONObject("status");
        JSONObject type = status == null ? null : status.optJSONObject("type");
        if (type != null) {
            m.state = type.optString("state", "pre");
            m.clock = type.optString("name").contains("HALFTIME") ? "HZ" : status.optString("displayClock");
        }
        JSONArray sides = game.getJSONArray("competitors");
        String homeFull = "", awayFull = "";
        for (int i = 0; i < sides.length(); i++) {
            JSONObject side = sides.getJSONObject(i);
            JSONObject team = side.getJSONObject("team");
            String full = team.optString("displayName");
            String name = team.optString("shortDisplayName");
            if (name.isEmpty()) name = full;
            int goals = goals(side.opt("score"));
            boolean home = sides.length() != 2 ? i == 0 : "home".equals(side.optString("homeAway", i == 0 ? "home" : "away"));
            if (home) {
                m.home = team.optInt("id");
                m.homeName = german(name);
                m.goalsHome = goals;
                homeFull = full;
            } else {
                m.away = team.optInt("id");
                m.awayName = german(name);
                m.goalsAway = goals;
                awayFull = full;
            }
        }
        m.derby = derby(homeFull, awayFull);
        return m;
    }

    /** The scoreboard gives "2", the team schedule {"value": 2.0}, a fixture nothing. */
    private static int goals(Object score) {
        if (score instanceof JSONObject) return (int) ((JSONObject) score).optDouble("value", 0);
        if (score == null) return 0;
        try {
            return Integer.parseInt(String.valueOf(score).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** ESPN writes club names the English way; the few that look wrong on a German TV are put right. */
    private static String german(String name) {
        return name.replace("Cologne", "Köln").replace("Munich", "München")
                .replace("Nuremberg", "Nürnberg").replace("Furth", "Fürth")
                .replace("Dusseldorf", "Düsseldorf").replace("Munster", "Münster")
                .replace("Monchengladbach", "Mönchengladbach");
    }

    private static boolean derby(String a, String b) {
        a = plain(a);
        b = plain(b);
        for (String[] d : DERBIES) {
            if (a.contains(d[0]) && b.contains(d[1]) || a.contains(d[1]) && b.contains(d[0])) return true;
        }
        return false;
    }

    private static String plain(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    private static String date(long ms) {
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date(ms));
    }

    private static JSONArray encode(List<Match> list) throws JSONException {
        JSONArray out = new JSONArray();
        for (Match m : list) {
            JSONObject o = new JSONObject();
            o.put("i", m.id);
            o.put("l", m.league);
            o.put("s", m.state);
            o.put("c", m.clock);
            o.put("k", m.kickoff);
            o.put("h", m.home);
            o.put("a", m.away);
            o.put("gh", m.goalsHome);
            o.put("ga", m.goalsAway);
            o.put("hn", m.homeName);
            o.put("an", m.awayName);
            o.put("d", m.derby);
            out.put(o);
        }
        return out;
    }

    private static List<Match> decode(JSONArray in) throws JSONException {
        List<Match> out = new ArrayList<>();
        for (int i = 0; i < in.length(); i++) {
            JSONObject o = in.getJSONObject(i);
            Match m = new Match();
            m.id = o.getString("i");
            m.league = o.optString("l");
            m.state = o.optString("s", "pre");
            m.clock = o.optString("c");
            m.kickoff = o.getLong("k");
            m.home = o.optInt("h");
            m.away = o.optInt("a");
            m.goalsHome = o.optInt("gh");
            m.goalsAway = o.optInt("ga");
            m.homeName = o.optString("hn");
            m.awayName = o.optString("an");
            m.derby = o.optBoolean("d");
            out.add(m);
        }
        return out;
    }

    /**
     * Every club of the given leagues as {id, name}, sorted by name. Blocks on the network, so
     * Options calls it from its own thread. Empty when nothing could be loaded.
     */
    static List<String[]> teams(List<String> leagues) {
        Map<String, String> byName = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String league : leagues) {
            try {
                JSONArray teams = new JSONObject(Feed.http(BASE + Feed.encode(league) + "/teams"))
                        .getJSONArray("sports").getJSONObject(0)
                        .getJSONArray("leagues").getJSONObject(0).getJSONArray("teams");
                for (int i = 0; i < teams.length(); i++) {
                    JSONObject t = teams.getJSONObject(i).getJSONObject("team");
                    String n = german(t.optString("displayName").trim());
                    if (!n.isEmpty()) byName.put(n, String.valueOf(t.optInt("id")));
                }
            } catch (IOException | JSONException | RuntimeException e) {
                Log.w("dumbtv", "teams " + league + ": " + e);
            }
        }
        List<String[]> out = new ArrayList<>();
        for (Map.Entry<String, String> e : byName.entrySet()) out.add(new String[] {e.getValue(), e.getKey()});
        return out;
    }
}
