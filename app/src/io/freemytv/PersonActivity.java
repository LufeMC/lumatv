package io.freemytv;

import android.content.Intent;
import android.os.Bundle;
import java.util.concurrent.*;
import org.json.*;

/** A person's biography and filmography use the title-page navigation pattern. */
public class PersonActivity extends TvActivity {
    private TitlePage page;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); Ui.init(this);
        int id = getIntent().getIntExtra("person_id", 0); if (id <= 0) { finish(); return; }
        page = new TitlePage(this, getIntent().getStringExtra("person_name"), v -> finish()); setContentView(page);
        page.status.setText(Ui.tr("Carregando…", "Loading…"));
        worker.execute(() -> {
            try {
                JSONObject person = TitleSearch.get("/person/" + id + "?append_to_response=combined_credits&language=" + People.language());
                JSONObject credits = person.optJSONObject("combined_credits");
                JSONArray combined = new JSONArray();
                for (String key : new String[]{"cast", "crew"}) {
                    JSONArray a = credits == null ? null : credits.optJSONArray(key);
                    for (int i = 0; a != null && i < a.length(); i++) combined.put(a.opt(i));
                }
                java.util.List<TitlePage.Item> titles = People.items(combined, "");
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    page.heading.setText(person.optString("name")); page.metadata.setText(person.optString("known_for_department"));
                    page.summary.setMaxLines(6); page.summary.setText(person.optString("biography")); page.poster(person.optString("profile_path"));
                    page.status.setText(""); page.filmography(titles, item -> startActivity(new Intent(this, SourcesActivity.class)
                            .putExtra("title_id", item.id).putExtra("title_kind", item.kind).putExtra("title_name", item.name)));
                });
            } catch (Exception e) { runOnUiThread(() -> { if (!isDestroyed()) page.status.setText(Ui.tr("Não foi possível carregar esta pessoa.", "Cannot load this person.")); }); }
        });
    }
    @Override protected void onStop() { if (page != null) page.pauseArtwork(); super.onStop(); }
    @Override protected void onResume() { super.onResume(); if (page != null) page.resumeArtwork(); }
    @Override protected void onDestroy() { worker.shutdownNow(); if (page != null) page.release(); super.onDestroy(); }
}
