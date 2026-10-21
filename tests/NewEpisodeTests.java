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
            ContinueWatching.Entry show = new ContinueWatching.Entry(); show.kind = "tv"; show.title = "The Big Bang Theory";
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
            check(NewEpisodes.followed(getTargetContext(), Collections.singletonList(show)).isEmpty(), "the Stremio notification preference is preserved");
            LibrarySync.store(getTargetContext(), new JSONArray());
            getTargetContext().getSharedPreferences(NewEpisodes.PREFS, 0).edit().remove("dismissed").commit();
            ContinueWatching.record(getTargetContext(), show, 90000, 1200000, false);
            home = (HomeActivity) startActivitySync(new Intent(getTargetContext(), HomeActivity.class).setAction(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            view = (HomeView) value(home, "home");
            await(() -> ((List<?>) value(view, "episodeNotices")).size() == 1, "Home loads the cached notice without a network request");
            SystemClock.sleep(4000); key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_DOWN);
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
            output.putString("stream", "Passed " + checks + " new-episode release and Home checks.\n");
        } catch (Throwable error) { output.putString("stream", "FAILED after " + checks + ": " + error.getClass().getSimpleName() + ": " + error.getMessage() + "\n"); }
        finally { removeMonitor(monitor); runOnMainSync(() -> { if (home != null) home.finish(); }); finish(output.getString("stream", "").startsWith("Passed") ? -1 : 0, output); }
    }
}
