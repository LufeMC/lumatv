package io.freemytv;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import java.lang.reflect.Field;
import java.util.Collections;

/** Verify artwork release and restoration across search, title, and playback screens. */
public class ArtworkLifecycleTests extends Instrumentation {
    private SearchActivity search;
    private SourcesActivity title;
    private PlayerActivity player;
    private int checks;
    private Object value(Object owner, String name) throws Exception { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
    private void main(Runnable action) {
        Throwable[] error = {null}; runOnMainSync(() -> { try { action.run(); } catch (Throwable failure) { error[0] = failure; } });
        if (error[0] != null) throw new AssertionError(error[0]);
    }
    private interface Condition { boolean get() throws Exception; }
    private void await(Condition condition, String label) throws Exception {
        long end = SystemClock.elapsedRealtime() + 20000;
        while (SystemClock.elapsedRealtime() < end) {
            boolean[] passed = {false}; main(() -> { try { passed[0] = condition.get(); } catch (Exception error) { throw new RuntimeException(error); } });
            if (passed[0]) { checks++; return; } SystemClock.sleep(50);
        }
        throw new AssertionError(label);
    }
    private int images(View view) {
        if (view instanceof ImageView) return ((ImageView) view).getDrawable() == null ? 0 : 1;
        int count = 0; if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) count += images(((ViewGroup) view).getChildAt(i));
        return count;
    }
    private Drawable backdrop() throws Exception { return ((ImageView) value(value(title, "page"), "artwork")).getDrawable(); }
    @Override public void onCreate(Bundle args) { super.onCreate(args); if (android.app.Application.getProcessName().equals("io.freemytv:home")) start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            getUiAutomation(); WatchSnapshot.capture(getTargetContext());
            search = (SearchActivity) startActivitySync(new Intent(getTargetContext(), SearchActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            main(() -> { try { ((EditText) value(search, "query")).setText("The Big Bang Theory"); } catch (Exception error) { throw new RuntimeException(error); } });
            await(() -> images((View) value(search, "content")) >= 1, "search posters load");
            title = (SourcesActivity) startActivitySync(new Intent(getTargetContext(), SourcesActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("title_id", 1418).putExtra("title_kind", "tv").putExtra("title_name", "The Big Bang Theory"));
            await(() -> backdrop() != null, "title artwork loads");
            await(() -> !(Boolean) value(search, "picturesActive") && images((View) value(search, "content")) == 0, "inactive search releases every poster");
            java.io.File fixture = new java.io.File(getTargetContext().getCacheDir(), "artwork-test.mkv");
            try (java.io.InputStream input = getContext().getAssets().open("sample.mkv")) { java.nio.file.Files.copy(input, fixture.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            AddonStreams.Source source = new AddonStreams.Source(); source.url = android.net.Uri.fromFile(fixture).toString(); source.quality = 360;
            PlaybackSession.current = new PlaybackSession("tt0000998", "Artwork test", Collections.singletonList(source), 360);
            player = (PlayerActivity) startActivitySync(new Intent(getTargetContext(), PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> (Boolean) value(player, "sourceValidated"), "fixture video and audio start");
            await(() -> backdrop() == null, "inactive title releases its backdrop");
            main(() -> player.finish());
            await(() -> title.hasWindowFocus() && backdrop() != null, "title artwork returns after playback"); player = null;
            main(() -> title.finish());
            await(() -> search.hasWindowFocus() && images((View) value(search, "content")) >= 1, "search posters return after Back"); title = null;
            await(() -> ((EditText) value(search, "query")).getText().toString().equals("The Big Bang Theory"), "query remains unchanged");
            result.putString("stream", "Passed " + checks + " artwork lifecycle checks.\n");
        } catch (Throwable error) { result.putString("stream", "FAILED after " + checks + ": " + error.getMessage() + "\n"); }
        finally {
            main(() -> { if (player != null) player.finish(); if (title != null) title.finish(); if (search != null) search.finish(); }); waitForIdleSync(); SystemClock.sleep(500);
            try { WatchSnapshot.restore(getTargetContext()); } catch (Exception ignored) {}
            new java.io.File(getTargetContext().getCacheDir(), "artwork-test.mkv").delete();
            finish(result.getString("stream", "").startsWith("Passed") ? -1 : 0, result);
        }
    }
}
