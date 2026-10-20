# Episode playback and seek corrections

This update applies to the second TV, model G3, at `<TV2-IP>:5555`.
The Hisense TV remains unchanged.

## User requests

| Request | Application behavior |
| --- | --- |
| Back from an episode | One press returns to the episode list and restores the selected card. |
| Next episode control | The player contains a Next episode button for the next aired episode. |
| Automatic next episode | A ten-second countdown follows playback completion. Cancel autoplay stops the transition. |
| Verify audio and video | The player requires a supported selected audio track, a rendered video frame, and advancing audio output. |
| Netflix before internal playback | An available installed subscription service becomes the primary title action. Stremio has a separate action. |
| Faster seek previews | The player prepares nearby images during playback and caches rendered frames. |
| Confirm an idle seek | Three seconds without input confirms the selected position. Each arrow press restarts the timer. |
| Skip an intro | A verified episode marker displays Skip intro with a five-second countdown and Keep intro. |
| Unexpected Netflix launch | The player registers its own Android media session for system media commands. |

OK confirms a seek immediately.
Back cancels an active seek before it exits playback.
If playback was paused before seeking, confirmation keeps it paused.
An open language menu consumes Back when the user closes that menu.

## Navigation and episode order

The title controller receives the player result.
A normal return rebuilds the episode list from cached metadata.
It does not restore the source-search state.
A next-episode result selects the next season and episode before source discovery starts.
The same Stremio account and saved audio preference remain available.

The episode sequence excludes future episodes.
It continues into a later regular season when necessary.
Specials remain in a separate sequence.
At the series end, the player has no next-episode countdown.
The existing player controls contain Next episode and the temporary Cancel autoplay action.
The existing status line contains the countdown.

## Source and service identity

The title page reads installed regional catalog offers before it requests service links.
This permits Netflix to receive primary focus without waiting for link lookup.
Love Is Blind was the representative Netflix title in device verification.
The internal action identifies Stremio explicitly.
Older local Continue Watching records display Stremio when their former label was Free My TV.
Netflix and other external records retain their service identity.
Rental and purchase offers do not become the primary action.
Catalog data does not confirm the user's subscription access or every season's availability.

## Audio output

The player decodes compressed audio before PCM output.
It does not rely on compressed-audio passthrough support reported by the TV.
An audio error or absent output confirmation triggers automatic source recovery.
The player checks declared tracks before it enables video decoding.
Missing-track sources do not require a video decoder.
Decoder release can wait up to two seconds for cleanup.
A decoder-initialization failure receives one delayed retry before the application changes sources.
An unverified source cannot save progress or mark an episode complete.
Subtitle changes can temporarily clear track data.
The player waits for current track data before it rejects that source.
It ignores playback-state events that no longer match the current player state.
The selected language remains available through the Audio menu.

Output-position verification does not measure sound at the speakers.
It cannot identify TV mute, a receiver fault, or an intentionally silent encoded track.
PCM output does not preserve compressed Dolby Atmos passthrough.

## Preview performance and memory

The player now prepares nearby images during normal playback.
A background worker decodes supported independent AVC frames with JCodec.
Prepared images do not move the playback decoder.
The memory cache retains 32 images.
The private JPEG cache retains at most 16 MiB.
Unsupported sources retain the earlier seek-and-capture method.
See `SEEK-PREVIEWS.md` for measured results and format limits.
The later preview build passed nine suites, including 140 counted checks.
Its real-playback test measured 28–43 milliseconds for six prepared selections.
Those measurements followed sixty seconds of background preparation.

The following numbers describe the preceding episode build.
The controlled baseline recorded 617 milliseconds for the first preview and 33 milliseconds for a cached forward preview.
The preceding controlled sample measured 351 milliseconds for the first preview and 48 milliseconds for a cached forward preview.
Those measurements are saved in `outputs/seek-after.json`.
The live BBT check measured 1,822 milliseconds for an uncached forward preview.
Its next forward frame did not become available within the short preparation window.
That live sample does not establish instant previews for remote sources.
These small samples include injected remote input and preview availability.
They do not establish a worst-case latency for every source.

## Intro markers and system media commands

The intro control appears only during a valid episode marker.
It uses the existing button style and focus behavior.
A temporary panel keeps these actions separate from the regular playback controls.
A permanent button would remain visible when no intro action was available.
Keep intro cancels the countdown for the current episode.
Pause stops the countdown.
No fixed intro duration is assumed.

IntroDB reads require no API key.
The request sends the IMDb identifier, season, and episode.
The response stays in the existing bounded private metadata cache for one day.
The parser checks identity, confidence, timestamp bounds, and available runtime.
Community timing can differ between video releases.
Missing or uncertain markers do not change playback.

The player previously had no Android media session.
Android could route system media buttons to Netflix's existing session.
The new player session handles transport commands while the player is visible.
It becomes inactive on exit and has no background media-button receiver.
External service launches still require a selected service action.
The code and current tests cannot establish the exact cause of the earlier overnight launch.
The dedicated Netflix remote button remains unchanged.

## Verification and device limits

The preceding episode build passed all eight suites.
Its APK SHA-256 begins with `278cddf922f9b293`.
The six counted suites passed 130 checks in total.
The separate seek and live playback suites also passed.
The live test observed 60 seconds of playback after initial source verification.
It confirmed rendered video, advancing audio output, seek preview availability, and automatic intro skipping.
It did not watch the complete episode.

| Suite | Checks |
| --- | ---: |
| Player, tracks, source changes, and seek controls | 41 |
| Player failure recovery | 13 |
| Intro controls and Android media routing | 15 |
| Episode navigation and service priority | 25 |
| Episode order and provider data | 14 |
| Intro marker data and countdown | 22 |
The earlier device-test record is saved in `outputs/episode-fixes-tests.json`.
The intro and media-session update uses `outputs/intro-session-tests.json`.
The record includes intermediate failures as well as completed runs.
Use the latest result for each suite.
The summary record identifies the installed APK by SHA-256.
Each final suite record includes that build checksum.
Earlier failed runs remain available for diagnosis.

The G3 firmware can stop foreground applications during low system memory.
Its memory-service configuration remains unchanged.
The preceding home startup median was 846 milliseconds across three process starts.
The original measured baseline was 2,077 milliseconds.
The preceding home PSS sample was 90,678 KiB.
See `PERFORMANCE.md` for the session differences and measurement limits.
Tests preserve the user's watch data and language preferences.
The temporary test application is removed after verification.

References:

- [Media3 audio output events](https://developer.android.com/reference/androidx/media3/exoplayer/analytics/AnalyticsListener)
- [Media3 scrubbing mode](https://android-developers.googleblog.com/2025/08/media3-180-whats-new.html)
- [Video frame metadata](https://developer.android.com/reference/androidx/media3/exoplayer/video/VideoFrameMetadataListener)

- [IntroDB API](https://introdb.app/docs/api)
- [Android media sessions](https://developer.android.com/reference/android/media/session/MediaSession)
