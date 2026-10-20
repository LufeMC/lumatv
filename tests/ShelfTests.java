package io.freemytv;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Run against an isolated preference file on an Android TV. */
public class ShelfTests extends Instrumentation {
    private int checks;
    private void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        checks++;
    }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        Context c = new ContextWrapper(getTargetContext()) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences("shelf-test-isolated", mode);
            }
        };
        SharedPreferences prefs = c.getSharedPreferences("home-apps", 0);
        try {
            prefs.edit().clear().commit();
            List<String> defaults = HomeApps.shelfPackages(c);
            check(!defaults.isEmpty() && defaults.size() <= 8, "default apps");
            ArrayList<String> reversed = new ArrayList<>(defaults);
            Collections.reverse(reversed);
            check(HomeApps.saveShelf(c, reversed) && HomeApps.shelfPackages(c).equals(reversed), "save order");
            check(!HomeApps.saveShelf(c, Arrays.asList(defaults.get(0), defaults.get(0))), "reject duplicate");
            check(HomeApps.shelfPackages(c).equals(reversed), "invalid save preserves order");
            check(!HomeApps.saveShelf(c, Collections.nCopies(9, defaults.get(0))), "reject more than eight");
            check(!HomeApps.saveShelf(c, Arrays.asList("missing.example.app")), "reject missing app");
            check(HomeApps.saveShelf(c, Collections.emptyList()) && HomeApps.shelfPackages(c).isEmpty(), "save empty list");
            boolean removedVisible = false;
            for (HomeApps.Item item : HomeApps.others(c)) if (defaults.get(0).equals(item.pkg)) removedVisible = true;
            check(removedVisible, "removed app remains in All Apps");
            prefs.edit().putString("packages", "invalid JSON").commit();
            check(HomeApps.shelfPackages(c).equals(defaults), "recover corrupt preferences");
            prefs.edit().putString("packages", new org.json.JSONArray(Arrays.asList(defaults.get(0), defaults.get(0), "missing.example.app")).toString()).commit();
            check(HomeApps.shelfPackages(c).equals(Arrays.asList(defaults.get(0))), "filter duplicates and missing apps");
            List<HomeApps.Item> available = HomeApps.editableApps(c);
            check(!available.isEmpty(), "load installed apps");
            for (HomeApps.Item item : available) {
                if (!defaults.contains(item.pkg)) {
                    check(HomeApps.saveShelf(c, Arrays.asList(item.pkg)), "save another installed app");
                    List<HomeApps.Item> shelf = HomeApps.shelf(c, false);
                    check(shelf.size() == 1 && shelf.get(0).pkg.equals(item.pkg) && shelf.get(0).tile != null, "render added app");
                    break;
                }
            }
            result.putString("stream", "Passed " + checks + " shelf checks.\n");
            finish(-1, result);
        } catch (Throwable error) {
            result.putString("stream", "FAILED after " + checks + " checks: " + error.toString() + "\n");
            finish(0, result);
        } finally { prefs.edit().clear().commit(); }
    }
}
