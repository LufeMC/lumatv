package io.freemytv;

import java.util.List;
import org.json.JSONObject;

/** Use aired episodes in season order. Specials remain in their own sequence. */
final class EpisodeSequence {
    interface Loader { JSONObject get(String path) throws Exception; }
    static SeriesEpisodes.Episode next(ContinueWatching.Entry current) throws Exception {
        return next(current, TitleSearch::get, SeriesEpisodes.today());
    }
    static SeriesEpisodes.Episode next(ContinueWatching.Entry current, Loader loader, String today) throws Exception {
        if (current == null || !"tv".equals(current.kind) || current.tmdb <= 0 || current.episode <= 0) return null;
        if (EpisodeMap.available(current.imdb)) {
            for (int number : EpisodeMap.seasons(current.imdb)) {
                if (current.season == 0 ? number != 0 : number == 0 || number < current.season) continue;
                for (SeriesEpisodes.Episode e : EpisodeMap.episodes(current.imdb, number, java.util.Collections.emptyList(), today))
                    if (!e.future && (number != current.season || e.number > current.episode)) return e;
            }
            return null;
        }
        String base = "/tv/" + current.tmdb;
        String language = "?language=" + (Ui.en ? "en-US" : "pt-BR");
        List<SeriesEpisodes.Episode> episodes = SeriesEpisodes.episodes(
                loader.get(base + "/season/" + current.season + language).optJSONArray("episodes"), current.season, today);
        for (SeriesEpisodes.Episode episode : episodes) if (episode.number > current.episode && !episode.future) return episode;
        if (current.season == 0) return null;
        List<SeriesEpisodes.Season> seasons = SeriesEpisodes.seasons(loader.get(base).optJSONArray("seasons"));
        for (SeriesEpisodes.Season season : seasons) {
            if (season.number <= current.season) continue;
            episodes = SeriesEpisodes.episodes(loader.get(base + "/season/" + season.number + language).optJSONArray("episodes"), season.number, today);
            for (SeriesEpisodes.Episode episode : episodes) if (!episode.future) return episode;
        }
        return null;
    }
}
