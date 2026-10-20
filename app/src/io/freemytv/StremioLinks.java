package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

final class StremioLinks {
    static final String PACKAGE = "com.stremio.one";
    private StremioLinks() {}

    static boolean installed(Context c) {
        return HomeApps.launchIntent(c.getPackageManager(), PACKAGE) != null;
    }

    static Uri titleUri(String kind, String imdb, boolean autoPlay) {
        if (!("movie".equals(kind) || "tv".equals(kind)) || imdb == null || !imdb.matches("tt[0-9]+"))
            throw new IllegalArgumentException("title");
        boolean movie = "movie".equals(kind);
        Uri.Builder link = new Uri.Builder().scheme("stremio").authority("")
                .appendPath("detail").appendPath(movie ? "movie" : "series").appendPath(imdb);
        if (movie) link.appendPath(imdb).appendQueryParameter("autoPlay", Boolean.toString(autoPlay));
        return link.build();
    }

    static Intent titleIntent(TitleSearch.Title title, String imdb, boolean autoPlay) {
        return new Intent(Intent.ACTION_VIEW, titleUri(title.kind, imdb, autoPlay)).setPackage(PACKAGE);
    }

    static Uri videoUri(String kind, String imdb, String video) {
        if ("tv".equals(kind) && video != null && video.matches(java.util.regex.Pattern.quote(imdb) + ":[0-9]+:[0-9]+"))
            return new Uri.Builder().scheme("stremio").authority("").appendPath("detail").appendPath("series").appendPath(imdb).appendPath(video).build();
        return titleUri(kind, imdb, true);
    }

    static Intent searchIntent(String query) {
        if (query == null || query.trim().isEmpty()) throw new IllegalArgumentException("query");
        return new Intent(Intent.ACTION_VIEW, new Uri.Builder().scheme("stremio").authority("")
                .appendPath("search").appendQueryParameter("search", query.trim()).build()).setPackage(PACKAGE);
    }
}
