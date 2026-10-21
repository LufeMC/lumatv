package io.freemytv;

import android.os.Bundle;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONArray;
import org.json.JSONObject;

/** Verify a remote progress write and restore the original watch state. */
public class StremioProgressTests extends TvTest {
    private int checks;
    private void check(boolean pass, String name) { if (!pass) throw new AssertionError(name); checks++; }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    private JSONObject read(String account, String id) throws Exception {
        JSONObject body = new JSONObject().put("authKey", account).put("collection", "libraryItem").put("ids", new JSONArray().put(id)).put("all", false);
        try {
            JSONArray result = MediaHttp.json(LibrarySync.API + "datastoreGet", body, 10000).getJSONArray("result");
            for (int i = 0; i < result.length(); i++) if (id.equals(result.getJSONObject(i).optString("_id"))) return result.getJSONObject(i);
            throw new Exception("missing_library_item");
        } finally { body.remove("authKey"); }
    }
    @Override public void onStart() {
        Bundle result = new Bundle(); String account = ""; JSONObject baseline = null;
        File backup = new File(getTargetContext().getFilesDir(), "remote-progress-test-backup.json");
        boolean written = false, restored = false;
        try {
            account = AddonStore.account(getTargetContext()); check(!account.isEmpty(), "the saved account is available");
            check(StremioAccount.fetch(account).length() > 0, "the real account returns its add-ons");
            if (backup.isFile()) {
                JSONObject prior = new JSONObject(new String(Files.readAllBytes(backup.toPath()), StandardCharsets.UTF_8));
                prior.put("_mtime", LibrarySync.now()); LibrarySync.put(account, prior);
                check(read(account, prior.getString("_id")).getJSONObject("state").toString().equals(prior.getJSONObject("state").toString()), "an interrupted test restores its saved watch state");
                backup.delete();
            }
            baseline = read(account, "tt0898266");
            check(baseline.optJSONObject("state") != null, "the reported show's server watch state is readable");
            Files.write(backup.toPath(), baseline.toString().getBytes(StandardCharsets.UTF_8));
            JSONObject changed = new JSONObject(baseline.toString()), state = baseline.getJSONObject("state");
            long marker = state.optLong("timeOffset") + 1;
            LibrarySync.progress(changed, state.optString("video_id"), marker, state.optLong("duration"), 0, false, null);
            written = true; LibrarySync.put(account, changed);
            JSONObject saved = read(account, baseline.getString("_id"));
            check(saved.getJSONObject("state").optLong("timeOffset") == marker, "the production upload reaches the real server");
            check(saved.getJSONObject("state").optString("video_id").equals(state.optString("video_id")), "the write preserves episode identity");
            check(saved.getJSONObject("state").optString("watched").equals(state.optString("watched")), "the write preserves watched episode flags");
        } catch (Throwable error) { result.putString("stream", "FAILED Stremio progress: " + error.getClass().getSimpleName() + ": " + error.getMessage() + "\n"); }
        finally {
            if (written && baseline != null) {
                for (int attempt = 0; attempt < 3 && !restored; attempt++) try {
                    JSONObject original = new JSONObject(baseline.toString()).put("_mtime", LibrarySync.now());
                    LibrarySync.put(account, original);
                    restored = read(account, original.getString("_id")).getJSONObject("state").toString().equals(baseline.getJSONObject("state").toString());
                } catch (Exception ignored) {}
                if (restored) { backup.delete(); checks++; }
                else result.putString("stream", "FAILED: The original remote watch state needs recovery from its saved backup.\n");
            }
            if (!result.containsKey("stream") && restored) result.putString("stream", "Passed " + checks + " real Stremio progress checks. Original server watch state restored.\n");
            if (!result.containsKey("stream")) result.putString("stream", "FAILED: The progress round trip did not finish.\n");
            finish(result.getString("stream").startsWith("Passed") ? -1 : 0, result);
        }
    }
}
