package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Regional catalog offers and verified app links remain separate from account access. */
final class WatchOptions {
    static final class Service {
        final String key, name, pkg;
        final String[] aliases, domains;
        Service(String key, String name, String pkg, String[] aliases, String... domains) {
            this.key = key; this.name = name; this.pkg = pkg; this.aliases = aliases; this.domains = domains;
        }
    }
    static final class Option {
        Service service;
        final List<String> offers = new ArrayList<>();
        Uri link;
        boolean direct() { return link != null; }
        String offersLabel() {
            List<String> labels = new ArrayList<>();
            for (String offer : offers) {
                switch (offer) {
                    case "flatrate": labels.add(Ui.tr("Assinatura", "Subscription")); break;
                    case "free": labels.add(Ui.tr("Grátis", "Free")); break;
                    case "ads": labels.add(Ui.tr("Com anúncios", "With ads")); break;
                    case "rent": labels.add(Ui.tr("Alugar", "Rent")); break;
                    case "buy": labels.add(Ui.tr("Comprar", "Buy")); break;
                }
            }
            return android.text.TextUtils.join(" · ", labels);
        }
    }
    static final class Result {
        final List<Option> options = new ArrayList<>();
        String imdb;
        boolean catalogChecked, linksChecked;
    }
    static final Service[] SERVICES = {
        new Service("netflix", "Netflix", "com.netflix.ninja", new String[]{"netflix", "netflix standard with ads"}, "netflix.com"),
        new Service("prime", "Prime Video", "com.amazon.amazonvideo.livingroom", new String[]{"amazon prime video", "amazon prime video with ads", "amazon video"}, "watch.amazon.com", "app.primevideo.com", "primevideo.com", "amazon.com"),
        new Service("disney", "Disney+", "com.disney.disneyplus", new String[]{"disney plus", "disney+"}, "disneyplus.com"),
        new Service("max", "HBO Max", "com.wbd.stream", new String[]{"hbo max", "max"}, "play.max.com", "hbomax.com"),
        new Service("appletv", "Apple TV", "com.apple.atve.androidtv.appletv", new String[]{"apple tv", "apple tv+", "apple tv plus", "apple tv store"}, "tv.apple.com", "itunes.apple.com"),
        new Service("hulu", "Hulu", "com.hulu.livingroomplus", new String[]{"hulu"}, "hulu.com"),
        new Service("paramount", "Paramount+", "com.cbs.ott", new String[]{"paramount plus", "paramount+", "paramount plus essential", "paramount plus premium"}, "paramountplus.com"),
        new Service("peacock", "Peacock", "com.peacocktv.peacockandroid", new String[]{"peacock", "peacock premium", "peacock premium plus"}, "peacocktv.com"),
        new Service("globoplay", "Globoplay", "com.globo.globotv", new String[]{"globoplay"}, "globoplay.globo.com"),
        new Service("sunnxt", "Sun NXT", "com.suntv.sunnxt", new String[]{"sun nxt"}, "sunnxt.com"),
        new Service("tubi", "Tubi", "com.tubitv", new String[]{"tubi", "tubi tv"}, "tubitv.com"),
        new Service("plex", "Plex", "com.plexapp.android", new String[]{"plex", "plex channel"}, "watch.plex.tv", "plex.tv")
    };
    private WatchOptions() {}

    static Service service(String name) {
        String normalized = name == null ? "" : name.toLowerCase(Locale.ROOT).trim();
        for (Service service : SERVICES) for (String alias : service.aliases)
            if (alias.equals(normalized)) return service;
        return null;
    }

    static Map<String, Option> parseCatalog(JSONObject country) {
        Map<String, Option> result = new LinkedHashMap<>();
        for (String category : new String[]{"flatrate", "free", "ads", "rent", "buy"}) {
            JSONArray providers = country == null ? null : country.optJSONArray(category);
            for (int i = 0; providers != null && i < providers.length(); i++) {
                JSONObject provider = providers.optJSONObject(i);
                if (provider == null) continue;
                Service service = service(provider.optString("provider_name"));
                if (service == null) continue;
                Option option = result.get(service.key);
                if (option == null) { option = new Option(); option.service = service; result.put(service.key, option); }
                if (!option.offers.contains(category)) option.offers.add(category);
            }
        }
        return result;
    }

