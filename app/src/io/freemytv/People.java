package io.freemytv;

import java.util.*;
import org.json.*;

/** Public cast, people, and recommendation metadata. */
final class People {
    static List<TitlePage.Person> parse(JSONArray input) {
        List<TitlePage.Person> out = new ArrayList<>(); Set<Integer> seen = new HashSet<>();
        for (int i = 0; input != null && i < input.length() && out.size() < 20; i++) {
            JSONObject o = input.optJSONObject(i);
            if (o == null || o.optBoolean("adult") || o.optInt("id") <= 0 || !seen.add(o.optInt("id"))) continue;
            TitlePage.Person p = new TitlePage.Person(); p.id = o.optInt("id"); p.name = o.optString("name", "");
            p.role = o.optString("character", o.optString("known_for_department", "")); p.photo = o.optString("profile_path", "");
            if (!p.name.isEmpty()) out.add(p);
        }
        return out;
    }
    static List<TitlePage.Person> search(String text) throws Exception {
        return parse(TitleSearch.get("/search/person?include_adult=false&query=" + java.net.URLEncoder.encode(text, "UTF-8")
                + "&language=" + language()).optJSONArray("results"));
    }
    static String language() { return Ui.en ? "en-US" : "pt-BR"; }
    static String[][] credits(JSONObject credits) {
        Set<String> directors = new LinkedHashSet<>(), writers = new LinkedHashSet<>();
        JSONArray crew = credits == null ? null : credits.optJSONArray("crew");
        for (int i = 0; crew != null && i < crew.length(); i++) {
            JSONObject person = crew.optJSONObject(i); if (person == null) continue;
            String name = person.optString("name", ""), job = person.optString("job", "");
            if (name.isEmpty()) continue;
            if ("Director".equals(job)) directors.add(name);
            if ("Screenplay".equals(job) || "Writer".equals(job)) writers.add(name);
        }
        List<String> stars = new ArrayList<>();
        for (TitlePage.Person person : parse(credits == null ? null : credits.optJSONArray("cast"))) {
            stars.add(person.name); if (stars.size() == 3) break;
        }
        return new String[][]{{Ui.tr("Direção", "Director"), String.join(", ", directors)},
                {Ui.tr("Roteiro", "Writer"), String.join(", ", writers)}, {Ui.tr("Elenco", "Starring"), String.join(", ", stars)}};
    }
    static List<TitlePage.Item> items(JSONArray input, String kind) {
        List<TitlePage.Item> out = new ArrayList<>(); Set<String> seen = new HashSet<>();
        for (int i = 0; input != null && i < input.length() && out.size() < 20; i++) {
            JSONObject o = input.optJSONObject(i); if (o == null || o.optBoolean("adult")) continue;
            String type = o.optString("media_type", kind);
            if (!("tv".equals(type) || "movie".equals(type)) || o.optInt("id") <= 0 || !seen.add(type + o.optInt("id"))) continue;
            TitlePage.Item it = new TitlePage.Item(); it.id = o.optInt("id"); it.kind = type;
            it.name = o.optString("tv".equals(type) ? "name" : "title", "");
            String date = o.optString("tv".equals(type) ? "first_air_date" : "release_date", "");
            it.line = date.length() >= 4 ? date.substring(0, 4) : "";
            it.picture = o.optString("backdrop_path", ""); if (it.picture.isEmpty() || it.picture.equals("null")) it.picture = o.optString("poster_path", "");
            if (!it.name.isEmpty()) out.add(it);
        }
        return out;
    }
}
