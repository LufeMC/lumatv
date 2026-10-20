# LumaTV improvements

55 in total, all approved for the build (October 20). Nothing here is implemented yet; coding starts when you say so.

## Your list (21)

1. Rename to LumaTV by Madfer Operations, changing only the name shown on the TV. Keep the internal app ID (io.freemytv) so installed TVs still update.
2. App icon and TV banner from the Flora mark.
3. Type search from your phone by scanning a QR code.
4. Remap the remote's app buttons (TCL Netflix and others stop working with the stock home screen gone).
5. Preload the next episode about 2 minutes before the end, and discard it if you don't continue.
6. "Still watching?" after 3 episodes in a row, only after midnight.
7. Sync with your Stremio account: watchlist, plus progress on your phone and other TVs.
8. People search and "More like this", from TMDB.
9. A cute Flora opening (dot eyes, a smile, a little tongue).
10. Match the TV's refresh rate to the video (24 fps content judders on a 60 Hz panel).
11. Skip sources the TV can't play (Dolby Vision, AV1) before trying them.
12. Remember the add-on and release that worked for each show.
13. Start fetching the source as soon as the home Resume chip is selected.
14. One Continue Watching card per show, even when Stremio and Netflix both have it.
15. Quick panel on a long press of the remote's settings button: sleep timer, picture mode, Bluetooth headphones.
16. Default audio and subtitle language in Settings.
17. Updates from GitHub, without a computer.
18. Keep the shelf order, settings and add-ons the same on both TVs.
19. Graceful failure: pick up playback after the TV kills the app, and show clear offline and no-TMDB-key states.
20. Search your Stremio add-ons' own catalogs too, not only TMDB.
21. Every app in the minimal white-logo style, not just the eight in the mockups: Sun NXT, Globoplay and anything else you add. Make the white logo automatically from each app's own TV banner, the same way the mockup logos were made (separate the logo from its background color). When a banner doesn't convert cleanly, show the app's name in the same style instead of a broken logo.

# From the deep analysis (30) and subtitle sync (4)

Picks 1 and 2 (repo safety and the signing key) go with the GitHub publish: the doc cleanup happens before the first push, and the build changes come with the rest of the code.

## Top picks

**1. Get the code ready for your public GitHub repo**
- **What:** Move household details out of the tracked docs into a local file that git ignores. That covers the TV network addresses, /Users/luis paths, "Luis's Mac" and the city. Then:
  - add a secret scanner that runs before each push;
  - commit the work as a few logical commits on top of the original;
  - push to your repo as `origin` and keep the original repo as `upstream`;
  - point the README, docs and credits links at your repo.
- **Why:** Your version exists only in this folder (14 changed and 101 new files). As written, the docs would publish which home addresses are TVs with ADB (remote debugging) open on port 5555. Keeping the original as `upstream` lets you pull in its 66 newer commits instead of copying fixes by hand.
- **Effort:** S
- **Evidence:** TV-DEPLOYMENT.md:3, :10, :13, :40-41; PERFORMANCE.md:3; SEEK-PREVIEWS.md:3; README.md:38, docs/index.html:61, MediaCredits.java:21 (links to the original); .github/workflows/pages.yml (publishes docs/). Your git commit email is a personal Gmail address, so consider switching to GitHub's noreply address before the first push.

**2. Protect the app's signing key**
- **What:** Store the keystore password in the macOS Keychain. Make the build fail, rather than quietly create a new key, when the keystore is missing. Check the signing certificate's fingerprint after every build. Keep an encrypted backup somewhere other than this Mac.
- **Why:** The TVs only accept updates signed with the same key. A new key forces an uninstall, which wipes the Stremio connection, Continue Watching and all settings. A fresh clone of your public repo would create a new key without any warning, and the current password, "freemytv", is already public in the original repo.
- **Effort:** S
- **Evidence:** build.sh:75-79; tests/build.sh:15; .gitignore:3; TV-DEPLOYMENT.md:112-115; outputs/FreeMyTV-build-backup.tar.gz holds the keystore unencrypted. The keystore is PKCS12, so change the password with `keytool -storepasswd` only; `-keypasswd` doesn't work on that format.

