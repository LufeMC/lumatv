package io.freemytv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;

/** Test release times, saved progress, Home navigation, and notice dismissal with isolated data. */
public class NewEpisodeTests extends TvTest {
    private int checks;
    private HomeActivity home;
    private HomeView view;
    private void check(boolean value, String label) { if (!value) throw new AssertionError(label); checks++; }
    private Object value(Object owner, String name) throws Exception { Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner); }
    private JSONObject episode(int number, long released) throws Exception {
        return new JSONObject().put("id", "tt0898266:10:" + number).put("season", 10).put("episode", number)
                .put("released", java.time.Instant.ofEpochMilli(released).toString());
    }
    private void key(int code) {
        runOnMainSync(() -> { long now = SystemClock.uptimeMillis(); home.dispatchKeyEvent(new KeyEvent(now, now, 0, code, 0)); home.dispatchKeyEvent(new KeyEvent(now, now, 1, code, 0)); });
        SystemClock.sleep(180);
    }
    private interface Condition { boolean ready() throws Exception; }
    private void await(Condition c, String label) throws Exception {
        long end = SystemClock.elapsedRealtime() + 15000;
        while (SystemClock.elapsedRealtime() < end) {
            boolean[] pass = {false}; Throwable[] error = {null};
            runOnMainSync(() -> { try { pass[0] = c.ready(); } catch (Throwable e) { error[0] = e; } });
            if (error[0] != null) throw new AssertionError(label, error[0]);
            if (pass[0]) { checks++; return; } SystemClock.sleep(100);
        }
        throw new AssertionError(label);
    }
    private void capture(String name) throws Exception {
        android.graphics.Bitmap image = getUiAutomation().takeScreenshot();
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(getTargetContext().getExternalFilesDir(null), name))) {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
        } finally { image.recycle(); }
    }
    @Override public void onCreate(Bundle args) { super.onCreate(args); if (android.app.Application.getProcessName().equals("io.freemytv:home")) start(); }
    @Override public void onStart() {
        Bundle output = new Bundle(); AtomicReference<Intent> opened = new AtomicReference<>();
        ActivityMonitor monitor = new ActivityMonitor() { @Override public ActivityResult onStartActivity(Intent intent) {
            if (intent.getComponent() != null && SourcesActivity.class.getName().equals(intent.getComponent().getClassName())) {
                opened.set(new Intent(intent)); return new ActivityResult(Activity.RESULT_CANCELED, null);
            } return null;
        }};
        try {
            getUiAutomation(); Ui.init(getTargetContext()); long now = System.currentTimeMillis();
            LibrarySync.store(getTargetContext(), new JSONArray());
            getTargetContext().getSharedPreferences(ContinueWatching.PREFS, 0).edit().clear().putBoolean("migrated", true).putLong("cloud-at", now).commit();
            getTargetContext().getSharedPreferences(NewEpisodes.PREFS, 0).edit().clear().commit();
            ContinueWatching.Entry show = new ContinueWatching.Entry(); show.kind = "tv"; show.title = "Episode notice test";
            show.imdb = "tt0898266"; show.video = "tt0898266:10:1"; show.season = 10; show.episode = 1; show.tmdb = 1418;
            JSONArray videos = new JSONArray().put(episode(2, now - 60000)).put(episode(3, now + 60000))
                    .put(episode(4, now - NewEpisodes.WINDOW - 1)).put(episode(2, now - 60000));
            JSONArray recent = NewEpisodes.releases(show.imdb, videos, now);
            check(recent.length() == 1, "future, expired, and duplicate episodes are excluded");
            check(NewEpisodes.releases("tt999", videos, now).length() == 0, "another series cannot produce a notice");
            videos.put(new JSONObject().put("id", "tt0898266:10:5").put("season", 10).put("episode", 5).put("released", "2026-10-20"));
            check(NewEpisodes.releases(show.imdb, videos, now).length() == 1, "a date without a release time is not treated as a confirmed release");
            getTargetContext().getSharedPreferences(NewEpisodes.PREFS, 0).edit().putString("episodes:" + show.imdb, recent.toString()).commit();
            List<NewEpisodes.Notice> notices = NewEpisodes.cached(getTargetContext(), Collections.singletonList(show), now);
            check(notices.size() == 1 && notices.get(0).entry.video.equals("tt0898266:10:2"), "the notice identifies the exact new episode");
            show.season = 11; check(NewEpisodes.cached(getTargetContext(), Collections.singletonList(show), now).isEmpty(), "older episodes do not appear after later progress"); show.season = 10;
            show.episode = 2; show.position = 1000;
            check(NewEpisodes.cached(getTargetContext(), Collections.singletonList(show), now).isEmpty(), "started episodes remain in Continue Watching instead of notices");
            show.episode = 1; show.position = 0;
            ContinueWatching.markWatched(getTargetContext(), notices.get(0).entry, true);
            check(NewEpisodes.cached(getTargetContext(), Collections.singletonList(show), now).isEmpty(), "watched episodes do not produce notices");
            ContinueWatching.markWatched(getTargetContext(), notices.get(0).entry, false);
            NewEpisodes.dismiss(getTargetContext(), notices.get(0));
            check(NewEpisodes.cached(getTargetContext(), Collections.singletonList(show), now).isEmpty(), "dismissed notices remain hidden");
            JSONObject item = LibrarySync.item(show.imdb, "tv", show.title, "", true); item.put("noNotif", true);
            LibrarySync.store(getTargetContext(), new JSONArray().put(item));
            check(NewEpisodes.cached(getTargetContext(), NewEpisodes.followed(getTargetContext(), Collections.singletonList(show)), now).isEmpty(), "the Stremio notification preference is preserved");
            LibrarySync.store(getTargetContext(), new JSONArray());
            getTargetContext().getSharedPreferences(NewEpisodes.PREFS, 0).edit().remove("dismissed").commit();
            JSONObject metadata = new JSONObject().put("status", "Returning Series").put("videos", new JSONArray()
                    .put(episode(1, now - 200000)).put(episode(2, now - 60000)).put(episode(3, now + 60000)));
            JSONObject timeline = NewEpisodes.timeline(show.imdb, metadata, now);
            getTargetContext().getSharedPreferences(NewEpisodes.PREFS, 0).edit().putString("timeline:" + show.imdb, timeline.toString()).commit();
            show.episode = 2; show.video = "tt0898266:10:2"; show.completed = true;
            List<NewEpisodes.Notice> waiting = NewEpisodes.retained(getTargetContext(), Collections.singletonList(show), now);
            check(waiting.size() == 1 && waiting.get(0).entry.video.equals("tt0898266:10:3"), "finishing the latest released episode keeps the series with its next release");
            check(!NewEpisodes.retained(getTargetContext(), Collections.singletonList(show), now + 120000).get(0).waiting, "a released episode replaces the waiting state");
            notices = NewEpisodes.cached(getTargetContext(), Collections.singletonList(show), now + 120000);
            check(notices.size() == 1 && notices.get(0).entry.video.equals("tt0898266:10:3"), "the scheduled episode becomes a notice without waiting for another metadata request");
            getTargetContext().getSharedPreferences(NewEpisodes.PREFS, 0).edit().putString("timeline:" + show.imdb, NewEpisodes.timeline(show.imdb, metadata, now, show).toString()).commit();
            long later = now + NewEpisodes.WINDOW + 120000;
            check(NewEpisodes.cached(getTargetContext(), Collections.singletonList(show), later).isEmpty(), "an old release no longer appears as new");
            List<NewEpisodes.Notice> retained = NewEpisodes.retained(getTargetContext(), Collections.singletonList(show), later);
            check(retained.size() == 1 && !retained.get(0).waiting && retained.get(0).entry.video.equals("tt0898266:10:3"), "the series and correct next episode remain after its release notice expires");
            metadata.put("status", "Ended"); metadata.getJSONArray("videos").remove(2);
            getTargetContext().getSharedPreferences(NewEpisodes.PREFS, 0).edit().putString("timeline:" + show.imdb, NewEpisodes.timeline(show.imdb, metadata, now).toString()).commit();
            check(NewEpisodes.retained(getTargetContext(), Collections.singletonList(show), now).isEmpty(), "a confirmed series ending does not create a waiting card");
            metadata.put("status", "Returning Series");
            getTargetContext().getSharedPreferences(NewEpisodes.PREFS, 0).edit().putString("timeline:" + show.imdb, NewEpisodes.timeline(show.imdb, metadata, now).toString()).commit();
            check(NewEpisodes.retained(getTargetContext(), Collections.singletonList(show), now).size() == 1, "an ongoing series remains visible when the next date is unknown");
            item = LibrarySync.item(show.imdb, "tv", show.title, "", true);
            item.getJSONObject("state").put("video_id", show.video).put("flaggedWatched", 1).put("timeOffset", 0).put("lastWatched", java.time.Instant.ofEpochMilli(now).toString());
            LibrarySync.store(getTargetContext(), new JSONArray().put(item));
            List<ContinueWatching.Entry> imported = NewEpisodes.followed(getTargetContext(), Collections.emptyList());
            check(imported.size() == 1 && imported.get(0).completed, "the full Stremio library retains a finished episode with zero resume offset");
            check(NewEpisodes.retained(getTargetContext(), imported, now).size() == 1, "the imported series remains on Home while waiting");
            item.put("removed", true).put("temp", true); LibrarySync.store(getTargetContext(), new JSONArray().put(item));
            check(NewEpisodes.retained(getTargetContext(), NewEpisodes.followed(getTargetContext(), Collections.emptyList()), now).size() == 1,
                    "recent temporary Stremio watch history remains visible without a saved-library action");
            item.put("temp", false); LibrarySync.store(getTargetContext(), new JSONArray().put(item));
            check(NewEpisodes.followed(getTargetContext(), Collections.emptyList()).isEmpty(), "an explicit library removal remains effective");
            LibrarySync.store(getTargetContext(), new JSONArray());
            getTargetContext().getSharedPreferences(NewEpisodes.PREFS, 0).edit().remove("timeline:" + show.imdb).commit();
            show.episode = 1; show.video = "tt0898266:10:1"; show.completed = false;
            ContinueWatching.record(getTargetContext(), show, 90000, 1200000, false);
            ActivityMonitor started = addMonitor(HomeActivity.class.getName(), null, false);
            getTargetContext().startActivity(new Intent(getTargetContext(), HomeActivity.class).setAction(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            home = (HomeActivity) waitForMonitorWithTimeout(started, 20000); removeMonitor(started);
            if (home == null) throw new AssertionError("Home did not open");
            view = (HomeView) value(home, "home");
            await(() -> {
                HomeActivity visible = HomeActivity.visible.get();
                if (visible == null) return false;
                home = visible; view = (HomeView) value(home, "home");
                for (Object itemNotice : (List<?>) value(view, "episodeNotices"))
                    if (((NewEpisodes.Notice) itemNotice).entry.video.equals("tt0898266:10:2")) return true;
                return false;
            }, "Home loads the cached notice without a network request");
            await(() -> value(view, "opening") == null || !((Opening) value(view, "opening")).playing(), "the opening finishes before navigation input");
            key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_DOWN);
            await(() -> view.zone == HomeView.Z_NEWS, "two Down presses select the notice below Resume");
            capture("new-episode-home.png"); addMonitor(monitor); key(KeyEvent.KEYCODE_DPAD_CENTER);
            await(() -> opened.get() != null, "Select opens the series page");
            check("tt0898266:10:2".equals(opened.get().getStringExtra("selected_video")) && !opened.get().getBooleanExtra("auto_play", false), "the notice selects its episode without automatic playback");
            key(KeyEvent.KEYCODE_DPAD_DOWN);
            await(() -> view.zone == HomeView.Z_ROW && ((Integer) value(((List<?>) value(view, "row")).get((Integer) value(view, "rowIndex")), "kind")) == 2, "Down selects the new episode card");
            SystemClock.sleep(1000); capture("new-episode-content.png"); key(KeyEvent.KEYCODE_BACK); key(KeyEvent.KEYCODE_BACK);
            await(() -> view.zone == HomeView.Z_PILL, "Back returns to Search");
            key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_DOWN);
            runOnMainSync(() -> { try { java.lang.reflect.Method method = HomeView.class.getDeclaredMethod("onLongPress"); method.setAccessible(true); method.invoke(view); } catch (Exception e) { throw new RuntimeException(e); } });
            await(() -> ((List<?>) value(view, "episodeNotices")).isEmpty() && view.zone == HomeView.Z_PILL, "dismissal removes the notice and preserves a valid focus");
            show.episode = 2; show.video = "tt0898266:10:2";
            ContinueWatching.finished(getTargetContext(), show, null);
            metadata.put("videos", new JSONArray().put(episode(1, now - 200000)).put(episode(2, now - 60000)).put(episode(3, now + 3600000)));
            getTargetContext().getSharedPreferences(NewEpisodes.PREFS, 0).edit().putString("timeline:" + show.imdb, NewEpisodes.timeline(show.imdb, metadata, now).toString()).commit();
            runOnMainSync(() -> { try { java.lang.reflect.Method method = HomeView.class.getDeclaredMethod("refreshRow"); method.setAccessible(true); method.invoke(view); } catch (Exception e) { throw new RuntimeException(e); } });
            await(() -> ((List<?>) value(view, "waitingSeries")).size() == 1, "Home retains the completed series while its next episode is unavailable");
            runOnMainSync(() -> { try {
                java.lang.reflect.Method method = HomeView.class.getDeclaredMethod("toRow"); method.setAccessible(true); method.invoke(view);
                List<?> cards = (List<?>) value(view, "row");
                for (int i = 0; i < cards.size(); i++) if ("waiting:tt0898266".equals(value(cards.get(i), "key"))) {
                    Field index = HomeView.class.getDeclaredField("rowIndex"); index.setAccessible(true); index.set(view, i);
                    java.lang.reflect.Method aim = HomeView.class.getDeclaredMethod("aimRow", boolean.class); aim.setAccessible(true); aim.invoke(view, true);
                }
            } catch (Exception e) { throw new RuntimeException(e); } });
            await(() -> {
                Object card = ((List<?>) value(view, "row")).get((Integer) value(view, "rowIndex"));
                return "Waiting for the next episode".equals(value(card, "kicker")) && ((String) value(card, "meta")).startsWith("Next episode:");
            }, "the retained card explains the waiting state and next release date");
            key(KeyEvent.KEYCODE_DPAD_UP);
            await(() -> view.zone == HomeView.Z_HERO, "Up selects the waiting series action");
            key(KeyEvent.KEYCODE_DPAD_RIGHT);
            check(((Integer) value(view, "heroIndex")) == 0, "a repeated action cannot receive a second focus");
            SystemClock.sleep(1000); capture("waiting-for-episode.png"); opened.set(null); key(KeyEvent.KEYCODE_DPAD_CENTER);
            await(() -> opened.get() != null && "tt0898266:10:3".equals(opened.get().getStringExtra("selected_video"))
                    && !opened.get().getBooleanExtra("auto_play", false), "the waiting card opens the scheduled episode without starting playback");
            output.putString("stream", "Passed " + checks + " new-episode release and Home checks.\n");
        } catch (Throwable error) { output.putString("stream", "FAILED after " + checks + ": " + error.getClass().getSimpleName() + ": " + error.getMessage() + "\n"); }
        finally { removeMonitor(monitor); runOnMainSync(() -> { if (home != null) home.finish(); }); finish(output.getString("stream", "").startsWith("Passed") ? -1 : 0, output); }
    }
}
