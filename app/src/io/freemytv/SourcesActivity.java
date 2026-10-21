package io.freemytv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** Browse titles and episodes before automatic internal playback. */
public class SourcesActivity extends TvActivity {
    private final ExecutorService network = Executors.newFixedThreadPool(6);
    private LinearLayout content, controls;
    private TextView status, heading;
    private Button play, servicePlay, watchlist;
    private boolean servicePending, linksReady;
    private int serviceRequest;
    private int addonCount;
    private static final int PLAYER_REQUEST = 41;
    private String title, kind, imdb, videoId, videoTitle;
    private int titleId, season = 1, generation, remaining, failed, unsupported;
    private final List<Integer> seasons = new ArrayList<>();
    private final List<AddonStreams.Source> sources = new ArrayList<>();
    private boolean episodes, active, pendingPlay, authorizing;
    private TitlePage page;
    private final List<SeriesEpisodes.Episode> episodeList = new ArrayList<>();
    private final List<WatchOptions.Option> offers = new ArrayList<>();
    private int focusedEpisode = 1;
    private boolean focusEpisodes, offersLoading = true, autoResume;
    private String resumeVideo = "", backdrop = "";
    private long resumePosition = -1, resumeDuration;
    private int runtimeMinutes;
    private SourceGate sourceGate;
    private PlaybackSession liveSession;
    private String focusedWarmVideo = "";
    private final android.os.Handler playback = new android.os.Handler(android.os.Looper.getMainLooper());
    private final List<java.util.concurrent.Future<?>> sourceRequests = new ArrayList<>();
    private final Runnable warmFocused = () -> warmVideo(focusedWarmVideo);
    private void warmVideo(String video) {
        if (!AddonStore.connected(this) || !video.matches("tt[0-9]+(?::[0-9]+:[0-9]+)?")) return;
        ContinueWatching.Entry entry = new ContinueWatching.Entry(); entry.video = video; entry.kind = kind;
        PlaybackPrefetch.warm(this, entry);
    }
    private final Runnable sourceDeadline = () -> {
        generation++; remaining = 0;
        for (java.util.concurrent.Future<?> request : sourceRequests) request.cancel(true);
        sourceRequests.clear();
        if (liveSession != null && PlaybackSession.current == liveSession) liveSession.updateSources(sources, false);
        else renderSources();
    };
    private final Runnable beginPlayback = () -> { List<AddonStreams.Source> ready = checkedSources(); if (active && pendingPlay && !ready.isEmpty()) startPlayback(ready.get(0)); };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); Ui.init(this); Watchdog.install(this);
        title = getIntent().getStringExtra("title_name"); kind = getIntent().getStringExtra("title_kind");
        imdb = getIntent().getStringExtra("imdb"); titleId = getIntent().getIntExtra("title_id", 0);
        if (title == null || titleId <= 0 || !("movie".equals(kind) || "tv".equals(kind))) { finish(); return; }
        season = getSharedPreferences("series-ui", 0).getInt("season:" + titleId, 1);
        focusedEpisode = getSharedPreferences("series-ui", 0).getInt("episode:" + titleId, 1);
        resumeVideo = getIntent().getStringExtra("resume_video"); if (resumeVideo == null) resumeVideo = "";
        autoResume = getIntent().getBooleanExtra("auto_play", false);
        resumePosition = getIntent().getLongExtra("resume_position", -1);
        resumeDuration = getIntent().getLongExtra("resume_duration", 0);
        String selectedVideo = getIntent().getStringExtra("selected_video");
        if ("tv".equals(kind) && imdb != null && selectedVideo != null
                && selectedVideo.matches(java.util.regex.Pattern.quote(imdb) + ":[0-9]+:[0-9]+")) {
            String[] parts = selectedVideo.split(":");
            try { season = Integer.parseInt(parts[1]); focusedEpisode = Integer.parseInt(parts[2]); } catch (NumberFormatException ignored) {}
        }
        if (autoResume && resumeVideo.matches("tt[0-9]+:[0-9]+:[0-9]+")) {
            String[] parts = resumeVideo.split(":");
            try { season = Integer.parseInt(parts[1]); focusedEpisode = Integer.parseInt(parts[2]); } catch (Exception ignored) { autoResume = false; }
        }
        if (autoResume && imdb != null && (resumeVideo.equals(imdb) || resumeVideo.startsWith(imdb + ":"))) warmVideo(resumeVideo);
        page = new TitlePage(this, title, v -> onBackPressed());
        content = page.content; controls = page.actions; heading = page.heading; status = page.status;
        servicePlay = page.action("", v -> openService(), 210); servicePlay.setVisibility(View.GONE);
        play = page.action(Ui.tr("Reproduzir com Stremio", "Play with Stremio"), v -> playTitle(), 240); play.setEnabled(false); TvWidgets.primary(play);
        page.action(Ui.tr("Onde assistir", "Where to watch"), v -> providers(), 170);
        watchlist = page.watchlistButton(v -> {
            if (imdb == null) return;
            watchlist.setEnabled(false);
            network.execute(() -> {
                boolean saved = !ContinueWatching.inWatchlist(this, imdb);
                ContinueWatching.setWatchlist(this, imdb, kind, title, backdrop.isEmpty() ? "" : "https://image.tmdb.org/t/p/w780" + backdrop, saved);
                runOnUiThread(() -> { if (!isDestroyed()) { watchlist.setEnabled(true); TitlePage.setSaved(watchlist, ContinueWatching.inWatchlist(this, imdb)); } });
            });
        });
        setContentView(page);
        if (state != null) { season = state.getInt("season", season); focusedEpisode = state.getInt("episode", focusedEpisode); }
        initialize();
    }
    private void initialize() {
        int request = ++generation;
        network.execute(() -> {
            try {
                org.json.JSONObject details = TitleSearch.get("/" + kind + "/" + titleId + "?append_to_response=external_ids,credits,recommendations&language=" + (Ui.en ? "en-US" : "pt-BR"));
                org.json.JSONObject ids = details.optJSONObject("external_ids");
                String mapped = ids == null ? details.optString("imdb_id", "") : ids.optString("imdb_id", "");
                if ("tv".equals(kind)) EpisodeMap.load(mapped);
                runOnUiThread(() -> {
                    if (!current(request)) return;
                    imdb = mapped.matches("tt[0-9]+") ? mapped : null;
                    page.summary.setText(details.optString("overview", ""));
                    String date = details.optString("tv".equals(kind) ? "first_air_date" : "release_date", "");
                    String year = date.length() >= 4 ? date.substring(0, 4) : "";
                    backdrop = details.optString("backdrop_path", ""); page.backdrop(backdrop);
                    if ("movie".equals(kind)) page.poster(details.optString("poster_path", ""));
                    watchlist.setEnabled(imdb != null); TitlePage.setSaved(watchlist, ContinueWatching.inWatchlist(this, imdb));
                    JSONObject credits = details.optJSONObject("credits"), recommendations = details.optJSONObject("recommendations");
                    if ("movie".equals(kind)) page.credits(People.credits(credits));
                    page.cast(People.parse(credits == null ? null : credits.optJSONArray("cast")), person -> startActivity(new Intent(this, PersonActivity.class)
                            .putExtra("person_id", person.id).putExtra("person_name", person.name)));
                    page.similar(People.items(recommendations == null ? null : recommendations.optJSONArray("results"), kind), item -> startActivity(new Intent(this, SourcesActivity.class)
                            .putExtra("title_id", item.id).putExtra("title_kind", item.kind).putExtra("title_name", item.name)));
                    if ("tv".equals(kind)) {
                        org.json.JSONArray typical = details.optJSONArray("episode_run_time");
                        if (typical != null && typical.length() > 0) runtimeMinutes = typical.optInt(0);
                        seasons.clear();
                        List<SeriesEpisodes.Season> available = SeriesEpisodes.seasons(details.optJSONArray("seasons"));
                        if (EpisodeMap.available(imdb)) seasons.addAll(EpisodeMap.seasons(imdb));
                        if (seasons.isEmpty()) for (SeriesEpisodes.Season item : available) seasons.add(item.number);
                        if (!seasons.contains(season) && !seasons.isEmpty()) season = seasons.get(0);
                        int regular = 0; for (int number : seasons) if (number > 0) regular++;
                        page.metadata.setText(year + "  ·  " + String.format(Ui.tr("%d temporadas", "%d seasons"), regular));
                        loadEpisodes();
                    } else {
                        int runtime = details.optInt("runtime", 0); runtimeMinutes = runtime;
                        page.metadata.setText(year + (runtime > 0 ? "  ·  " + runtime + " min" : ""));
                        play.setEnabled(imdb != null); status.setText(""); if (active) play.requestFocus();
                        if (autoResume && active && imdb != null && imdb.equals(resumeVideo)) { autoResume = false; loadSources(imdb, title); }
                    }
                    network.execute(() -> {
                        try {
                            TitleSearch.Title selectedTitle = makeTitle();
                            WatchOptions.Result result = WatchOptions.catalog(this, selectedTitle);
                            runOnUiThread(() -> { if (!isDestroyed()) { offers.clear(); offers.addAll(result.options); offersLoading = false; updateServices(); } });
                            WatchOptions.resolveLinks(result, selectedTitle);
                            runOnUiThread(() -> { if (!isDestroyed()) { offers.clear(); offers.addAll(result.options); linksReady = true; if (servicePending) launchService(); } });
                        } catch (Exception ignored) { runOnUiThread(() -> offersLoading = false); }
                    });
                });
            } catch (Exception ignored) { runOnUiThread(() -> {
                if (current(request)) error(Ui.tr("Não foi possível carregar o título.", "Cannot load this title."), () -> initialize());
            }); }
        });
    }
    private TitleSearch.Title makeTitle() {
        TitleSearch.Title value = new TitleSearch.Title(); value.id = titleId; value.kind = kind; value.name = title; return value;
    }
    private void playTitle() {
        if ("tv".equals(kind)) {
            SeriesEpisodes.Episode selected = null;
            java.util.Set<String> watched = ContinueWatching.watched(this, imdb);
            for (SeriesEpisodes.Episode item : episodeList) if (item.number == focusedEpisode && !item.future && !watched.contains(item.season+":"+item.number)) selected = item;
            if (selected == null) for (SeriesEpisodes.Episode item : episodeList) if (!item.future && !watched.contains(item.season+":"+item.number)) { selected = item; break; }
            if (selected == null) for (SeriesEpisodes.Episode item : episodeList) if (item.number == focusedEpisode && !item.future) { selected=item;break; }
            if (selected != null) selectEpisode(selected);
        } else if (imdb != null) loadSources(imdb, title);
    }
    private void selectEpisode(SeriesEpisodes.Episode episode) {
        if (episode.future) { status.setText(Ui.tr("Este episódio ainda não estreou.", "This episode has not aired.")); return; }
        if (imdb == null) { status.setText(Ui.tr("Este título não tem um identificador de reprodução.", "This title has no playback identifier.")); return; }
        focusedEpisode = episode.number; saveSelection();
        loadSources(episode.id(imdb), title + " · " + episode.code() + " · " + episode.name);
    }
    private void saveSelection() {
        getSharedPreferences("series-ui", 0).edit().putInt("season:" + titleId, season).putInt("episode:" + titleId, focusedEpisode).apply();
    }
    private void connect() {
        if (authorizing || !active || !pendingPlay) return;
        authorizing = true;
        startActivityForResult(new Intent(this, ConnectSourcesActivity.class).putExtra("automatic", true), 40);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PLAYER_REQUEST) {
            liveSession = null;
            if (data != null && data.getBooleanExtra("retry_sources", false)) { loadSources(videoId, videoTitle); return; }
            if ("tv".equals(kind)) {
                focusEpisodes = true;
                if (result == RESULT_OK && data != null && data.getBooleanExtra("next_episode", false)) {
                    season = data.getIntExtra("season", season); focusedEpisode = data.getIntExtra("episode", focusedEpisode);
                    resumeVideo = imdb + ":" + season + ":" + focusedEpisode; resumePosition = 0; autoResume = true; saveSelection();
                }
                loadEpisodes();
            } else { pendingPlay = false; status.setText(""); content.removeAllViews(); }
        }
        if (request == 40) {
            authorizing = false;
            if (result == RESULT_OK && AddonStore.connected(this)) loadSources(videoId, videoTitle);
            else { pendingPlay = false; status.setText(Ui.tr("A autorização da conta é necessária para reproduzir.", "Account approval is required for playback.")); }
        }
    }
    private void loadEpisodes() {
        page.deadEnd(null, null, null, null);
        playback.removeCallbacks(beginPlayback); playback.removeCallbacks(sourceDeadline);
        for (java.util.concurrent.Future<?> pending : sourceRequests) pending.cancel(true); sourceRequests.clear();
        liveSession = null; episodes = true; episodeList.clear(); pendingPlay = false; int request = ++generation; sources.clear(); content.removeAllViews();
        status.setText(Ui.tr("Carregando episódios…", "Loading episodes…"));
        final int requestedSeason = season;
        network.execute(() -> {
            try {
                JSONArray result;
                try { result = TitleSearch.get("/tv/" + titleId + "/season/" + requestedSeason + "?language=" + (Ui.en ? "en-US" : "pt-BR")).getJSONArray("episodes"); }
                catch (Exception failure) { if (!EpisodeMap.available(imdb)) throw failure; result = new JSONArray(); }
                List<SeriesEpisodes.Episode> parsed = EpisodeMap.episodes(imdb, requestedSeason, SeriesEpisodes.episodes(result, requestedSeason, SeriesEpisodes.today()), SeriesEpisodes.today());
                runOnUiThread(() -> {
                    if (!current(request)) return;
                    episodeList.clear(); episodeList.addAll(parsed);
                    status.setText(parsed.isEmpty() ? Ui.tr("Nenhum episódio encontrado.", "No episodes found.") : "");
                    page.episodes(parsed, imdb, seasons, season, focusedEpisode, focusEpisodes && active, ContinueWatching.watched(this, imdb), next -> {
                        if (next != season) { season = next; focusedEpisode = 1; focusEpisodes = true; saveSelection(); loadEpisodes(); }
                    }, new TitlePage.Episodes() {
                        public void select(SeriesEpisodes.Episode episode) { selectEpisode(episode); }
                        public void focus(SeriesEpisodes.Episode episode) {
                            focusedEpisode = episode.number; saveSelection(); updatePlayLabel();
                            playback.removeCallbacks(warmFocused);
                            if (!episode.future && imdb != null) {
                                focusedWarmVideo = episode.id(imdb); playback.postDelayed(warmFocused, 750);
                            }
                        }
                    });
                    play.setEnabled(imdb != null && !parsed.isEmpty()); updatePlayLabel();
                    if (!focusEpisodes && active) { if (servicePlay.getVisibility() == View.VISIBLE) servicePlay.requestFocus(); else play.requestFocus(); } focusEpisodes = false;
                    tryAutoResume();
                });
            } catch (Exception ignored) { runOnUiThread(() -> {
                if (current(request)) error(Ui.tr("Não foi possível carregar os episódios.", "Cannot load episodes."), () -> loadEpisodes());
            }); }
        });
    }
    private void tryAutoResume() {
        if (!autoResume || !active || !episodes || imdb == null) return;
        for (SeriesEpisodes.Episode item : episodeList) if (item.id(imdb).equals(resumeVideo)) {
            autoResume = false; selectEpisode(item); return;
        }
    }
    private void updatePlayLabel() {
        long position = imdb == null ? 0 : getSharedPreferences("playback-progress", 0).getLong(imdb + ":" + season + ":" + focusedEpisode, 0);
        boolean available = false;
        for (SeriesEpisodes.Episode item : episodeList) if (item.number == focusedEpisode && !item.future) available = true;
        play.setEnabled(imdb != null && available);
        String label = position > 0 ? Ui.tr("Continuar com Stremio", "Resume with Stremio") : Ui.tr("Reproduzir com Stremio", "Play with Stremio");
        play.setText(label); play.setContentDescription(label + ". S" + season + " E" + focusedEpisode);
    }
    private void updateServices() {
        WatchOptions.Option preferred = WatchOptions.preferred(offers);
        boolean initialFocus = getCurrentFocus() == null || play.hasFocus();
        servicePlay.setVisibility(preferred == null ? View.GONE : View.VISIBLE);
        if (preferred != null) {
            servicePlay.setText(String.format(Ui.tr("Assistir no %s", "Watch on %s"), preferred.service.name));
            TvWidgets.primary(servicePlay); play.setBackground(TvWidgets.background(this));
            play.setTextColor(TvWidgets.textColors());
            if (active && initialFocus && !pendingPlay && liveSession == null) servicePlay.requestFocus();
        }
    }
    private void openService() {
        final int request = ++serviceRequest;
        if (!linksReady) {
            servicePending = true; servicePlay.setEnabled(false);
            status.setText(Ui.tr("Abrindo o título…", "Opening the title…"));
            playback.postDelayed(() -> { if (servicePending && active && request == serviceRequest) launchService(); }, 6000);
            return;
        }
        launchService();
    }
    private void cancelService() {
        serviceRequest++;
        if (servicePending) { servicePending = false; servicePlay.setEnabled(true); status.setText(""); }
    }
    private void launchService() {
        servicePending = false; servicePlay.setEnabled(true);
        if (!active || isFinishing()) return;
        WatchOptions.Option preferred = WatchOptions.preferred(offers);
        if (preferred == null) return;
        Intent intent = WatchOptions.intent(this, preferred);
        if (intent != null) try { startActivity(intent); }
        catch (Exception ignored) { status.setText(Ui.tr("Não foi possível abrir o serviço.", "Cannot open this service.")); }
    }
    private void providers() {
        if (offers.isEmpty()) {
            new AlertDialog.Builder(this).setTitle(Ui.tr("Onde assistir", "Where to watch"))
                    .setMessage(offersLoading ? Ui.tr("Verificando os serviços. Tente novamente em instantes.", "Checking services. Try again shortly.")
                            : Ui.tr("Nenhuma oferta encontrada nos serviços instalados.", "No offers found in the installed services."))
                    .setPositiveButton(Ui.tr("Fechar", "Close"), null).show(); return;
        }
        String[] labels = new String[offers.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = offers.get(i).service.name + " · " + offers.get(i).offersLabel();
        new AlertDialog.Builder(this).setTitle(Ui.tr("Onde assistir", "Where to watch"))
                .setItems(labels, (dialog, which) -> {
                    Intent intent = WatchOptions.intent(this, offers.get(which));
                    if (intent != null) try { startActivity(intent); } catch (Exception ignored) {
                        status.setText(Ui.tr("Não foi possível abrir o serviço.", "Cannot open this service."));
                    }
                }).show();
    }
    private void loadSources(String id, String label) {
        playback.removeCallbacks(warmFocused);
        cancelService();
        page.deadEnd(null, null, null, null);
        PlaybackMemory.init(this);
        episodes = false; pendingPlay = true; videoId = id; videoTitle = label;
        if (!AddonStore.connected(this)) { status.setText(Ui.tr("Preparando sua conta…", "Preparing your account…")); connect(); return; }
        playback.removeCallbacks(beginPlayback); playback.removeCallbacks(sourceDeadline);
        for (java.util.concurrent.Future<?> pending : sourceRequests) pending.cancel(true); sourceRequests.clear();
        sourceGate = new SourceGate(); liveSession = null;
        playback.postDelayed(sourceDeadline, 15000);
        int request = ++generation; remaining = 0; failed = 0; unsupported = 0; sources.clear();
        content.removeAllViews();
        content.addView(text(label, 18));
        status.setText(Ui.tr("Verificando complementos…", "Checking add-ons…"));
        network.execute(() -> {
            try {
                List<AddonStreams.Addon> addons = AddonStreams.addons(StremioAccount.addons(this), "tv".equals(kind) ? "series" : "movie", id);
                runOnUiThread(() -> {
                    if (!current(request)) return;
                    addonCount = addons.size(); remaining = addonCount;
                    if (remaining == 0) { playback.removeCallbacks(sourceDeadline); renderSources(); return; }
                    for (AddonStreams.Addon addon : addons) sourceRequests.add(network.submit(() -> {
                        AddonStreams.Result result = null;
                        try { result = AddonStreams.fetch(addon, "tv".equals(kind) ? "series" : "movie", id); }
                        catch (Exception ignored) {}
                        final AddonStreams.Result loaded = result;
                        runOnUiThread(() -> {
                            if (!current(request)) return;
                            if (loaded == null) failed++; else {
                                for (AddonStreams.Source source : loaded.sources) if (sources.size() < 300) sources.add(source);
                                unsupported += loaded.unsupported;
                            }
                            remaining--;
                            if (remaining == 0) { playback.removeCallbacks(sourceDeadline); sourceRequests.clear(); }
                            if (liveSession != null && PlaybackSession.current == liveSession) {
                                liveSession.updateSources(sources, remaining > 0);
                            } else renderSources();
                        });
                    }));
                });
            } catch (Exception ignored) { runOnUiThread(() -> {
                if (current(request)) error(Ui.tr("Não foi possível ler as fontes. Reconecte seus complementos.",
                        "Cannot load playback sources. Try again."), () -> loadSources(videoId, videoTitle));
            }); }
        });
    }
    private List<AddonStreams.Source> checkedSources() {
        return PlaybackPlan.automatic(sources);
    }
    private void renderSources() {
        View focus = getCurrentFocus(); Object tag = focus == null ? null : focus.getTag();
        List<AddonStreams.Source> selected = checkedSources();
        playback.removeCallbacks(beginPlayback);
        if (pendingPlay && !selected.isEmpty() && active) {
            long delay = sourceGate.delay(android.os.SystemClock.elapsedRealtime(), remaining, selected);
            if (delay == 0) { startPlayback(selected.get(0)); return; }
            if (delay > 0) playback.postDelayed(beginPlayback, delay);
        }
        content.removeAllViews();
        String message = remaining > 0 ? Ui.tr("Encontrando uma fonte…", "Finding a source…")
                : selected.isEmpty() ? Ui.tr("Nenhuma fonte disponível. Tente novamente mais tarde.", "No sources are available. Try again later.")
                : Ui.tr("Pronto para reproduzir.", "Ready to play.");
        status.setText(message);
        if (remaining == 0) {
            if (selected.isEmpty()) {
                pendingPlay = false;
                List<String> reasons = new ArrayList<>();
                if (addonCount == 0) reasons.add(Ui.tr("Nenhum complemento oferece fontes para este título.", "No add-on offers sources for this title."));
                if (failed > 0) reasons.add(String.format(Ui.tr("%d de %d complementos não responderam.", "%d of %d add-ons did not respond."), failed, addonCount));
                if (unsupported > 0) reasons.add(String.format(Ui.tr("%d resultados não têm um endereço de vídeo compatível.", "%d results have no supported video address."), unsupported));
                if (reasons.isEmpty()) reasons.add(Ui.tr("As fontes verificadas não responderam.", "The checked sources did not respond."));
                List<String> labels = new ArrayList<>(); List<View.OnClickListener> actions = new ArrayList<>();
                labels.add(Ui.tr("Tentar novamente", "Try again")); actions.add(v -> loadSources(videoId, videoTitle));
                if (StremioLinks.installed(this) && imdb != null) { labels.add(Ui.tr("Abrir no Stremio", "Open in Stremio")); actions.add(v -> openStremio()); }
                page.deadEnd(Ui.tr("Não foi possível reproduzir", "Cannot play this title"), reasons, labels.toArray(new String[0]), actions.toArray(new View.OnClickListener[0]));
            } else addButton(content, Ui.tr("Reproduzir", "Play"), v -> startPlayback(selected.get(0)));
        }
    }
    private void openStremio() {
        try { startActivity(new Intent(Intent.ACTION_VIEW, StremioLinks.videoUri(kind, imdb, videoId)).setPackage(StremioLinks.PACKAGE)); }
        catch (Exception e) { status.setText(Ui.tr("Não foi possível abrir o Stremio.", "Cannot open Stremio.")); }
    }
    private void startPlayback(AddonStreams.Source first) {
        if (!active) return;
        List<AddonStreams.Source> queue = PlaybackPlan.automatic(sources);
        if (first != null) {
            queue.remove(first); queue.add(0, first);
        }
        if (queue.isEmpty()) return;
        pendingPlay = false; playback.removeCallbacks(beginPlayback);
        PlaybackSession.current = new PlaybackSession(videoId, videoTitle, queue, -1);
        liveSession = PlaybackSession.current; liveSession.updateSources(sources, remaining > 0); liveSession.discovering = remaining > 0;
        ContinueWatching.Entry entry = new ContinueWatching.Entry();
        entry.id = "local:" + videoId; entry.video = videoId; entry.imdb = imdb; entry.tmdb = titleId;
        entry.title = title; entry.kind = kind; entry.provider = "Stremio"; entry.art = backdrop.isEmpty() ? "" : "https://image.tmdb.org/t/p/w780" + backdrop;
        long expected = runtimeMinutes * 60000L;
        if ("tv".equals(kind)) { entry.season = season; entry.episode = focusedEpisode;
            for (SeriesEpisodes.Episode episode : episodeList) if (episode.number == focusedEpisode) { entry.subtitle = episode.name; if (episode.minutes > 0) expected = episode.minutes * 60000L; }
        }
        if (expected == 0 && videoId.equals(resumeVideo)) expected = resumeDuration;
        liveSession.expectedDuration = expected; entry.duration = expected;
        PlaybackSession.current.entry = entry;
        if (videoId.equals(resumeVideo) && resumePosition >= 0) {
            PlaybackSession.current.resumePosition = resumePosition; resumeVideo = ""; resumePosition = -1;
        }
        startActivityForResult(new Intent(this, PlayerActivity.class), PLAYER_REQUEST);
    }
    private void error(String message, Runnable retry) {
        status.setText(message); content.removeAllViews();
        addButton(content, Ui.tr("Tentar novamente", "Try again"), v -> retry.run()); focusFirst();
    }
    private void focusFirst() { if (active && content.getChildCount() > 0) content.getChildAt(0).requestFocus(); }
    private Button addButton(LinearLayout parent, String value, View.OnClickListener action) {
        Button button = TvWidgets.button(this, value, action);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(parent == controls ? -2 : -1, -2);
        params.bottomMargin = dp(8); params.rightMargin = dp(12); parent.addView(button, params); return button;
    }
    @Override public void onBackPressed() {
        if ("tv".equals(kind) && !episodes) { focusEpisodes = true; loadEpisodes(); } else super.onBackPressed();
    }
    @Override protected void onResume() {
        super.onResume(); page.resumeArtwork(); active = true; tryAutoResume();
        if (watchlist != null) TitlePage.setSaved(watchlist, ContinueWatching.inWatchlist(this, imdb));
        if (pendingPlay && !authorizing) {
            if (!AddonStore.connected(this)) connect();
            else if (!episodes && !sources.isEmpty()) renderSources();
        }
    }
    @Override protected void onStop() { page.pauseArtwork(); super.onStop(); }
    @Override protected void onPause() { active = false; cancelService(); playback.removeCallbacks(beginPlayback); playback.removeCallbacks(warmFocused); super.onPause(); }
    @Override protected void onDestroy() {
        generation++; playback.removeCallbacksAndMessages(null); network.shutdownNow(); page.release();
        if (liveSession != null && liveSession.discovering) { liveSession.discovering = false; if (liveSession.sourcesChanged != null) liveSession.sourcesChanged.run(); }
        super.onDestroy();
    }
    @Override protected void onSaveInstanceState(Bundle out) { out.putInt("season", season); out.putInt("episode", focusedEpisode); super.onSaveInstanceState(out); }
    private boolean current(int request) { return request == generation && !isFinishing() && !isDestroyed(); }
    private int dp(float value) { return TvWidgets.dp(this, value); }
    private TextView text(String value, int size) { return TvWidgets.text(this, value, size); }
    private LinearLayout column() { LinearLayout value = new LinearLayout(this); value.setOrientation(LinearLayout.VERTICAL); return value; }
}