**3. Stop the rewind memory from starving 4K playback**
- **What:** The player keeps the last 15 s in memory for rewinding, and that shares one 20 MB limit with the video it loads ahead. Size the rewind part from the source's bitrate (data rate) so it never takes more than about a quarter of the limit, and enlarge the disk cache so rewinds can come from disk.
- **Why:** Above about 11 Mbps, which covers most 4K web releases and every remux (the app tries 4K first), the rewind memory alone fills the limit. The video loaded ahead then keeps dropping to about half a second, so any Wi-Fi hiccup becomes a stall, and a 12 s stall switches to a different source.
- **Effort:** S
- **Evidence:** PlayerActivity.java:329-330 (20 MiB, 15 s back buffer); PlaybackPlan.java:12; PlayerActivity.java:101; MediaCache.java:22; Media3 (the player library) 1.8.1 drops the rewind memory only below 500 ms of video ahead. This hasn't been measured on the TVs, so add a high-bitrate test first.

**4. Fix the lost selection after a finished title leaves Continue Watching**
- **What:** When the selected Continue Watching card disappears, move the selection to the next card, or to the app row if the list is empty. Also make visible the "Loading…" text that the code sets but never shows.
- **Why:** You finish a movie, or an episode without starting the next, and press Back. Then nothing on home looks selected, the arrow keys move an invisible menu, and OK opens whichever shelf app was last selected.
- **Effort:** S
- **Evidence:** HomeView.java:444 (only repairs the Picks row), :1693, :1810-1817, :1894-1897; ContinueRow.java:85-90, :46, :126; triggered by PlayerActivity.java:392, ContinueWatching.java:54 and :120.

**5. Make the selected item obvious in the day look**
- **What:** Give the Search pill, which is selected first on home, the same grow-and-shadow effect the tabs get. Restyle the title page's main button so that at rest it no longer looks selected. Try 2-3 styles before picking one.
- **Why:** By day, the Search pill's selected and unselected states differ by 1.03:1 contrast, which is invisible. On the title page, "Resume" looks selected while the selection is actually on an episode card, so you can't tell where the remote is.
- **Effort:** S
- **Evidence:** HomeView.java:2106-2113 vs :2016-2028; HomeView.java:117, :389; TvWidgets.java:28 vs :40-43; outputs/FreeMyTV-series.png