    /** Build a new intent from allowed data. Do not execute intent fields returned by an add-on. */
    static Uri safeLink(Service service, String raw) {
        if (raw == null || raw.length() > 4096) return null;
        Uri link = Uri.parse(raw);
        if (!"https".equalsIgnoreCase(link.getScheme()) || link.getHost() == null || link.getUserInfo() != null
                || (link.getPort() != -1 && link.getPort() != 443)) return null;
        String host = link.getHost().toLowerCase(Locale.ROOT);
        for (String domain : service.domains) if (host.equals(domain) || host.endsWith("." + domain)) return link;
        return null;
    }

    static Result load(Context c, TitleSearch.Title title) throws Exception {
        Result result = catalog(c, title);
        resolveLinks(result, title);
        return result;
    }
    static Result catalog(Context c, TitleSearch.Title title) throws Exception {
        Result result = new Result();
        JSONObject details = TitleSearch.get("/" + title.kind + "/" + title.id + "?append_to_response=external_ids,watch/providers");
        JSONObject ids = details.optJSONObject("external_ids");
        String imdb = ids == null ? details.optString("imdb_id") : ids.optString("imdb_id");
        result.imdb = imdb.matches("tt[0-9]+") ? imdb : null;
        JSONObject watch = details.optJSONObject("watch/providers");
        JSONObject countries = watch == null ? null : watch.optJSONObject("results");
        result.catalogChecked = countries != null;
        String region = Setup.region();
        Map<String, Option> options = parseCatalog(countries == null ? null : countries.optJSONObject(region));
        for (Service service : SERVICES) {
            Option option = options.get(service.key);
            if (option != null && HomeApps.launchIntent(c.getPackageManager(), service.pkg) != null) result.options.add(option);
        }
        result.options.sort(java.util.Comparator.comparingInt(WatchOptions::rank));
        return result;
    }
    private static int rank(Option option) {
        return option.offers.contains("flatrate") ? 0 : option.offers.contains("free") || option.offers.contains("ads") ? 1 : 2;
    }
    static Option preferred(List<Option> options) {
        for (Option option : options) if (rank(option) == 0) return option;
        for (Option option : options) if (rank(option) == 1) return option;
        return null;
    }
    static void resolveLinks(Result result, TitleSearch.Title title) {
        if (result.imdb != null) {
            try {
                JSONObject links = watchHub(title.series() ? "series" : "movie", result.imdb);
                JSONArray streams = links.optJSONArray("streams");
                result.linksChecked = streams != null;
                for (int i = 0; streams != null && i < streams.length(); i++) {
                    JSONObject stream = streams.optJSONObject(i);
                    Service service = stream == null ? null : service(stream.optString("name"));
                    Option option = null;
                    if (service != null) for (Option candidate : result.options) if (candidate.service == service) option = candidate;
                    if (option == null || option.link != null) continue;
                    option.link = safeLink(service, stream.optString("externalUrl"));
                }
            } catch (Exception ignored) { /* Catalog offers remain available when link lookup fails. */ }
        }
    }

    private static JSONObject watchHub(String kind, String imdb) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("https://watchhub.strem.io/stream/" + kind + "/" + imdb + ".json").openConnection();
        c.setConnectTimeout(5000); c.setReadTimeout(5000);
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("User-Agent", "FreeMyTV/1.0");
        c.setRequestProperty("Accept", "application/json");
        try {
            if (c.getResponseCode() != 200) throw new java.io.IOException("links");
            try (InputStream input = c.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] block = new byte[8192]; int count;
                while ((count = input.read(block)) != -1) {
                    if (output.size() + count > 1024 * 1024) throw new java.io.IOException("response_size");
                    output.write(block, 0, count);
                }
                return new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally { c.disconnect(); }
    }

    static Intent intent(Context c, Option option) {
        Intent linked = option.link == null ? null : new Intent(Intent.ACTION_VIEW, option.link).setPackage(option.service.pkg);
        if (linked != null && linked.resolveActivity(c.getPackageManager()) != null) return linked;
        return HomeApps.launchIntent(c.getPackageManager(), option.service.pkg);
    }
}
