package io.freemytv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The TV's small web server for your phone (and for your other LumaTV TV): type a search on the phone, finish setup
 * from the phone, and sync between TVs. Runs in the :home process, only on the local network address, only while
 * something needs it (a phone page in use in the last 15 minutes, the Your TVs page, or TV sync switched on).
 * <p>
 * Every phone request carries a random token that's new each time the server starts (it's in the QR code), and
 * pages are only answered for the TV's own address as Host. Sync requests (/sync/…) are checked by TvSync with the
 * secret the two TVs agreed on when paired. The TMDB key is never sent back, and nothing secret goes to the log.
 * <p>
 * For the home screen and search: {@link #typingUrl}, {@link #setupUrl}, {@link #setTypingListener}.
 */
final class PhoneLink {
    private PhoneLink() {}

    /** Called on the main thread as the phone types, and when a result is tapped there. */
    interface Listener {
        void onText(String text);
        /** `id` is "movie:<tmdb id>" or "tv:<tmdb id>"; {@link #result} gives the title. */
        void onOpen(String id);
        default void onSetup() {}
    }

    static final int PREFERRED_PORT = 48613;
    private static final long IDLE = 15 * 60_000L;
    private static final int MAX_BODY = 64 * 1024, MAX_SYNC_BODY = 4 * 1024 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static Context app;
    private static ServerSocket server;
    private static ExecutorService workers;
    private static String address = "", typingToken = "", setupToken = "";
    private static volatile long phoneAt;
    private static volatile Listener listener;
    private static final Set<String> holds = new HashSet<>();
    private static final Map<String, TitleSearch.Title> results = Collections.synchronizedMap(new LinkedHashMap<String, TitleSearch.Title>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, TitleSearch.Title> e) { return size() > 60; }
    });
    private static final Runnable idleCheck = new Runnable() {
        @Override public void run() {
            synchronized (PhoneLink.class) {
                if (server == null) return;
                if (holds.isEmpty() && System.currentTimeMillis() - phoneAt > IDLE) {
                    stop();
                    return;
                }
            }
            MAIN.postDelayed(this, 60_000);
        }
    };

    // MARK: - API

    /** Starts the server if needed; "http://<tv ip>:<port>/t?k=<token>", or null when the TV has no network address. */
    static String typingUrl(Context c) {
        if (!start(c)) return null;
        phoneAt = System.currentTimeMillis();
        return base() + "/t?k=" + typingToken;
    }

    /** The phone setup page (TMDB key, city, country, temperature unit), or null without a network address. */
    static String setupUrl(Context c) {
        if (!start(c)) return null;
        phoneAt = System.currentTimeMillis();
        return base() + "/s?k=" + setupToken;
    }

    static void setTypingListener(Listener l) {
        listener = l;
    }

    /** A title the phone was shown, by its id ("movie:603"), or null. */
    static TitleSearch.Title result(String id) {
        return results.get(id);
    }

    /** Keeps the server on for a reason ("sync", "pairing") until released. Returns false without a network. */
    static synchronized boolean hold(Context c, String reason) {
        holds.add(reason);
        return start(c);
    }

    static synchronized void release(String reason) {
        holds.remove(reason);
    }

    static synchronized int port() {
        return server == null ? 0 : server.getLocalPort();
    }

    static synchronized String address() {
        return address;
    }

    static synchronized boolean running() {
        return server != null && !server.isClosed();
    }

    // MARK: - Server

    private static String base() {
        return "http://" + address + ":" + server.getLocalPort();
    }

    private static synchronized boolean start(Context c) {
        app = c.getApplicationContext();
        String ip = lanAddress();
        if (ip == null) return false;
        if (server != null && !server.isClosed() && ip.equals(address)) return true;
        stop();
        try {
            ServerSocket s = new ServerSocket();
            s.setReuseAddress(true);
            try {
                s.bind(new InetSocketAddress(InetAddress.getByName(ip), PREFERRED_PORT), 16);
            } catch (IOException busy) {
                s.close();
                s = new ServerSocket();
                s.bind(new InetSocketAddress(InetAddress.getByName(ip), 0), 16);
            }
            server = s;
            address = ip;
            typingToken = token();
            setupToken = token();
            workers = new java.util.concurrent.ThreadPoolExecutor(3, 3, 0, java.util.concurrent.TimeUnit.MILLISECONDS,
                    new java.util.concurrent.ArrayBlockingQueue<Runnable>(8));
            final ServerSocket accepting = s;
            final ExecutorService pool = workers;
            Thread t = new Thread(() -> accept(accepting, pool), "lumatv-phone-link");
            t.setDaemon(true);
            t.start();
            MAIN.removeCallbacks(idleCheck);
            MAIN.postDelayed(idleCheck, 60_000);
            TvSync.serverStarted(app);
            AppLog.w("phone link on port " + s.getLocalPort());
            return true;
        } catch (IOException e) {
            AppLog.w("phone link: " + e.getMessage());
            return false;
        }
    }

    static synchronized void stop() {
        if (server == null) return;
        try { server.close(); } catch (IOException ignored) {}
        server = null;
        if (workers != null) workers.shutdownNow();
        workers = null;
        typingToken = setupToken = "";
        MAIN.removeCallbacks(idleCheck);
        if (app != null) TvSync.serverStopped(app);
    }

    private static void accept(ServerSocket s, ExecutorService pool) {
        while (!s.isClosed()) {
            try {
                Socket socket = s.accept();
                socket.setSoTimeout(15_000);
                try {
                    pool.execute(() -> serve(socket));
                } catch (RuntimeException rejected) {
                    socket.close();
                }
            } catch (IOException e) {
                if (s.isClosed()) return;
            }
        }
    }

    /** The TV's site-local IPv4 address (Wi-Fi or Ethernet), or null. */
    static String lanAddress() {
        try {
            String fallback = null;
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp() || n.isLoopback() || n.isVirtual()) continue;
                for (InetAddress a : Collections.list(n.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    String name = n.getName();
                    if (name.startsWith("wlan") || name.startsWith("eth")) return a.getHostAddress();
                    if (fallback == null) fallback = a.getHostAddress();
                }
            }
            return fallback;
        } catch (Exception e) {
            return null;
        }
    }

    private static String token() {
        byte[] b = new byte[18];
        RANDOM.nextBytes(b);
        return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    static boolean same(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    // MARK: - HTTP (one request per connection)

    static final class Request {
        String method = "", path = "", query = "";
        final Map<String, String> headers = new HashMap<>();
        byte[] body = new byte[0];

        String param(String name) {
            for (String part : query.split("&")) {
                int eq = part.indexOf('=');
                if (eq > 0 && part.substring(0, eq).equals(name)) {
                    try { return java.net.URLDecoder.decode(part.substring(eq + 1), "UTF-8"); } catch (Exception e) { return ""; }
                }
            }
            return "";
        }

        String header(String name) {
            String v = headers.get(name.toLowerCase(Locale.ROOT));
            return v == null ? "" : v;
        }
    }

    static final class Response {
        int status = 200;
        String type = "application/json; charset=utf-8";
        byte[] body = new byte[0];

        static Response json(int status, JSONObject o) {
            Response r = new Response();
            r.status = status;
            r.body = o.toString().getBytes(StandardCharsets.UTF_8);
            return r;
        }

        static Response bytes(int status, String type, byte[] body) {
            Response r = new Response();
            r.status = status;
            r.type = type;
            r.body = body;
            return r;
        }
    }

    private static void serve(Socket socket) {
        try (Socket s = socket; InputStream in = s.getInputStream(); OutputStream out = s.getOutputStream()) {
            Request req = read(in);
            Response res;
            if (req == null) res = Response.json(400, error("bad request"));
            else res = route(req);
            write(out, res);
        } catch (Exception e) {
            AppLog.w("phone link request failed: " + e.getClass().getSimpleName());
        }
    }

    private static Request read(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int state = 0;
        while (state < 4) {
            int b = in.read();
            if (b < 0) return null;
            head.write(b);
            if (head.size() > 8192) return null;
            state = (b == '\r' && (state == 0 || state == 2)) || (b == '\n' && (state == 1 || state == 3)) ? state + 1 : (b == '\r' ? 1 : 0);
        }
        String[] lines = new String(head.toByteArray(), StandardCharsets.ISO_8859_1).split("\r\n");
        String[] first = lines[0].split(" ");
        if (first.length < 3) return null;
        Request r = new Request();
        r.method = first[0];
        String target = first[1];
        int q = target.indexOf('?');
        r.path = q < 0 ? target : target.substring(0, q);
        r.query = q < 0 ? "" : target.substring(q + 1);
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) r.headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).trim());
        }
        int length = 0;
        try { length = Integer.parseInt(r.header("content-length").isEmpty() ? "0" : r.header("content-length")); }
        catch (NumberFormatException e) { return null; }
        int limit = r.path.startsWith("/sync/") ? MAX_SYNC_BODY : MAX_BODY;
        if (length < 0 || length > limit) return null;
        byte[] body = new byte[length];
        int read = 0;
        while (read < length) {
            int n = in.read(body, read, length - read);
            if (n < 0) return null;
            read += n;
        }
        r.body = body;
        return r;
    }

    private static void write(OutputStream out, Response r) throws IOException {
        String reason = r.status == 200 ? "OK" : r.status == 403 ? "Forbidden" : r.status == 404 ? "Not Found" : "Error";
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(r.status).append(' ').append(reason).append("\r\n")
                .append("Content-Type: ").append(r.type).append("\r\n")
                .append("Content-Length: ").append(r.body.length).append("\r\n")
                .append("Cache-Control: no-store\r\n")
                .append("Referrer-Policy: no-referrer\r\n")
                .append("X-Content-Type-Options: nosniff\r\n")
                .append("X-Frame-Options: DENY\r\n");
        if (r.type.startsWith("text/html")) {
            h.append("Content-Security-Policy: default-src 'none'; img-src https://image.tmdb.org; style-src 'unsafe-inline'; "
                    + "script-src 'unsafe-inline'; connect-src 'self'; form-action 'none'; base-uri 'none'\r\n");
        }
        h.append("Connection: close\r\n\r\n");
        out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(r.body);
        out.flush();
    }

    private static Response route(Request r) {
        String host;
        synchronized (PhoneLink.class) {
            if (server == null) return Response.json(503, error("stopped"));
            host = address + ":" + server.getLocalPort();
        }
        // Only requests made to the TV's own address: a web page elsewhere can't reach it through a renamed host.
        if (!host.equals(r.header("host"))) return Response.json(403, error("host"));
        if (r.path.startsWith("/sync/")) {
            if (!"POST".equals(r.method)) return Response.json(405, error("method"));
            return TvSync.handle(app, r);
        }
        boolean typing = r.path.equals("/t") || r.path.startsWith("/api/type") || r.path.equals("/api/open");
        boolean setup = r.path.equals("/s") || r.path.equals("/api/setup");
        String given = r.path.startsWith("/api/") ? r.header("x-luma-key") : r.param("k");
        if (typing && !same(given, typingToken) || setup && !same(given, setupToken)) {
            return r.path.startsWith("/api/") ? Response.json(403, error("expired"))
                    : Response.bytes(403, "text/html; charset=utf-8", expiredPage());
        }
        phoneAt = System.currentTimeMillis();
        try {
            switch (r.path) {
                case "/t": return page("phone/typing.html");
                case "/s": return page("phone/setup.html");
                case "/api/type": return type(r);
                case "/api/open": return open(r);
                case "/api/setup": return "POST".equals(r.method) ? saveSetup(r) : Response.json(200, setupView());
                default: return Response.json(404, error("not found"));
            }
        } catch (Exception e) {
            AppLog.w("phone link: " + e.getClass().getSimpleName());
            return Response.json(500, error("failed"));
        }
    }

    private static Response page(String asset) throws IOException {
        try (InputStream in = app.getAssets().open(asset)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            String html = new String(out.toByteArray(), StandardCharsets.UTF_8).replace("{{TV}}", escape(TvSync.name(app)));
            return Response.bytes(200, "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static byte[] expiredPage() {
        String text = "<!doctype html><meta charset=utf-8><meta name=viewport content=\"width=device-width\">"
                + "<body style=\"margin:0;background:#0A0B0D;color:#F5F3EF;font:17px system-ui,sans-serif;padding:60px 20px\">"
                + "<h1 style=\"font-size:28px;margin:0 0 12px\">This link expired</h1>"
                + "<p style=\"color:rgba(245,243,239,.72);margin:0\">Scan the code on the TV again. "
                + "/ O link expirou: escaneie o código na TV de novo.</p>";
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static JSONObject error(String code) {
        try { return new JSONObject().put("error", code); } catch (Exception e) { return new JSONObject(); }
    }

    // MARK: - Typing

    private static Response type(Request r) throws Exception {
        String text = new JSONObject(new String(r.body, StandardCharsets.UTF_8)).optString("text", "");
        if (text.length() > 200) text = text.substring(0, 200);
        final String typed = text;
        Listener l = listener;
        if (l != null) MAIN.post(() -> { Listener now = listener; if (now != null) now.onText(typed); });
        JSONObject out = new JSONObject();
        JSONArray list = new JSONArray();
        if (!typed.trim().isEmpty()) {
            if (!Setup.hasKey()) {
                out.put("error", "no_key");
            } else {
                try {
                    List<TitleSearch.Title> found = TitleSearch.searchBoth(typed);
                    for (int i = 0; i < found.size() && i < 8; i++) {
                        TitleSearch.Title t = found.get(i);
                        String id = t.kind + ":" + t.id;
                        results.put(id, t);
                        list.put(new JSONObject().put("id", id).put("title", t.name).put("year", t.year)
                                .put("kind", t.series() ? "series" : "movie")
                                .put("poster", t.poster.isEmpty() ? "" : "https://image.tmdb.org/t/p/w185" + t.poster));
                    }
                } catch (Exception e) {
                    out.put("error", "network");
                }
            }
        }
        return Response.json(200, out.put("results", list));
    }

    private static Response open(Request r) throws Exception {
        String id = new JSONObject(new String(r.body, StandardCharsets.UTF_8)).optString("id", "");
        if (!id.matches("(movie|tv):[0-9]{1,9}")) return Response.json(400, error("id"));
        Listener l = listener;
        if (l == null) return Response.json(200, new JSONObject().put("ok", false).put("error", "not_listening"));
        MAIN.post(() -> { Listener now = listener; if (now != null) now.onOpen(id); });
        return Response.json(200, new JSONObject().put("ok", true));
    }

    // MARK: - Setup

    private static JSONObject setupView() throws Exception {
        JSONObject v = Setup.publicView();   // never contains the key
        return new JSONObject().put("hasKey", v.optBoolean("hasKey")).put("city", v.optString("city"))
                .put("region", v.optString("region")).put("temperature", v.optString("temperature"));
    }

    private static Response saveSetup(Request r) throws Exception {
        JSONObject in = new JSONObject(new String(r.body, StandardCharsets.UTF_8));
        String key = in.optString("key", "").trim(), city = in.optString("city", "").trim(),
                country = in.optString("country", "").trim(), unit = in.optString("unit", "").trim().toUpperCase(Locale.ROOT);
        JSONObject fields = new JSONObject();
        if (!key.isEmpty()) {
            if (!key.matches("[A-Za-z0-9._-]{20,400}")) return Response.json(200, error("key_format"));
            int check = checkKey(key);
            if (check == 401) return Response.json(200, error("key_rejected"));
            fields.put("tmdb_key", key);
        }
        if (!city.isEmpty()) {
            if (city.length() > 80 || country.length() > 60) return Response.json(200, error("city"));
            String lang = in.optString("lang", "");
            JSONObject place = geocode(city, country, lang.matches("[a-z]{2}") ? lang : (Ui.en ? "en" : "pt"));
            if (place == null) return Response.json(200, error("city_not_found"));
            fields.put("city", place.optString("name", city))
                    .put("latitude", String.format(Locale.ROOT, "%.4f", place.optDouble("latitude")))
                    .put("longitude", String.format(Locale.ROOT, "%.4f", place.optDouble("longitude")));
            String code = place.optString("country_code", "");
            if (code.matches("[A-Za-z]{2}")) fields.put("region", code.toUpperCase(Locale.ROOT));
        }
        if (unit.equals("C") || unit.equals("F")) fields.put("temperature", unit);
        if (fields.length() == 0) return Response.json(200, error("empty"));
        if (!Setup.save(app, fields)) return Response.json(200, error("not_saved"));
        MAIN.post(() -> { Listener now = listener; if (now != null) now.onSetup(); });
        HomeMode.notice(app, Ui.tr("Configuração recebida do telefone.", "Setup received from your phone."));
        return Response.json(200, setupView().put("ok", true));
    }

    /** 200 when TMDB accepts the key, 401 when it doesn't, 0 when it couldn't be checked (saved anyway). */
    private static int checkKey(String key) {
        HttpURLConnection c = null;
        try {
            boolean bearer = key.startsWith("eyJ");
            String url = "https://api.themoviedb.org/3/configuration" + (bearer ? "" : "?api_key=" + URLEncoder.encode(key, "UTF-8"));
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(6000);
            c.setInstanceFollowRedirects(false);
            if (bearer) c.setRequestProperty("Authorization", "Bearer " + key);
            int code = c.getResponseCode();
            return code == 401 || code == 403 ? 401 : code == 200 ? 200 : 0;
        } catch (Exception e) {
            return 0;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Open-Meteo's free geocoding: the best match for the city, in the country when one was typed. */
    static JSONObject geocode(String city, String country, String lang) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("https://geocoding-api.open-meteo.com/v1/search?count=10&format=json&language="
                    + lang + "&name=" + URLEncoder.encode(city, "UTF-8")).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setInstanceFollowRedirects(false);
            if (c.getResponseCode() != 200) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) {
                    if (out.size() + n > 512 * 1024) return null;
                    out.write(b, 0, n);
                }
            }
            return pickPlace(new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8)).optJSONArray("results"), country);
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** The first result, or the first in the typed country (by name or two-letter code). */
    static JSONObject pickPlace(JSONArray results, String country) {
        if (results == null || results.length() == 0) return null;
        String want = country == null ? "" : country.trim().toLowerCase(Locale.ROOT);
        if (want.isEmpty()) return results.optJSONObject(0);
        for (int i = 0; i < results.length(); i++) {
            JSONObject o = results.optJSONObject(i);
            if (o == null) continue;
            String name = o.optString("country", "").toLowerCase(Locale.ROOT), code = o.optString("country_code", "").toLowerCase(Locale.ROOT);
            if (want.equals(name) || want.equals(code) || (!name.isEmpty() && (name.startsWith(want) || want.startsWith(name)))) return o;
        }
        return null;
    }
}