**6. Take the TMDB key and home location out of the app file**
- **What:** Stop compiling the TMDB token (your movie-database access key), coordinates and city into the APK. Enter them instead in Settings or from the phone QR page (#3), stored encrypted on the TV. Add a release build mode that refuses to build if they are filled in.
- **Why:** Updates from GitHub (#17) mean APKs anyone can download, and today every APK contains your token and coordinates accurate to about 11 m. If someone abuses the token and TMDB revokes it, search and home picks break on both TVs. It also turns "no TMDB key" (#19) into a simple "not set yet" state.
- **Effort:** M
- **Evidence:** build.sh:44-60; Recs.java:130, :564; TitleSearch.java:84; HomeData.java:37-38; Ui.java:27; AddonStore.java:20-66 (encryption pattern to reuse); TV-DEPLOYMENT.md:105-109. Do this before the first public APK.

**7. "Up next" in Continue Watching**
- **What:**
  - When an episode finishes, replace the show's card with "Up next · S1 E5".
  - Count an episode as watched at 95% or with 2 minutes left, not only in the last second.
  - Point the title page's Play button at the next episode.
  - Later, bring caught-up shows back when a new episode airs.
- **Why:** Today, finishing an episode makes the whole show vanish from Continue Watching, and leaving during the credits leaves a card that resumes the credits. Most series nights end with searching for the show again.
- **Effort:** L in full; the core (Up next card, 95% rule, Play label) is M.
- **Evidence:** ContinueWatching.java:49, :54, :120; PlayerActivity.java:389-392, :500-507, :682; SourcesActivity.java:61, :203-209; tests/ProgressDataTests.java:55-59 (these tests need rewriting). Series watched in the Stremio app already move on to the next episode (stremio-core player.rs:1300-1309), so that part is mostly done.

**8. Let the TV sleep when the player is paused or finished**
- **What:** Keep the screen awake only while video is playing, buffering or counting down to the next episode, and come back paused after the TV has been in standby. Add "ends 23:41" next to the time and skip the pause card.
- **Why:** A paused or finished player keeps the panel lit on a still frame all night. The screensaver never starts, which risks burn-in and wastes power, and switching the TV on can start the sound by itself.
- **Effort:** S
- **Evidence:** PlayerActivity.java:124 (keep-awake is set once and never cleared), :202-208, :421, :389-396; https://developer.android.com/training/tv/playback/ambient-mode. There is also a bug to fix with it: after an episode ends, anything that stops and restarts the player replays it from 0:00 because `wanted` stays true. That includes standby, and the screensaver once this ships.

**9. Only home-screen crashes should give the TV back to the stock launcher**
- **What:** The crash safety net counts every crash in the shared process, including the player's. Count only crashes that happen while the home screen or screensaver is showing. Record whether the switch back worked, and give the fallback screen buttons: Try again, LumaTV Settings and TV Settings.
- **Why:** Three player crashes in five minutes, for example while retrying a bad stream, would bring back the stock launcher with its ads until someone finds Settings > Home Screen. If switching back fails, the fallback screen has nothing to press.
- **Effort:** S-M once trimmed (no "player safe mode", no app list).
- **Evidence:** Watchdog.java:21-37; AndroidManifest.xml:61, :77-95; PlayerActivity.java:118; HomeActivity.java:24-36; HomeMode.java:237-238. Test the counting rule on its own, because throwing in the :home process kills the test. No player crash has been seen yet; the risk is inferred from the code.

**10. Explain dead ends and offer a way forward**
- **What:** When no source is found or all of them fail, say why, using counts the app already collects: "2 of 5 add-ons didn't answer", "14 results are torrents, which this player can't play". Then offer focused buttons: Try again, Try more sources (the next 12 of up to 300 found) and Open in Stremio.
- **Why:** Today every dead end says "No sources are available. Try again later." next to a button labelled "Play", or leaves a black screen with the selection on Play/Pause. "Try another source" also does nothing once the list runs out.
- **Effort:** S-M
- **Evidence:** SourcesActivity.java:271-273 (counted but never shown), :329-333; PlayerActivity.java:315-319 (the Portuguese text promises "choose another quality"), :481, :717; PlaybackSession.java:31 (limit of 12) vs SourcesActivity.java:272 (300). Check the Stremio episode link on a TV before relying on it.

**11. Ride out Wi-Fi drops without switching release**
- **What:** Once a source has started playing, retry that same source after a network error, waiting 1, 2, then 4 s, for up to about 30 s. While the TV has no network, show "Waiting for network…" and pause the timers that switch sources.
- **Why:** An outage longer than the video loaded ahead (about 15-45 s) currently burns through all 12 queued sources in seconds and ends at "Playback is unavailable". Staying on the same release keeps the same quality, audio tracks and subtitle timing when Wi-Fi comes back.
- **Effort:** M
- **Evidence:** PlayerActivity.java:328 (no retries), :369-375, :480-486 (every error moves to the next source), :93, :100-101 (timers ignore the network), :488-493 (an existing same-source retry path to reuse); nothing in app/src checks network state. Asking the add-on again for a fresh link and holding a Wi-Fi lock are optional. Update PLAYBACK.md:166-169 to match.

**12. Skip recap, and "Next episode" when the credits start**
- **What:** Use the recap and credits times that IntroDB (the community intro-timing database) already sends with every intro lookup. Offer "Skip recap", start the Next-episode countdown when the credits begin, and count the episode as watched at that point.
- **Why:** The credits, plus a countdown that only starts after the file ends, are the longest wait in a binge. Stopping during the credits also leaves the episode marked unfinished.
- **Effort:** M
- **Evidence:** IntroMarkers.java:23 (reads only the intro), :59 (the full response is already cached), :17; PlayerActivity.java:389-396, :693-697; ContinueWatching.java:54; https://introdb.app/docs/api. Accept a credits time only if it falls in the last 15-20% of the runtime, keep it behind the cancellable countdown, and skip the extra Settings toggle.

**13. Pick the right add-on subtitle automatically, and switch subtitles without restarting the player**
- **What:** Load add-on subtitles while sources are being found. When the file has no subtitle in your language built in, attach the best 1-3 add-on subtitles from the first frame, and remember your pick for each show.
- **Why:** If you need Portuguese subtitles on an English release, you open the Subtitles menu on every episode, and each pick tears down the player for several seconds of black screen.
- **Effort:** M
- **Evidence:** PlayerActivity.java:85 (the choice is lost on the next episode, :707-713), :405-411, :427, :570-574 (full reopen), :561-564; AddonSubtitles.java:79-81; MediaDataSource.java:19, :39. Build it with default languages (#16). A subtitle that fails to download must not fail the video (PlayerActivity.java:328).

**14. Only promote "Watch on Netflix" for services you pay for**
- **What:** Add a "My services" list in Settings, with every installed service on by default so you switch off the ones you don't pay for. Use it for the title-page button and the home picks, and optionally check whether the selected season is actually on that service.
- **Why:** The app treats "installed" as "subscribed". The button that is selected by default, and the hourly picks, can send you to a sign-up screen in an app you don't pay for.
- **Effort:** M
- **Evidence:** WatchOptions.java:121, :126-133; SourcesActivity.java:109-115, :211-219; Recs.java:377, :478, :539-540; PLAYBACK.md:103, :218. Don't guess the default from Watch Next entries: Prime Video published none (TV-DEPLOYMENT.md:337). Store the list so TV sync (#18) carries it.

## Subtitles that stay in sync (4)

Only add-on subtitles (OpenSubtitles and similar) go out of sync; subtitles built into the video file are already timed to it. Build the first three together, and add the fourth if mismatches still happen.

15. **Prefer subtitles made for this exact file.** The app already sends each file's fingerprint to the subtitle add-ons (AddonSubtitles.java:44-45), so some return subtitles timed to that release. Rank those first. Effort: S.
16. **Manual timing control, remembered.** The "Timing" control on the audio and subtitles screen is designed but not in the code. Save the adjustment per release so it's fixed once. Effort: S.
17. **Automatic drift fix.** Subtitles that start fine and slowly drift usually come from a speed mismatch (25 fps subtitles on a 23.976 fps file). Compare when the last line appears against the video's length, and stretch the timing to match. Effort: S-M.
18. **True auto-sync.** Listen to the first few minutes of dialogue and line the subtitles up with when people speak, as the tools ffsubsync and alass do. This is possible because the player already decodes the audio itself. Shift only when confident, show what changed ("Subtitles moved 2.3 s earlier"), and make it one press to undo. Effort: M-L.

## Quick wins

- **Forced subtitles when subtitles are off:** some releases include a subtitle track only for foreign-language lines and on-screen signs, and today it never shows. Keeping subtitles enabled with no preferred language lets the player library pick these tracks automatically (PlayerActivity.java:335-337, :531-536). Make Off do this rather than adding a new setting.
- **Open the title page straight from home picks:** one Back then returns home, instead of landing on a Search screen with the title typed in, and the artwork shows right away (HomeView.java:1866-1870, :1887-1891; SearchActivity.java:140-141).
- **Stremio titles in home picks:** let one of the three hourly picks be a trending title you'd play through Stremio. Stremio entries outnumber Netflix 36 to 2 (TV-DEPLOYMENT.md:336). Only pick titles already released for home viewing, not ones still in cinemas (Recs.java:175, :379, :397; HomeView.java:1867, :1888).
- **Check the episode tag in filenames:** move sources whose filename names a different episode, or a two-episode file, to the back of the queue instead of playing them first (AddonStreams.java:104-107; PlaybackPlan.java:8-18). Reorder only, never remove, and don't mistake resolutions like 1920x1080 for episode numbers.
- **"What did they say?":** a short rewind (30 s or less) with subtitles off shows subtitles until playback is back where it was, as on Apple TV. Use built-in tracks only (PlayerActivity.java:661, :86-117).
- **Copy pass:** fix eight Portuguese/English mismatches:
  - "de há 12 min" (HomeView.java:1433, DreamView.java:787);
  - Search text that names a Search button that doesn't exist (SearchActivity.java:232, :245);
  - "1 results" (:254);
  - "TV aberta" vs "TV ao Vivo".
  Also hide the Live TV corner button when there's no Live TV app (HomeView.java:412, :1916).
- **Portuguese text fit and 24-hour clock (trimmed version):** let player buttons and season tabs grow so "Temporada 10" and "Cancelar reprodução automática" aren't cut off (TitlePage.java:103-106; PlayerActivity.java:171-175, :191). Follow the TV's 24-hour setting instead of tying the clock format to the language (Ui.java:129-140).
- **Settings contrast by day:** on these Android 11/12 TVs the Settings background falls back to a flat bright blue, and the dimming meant to keep white text readable is skipped. Values end up around 1.8:1 and the "tick Always allow" hint around 2.6:1. Apply the dimming that is already computed (Scene.java:89-90) and put the warning on an amber chip.
- **Show the home shelf before the All Apps scan:** post your shelf apps before every installed app's banner is redrawn (HomeView.java:1672-1686; HomeApps.java:303-327). Add a shelf-only timing first, because the 835 ms figure also waits for a thumbnail.

## Also worth doing

- **Per-episode watched history (M):** checkmarks and "Resume at 12:34" on episode cards, Play goes to the first unwatched episode, and Stremio's watched flags are imported (PlayerActivity.java:390-392; TitlePage.java:121-127; SourcesActivity.java:186). It needs "Up next" (#7 above) and the episode-numbering fix first, and "Hide spoilers" should stay off by default.
- **Keep the TV tests away from your data (M):** test cleanup forces the audio language to English every session, which would erase the default-language setting (#16). Make tests write to separate test copies of the settings, and make renamed code break the build instead of failing on the TV (tests/PreferenceRepair.java:16; TV-DEPLOYMENT.md:236-239; tests/ContinueUiTests.java:53-54). Land this before #16.
- **Episode numbering fix (M):** look up Stremio's own episode numbering (Cinemeta) so add-ons are asked for the right episode on shows where TMDB numbers seasons differently, such as One Piece, Money Heist, Futurama, Attack on Titan and specials (SeriesEpisodes.java:23; AddonStreams.java:79-89; SourcesActivity.java:66-68).
- **Playback and crash log on the TV (M):** a small, privacy-safe rolling log of source attempts, failure reasons and why Android last stopped the app, so last night's failure can be diagnosed without a computer once updates arrive from GitHub (Watchdog.java:30-37, :50-59; PlaybackSession.java:11-12; PlayerActivity.java:119). Show one line in Notices, not a new screen.
- **Keep the ADB approval alive (S-M):** background jobs should never pop "Allow USB debugging?" over what you're watching. A daily quiet check stops Android expiring the approval after 7 unused days, and choosing the current Home Screen mode again re-approves. Without this the crash safety net can break silently (Adb.java:100-103; HomeMode.java:93, :237-238, :257; SettingsActivity.java:463).
- **"Watch on Netflix" opens the title, not Netflix's home (M):** if the button is pressed before the direct link arrives, wait briefly for it, then test link variants on both TVs; WatchHub's own TV link uses /watch/ with source=30 (SourcesActivity.java:113, :222-227; WatchOptions.java:146, :171-175).
- **Run logic tests on the Mac (M):** check source order, episode order and seeking in seconds without touching the TVs. This first needs the language helpers moved out of Ui and the Android version of the JSON library (com.vaadin.external.google:android-json), kept out of the APK, because the code relies on how Android handles JSON nulls (ContinueWatching.java:106; Recs.java:442; build.sh:72).