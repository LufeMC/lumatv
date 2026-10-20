# Search and playback

This modified build adds title search and internal playback to Free My TV.
It preserves the editable app row and the original home screen controls.

## Use the TV

1. Press OK when the home screen opens.
2. Enter a movie or TV show title.
3. Select a poster from the suggestions.
4. For a TV show, select a season.
5. Select an episode or select Play.

Search has the initial home focus.
Search opens with the keyboard ready.
Back from a watch card or app tile returns focus to Search.
The remote Search key also works on the application's search, title, player, app editor, and settings screens.
Suggestions update while you type.
The title page shows artwork, a summary, playback controls, and the available seasons.
Episode cards include artwork, a title, a runtime, and a saved playback position when available.
Future episodes cannot start playback.
The application restores the last selected season and episode for each show.

The first playback request starts account approval automatically if the add-ons are not connected.
Scan the displayed QR code with your phone.
Authorize the account on the official Stremio website.
Playback continues automatically after approval.
Existing imported add-ons remain available without another account approval.
New account connections update the add-on collection automatically every six hours when needed.
Playback uses saved add-ons while that update runs in the background.
The application retains cached add-ons if account synchronization fails.

## Continue Watching

The Explore tab shows Continue Watching when saved titles are available.
Movies and Shows retain the recommendation cards.
Each card includes the service, playback progress, and artwork when available.
The row combines local playback, the authorized Stremio library, and shared Android Watch Next entries.
The Stremio library refreshes when the home screen opens, subject to a three-minute cache.
The application checks modification metadata before downloading the library.
If the metadata is unchanged, it uses the saved watch history.
Other services must share their entries with Android.
Netflix entries were available on the second TV during testing.
Prime Video did not provide an entry during that check.
The application does not read another application's private database.
Service cards open their corresponding applications.
Stremio library cards resume internal playback for the saved episode and position.

The application retries missing artwork after network recovery.
If service artwork fails, the application searches TMDB for matching artwork.
A legacy add-on connection needs account approval before it can synchronize the Stremio library.
Disconnecting the account removes its cached Stremio library from Free My TV.

## Automatic playback

The player starts with the highest labeled resolution.
It tries two sources at each resolution before trying a lower resolution.
The retry plan contains no more than twelve distinct sources.
The application does not show a quality selection screen or a source list during normal playback.
A failed source causes an automatic source change for the same movie or episode.
A source change preserves the playback position.
Startup buffering triggers another attempt after eight seconds.
Later buffering triggers another attempt after twelve seconds.

## Player controls

- OK displays the playback controls.
- The playback button pauses or resumes playback.
- Left and Right open the seek preview from the timeline or playback control.
- Repeated Left and Right presses change the selected position.
- Holding an arrow increases the seek step.
- OK confirms the selected position.
- Back cancels the selection and restores the previous position.
- The timeline selects a playback position.
- Audio opens the audio selection panel.
- Subtitles opens the embedded and add-on subtitle selection panel.
- Off disables subtitles.
- Playback options contains Try another source and Restart.

If the controls are hidden, press Left or Right to open the seek preview.
The preview uses decoded frames from the current source.
The player loads nearby preview frames before the next arrow press.
An unsupported source can show the selected time without an image.
The player buffers video ahead and retains a private cache of no more than 64 MiB.
The cache uses opaque keys.
Back returns directly to the episode list during series playback.
If a seek preview is active, Back cancels that selection first.
The application preserves audio and subtitle language preferences when those languages are available in another source.

## Supported sources

The internal player supports direct HTTPS video sources from connected Stremio add-ons.
The build includes Media3 support for progressive media, HLS, and DASH.
Actual video and audio support depends on the TV codecs.
The player does not implement BitTorrent, Usenet, archive extraction, or external Stremio server playback.
The player skips unsupported sources.
Subtitle files must use HTTPS.
The player supports SRT, WebVTT, and ASS/SSA subtitle files.
It does not extract subtitle archives.

Netflix, Prime Video, and other supported installed services retain their own players.
Regional availability comes from JustWatch through TMDB.
Availability does not verify the user's subscription or episode access.
The application uses a title link when the installed service supports that link.
Otherwise, it opens the service application and identifies the need for another search.

## Private data

Stremio device authorization imports the account's add-on descriptors.
It does not change the account's add-ons.
New account connections store the Stremio authorization key with Android Keystore encryption for automatic add-on synchronization.
Android Keystore protects the encrypted add-on addresses on the TV.
Settings → Playback account → Disconnect account removes the saved account connection and add-ons from Free My TV.
Video request headers do not go to an external subtitle provider.
Media3 logging is disabled because source addresses can contain private keys.

## Build

Install JDK 17, Python 3, Android platform 36, and Android build tools 36.0.0.
Run `./build.sh` from this directory.
The build downloads reviewed dependencies from Google Maven and Maven Central.
`player-dependencies.lock` specifies each artifact and its SHA-256 hash.
`player-dependencies.xml` records the dependency roots.
Maven is needed only when resolving a new dependency set.
Keep `config.properties` and `freemytv.keystore` private.
Preserve the signing key for application updates.

## Performance

The home renderer schedules animation frames while the interface changes.
An idle home screen checks its state once per second.
The application releases recommendation artwork and hardware layers when the home screen pauses.
Title metadata has a bounded five-minute memory cache.
Public title artwork uses bounded memory and disk caches.
The Continue Watching row loads nearby images and releases distant image references.
The home screen prepares visible recommendation images instead of all recommendation tabs.

## Verification

The TV test suite includes generated silent video with two audio tracks and two subtitle tracks.
It checks source failure, source changes, position recovery, track selection, subtitle files, pause, resume, and HTTPS playback.
The data tests check metadata, source filtering, automatic resolution fallback, request headers, and encrypted storage.
The discovery UI tests check home screen search, search suggestions, title details, season selection, and automatic playback.
The progress data suite checks resume identity, account filtering, completed titles, and seek confirmation.
The Continue Watching UI suite checks remote navigation and resume arguments.
The performance suite measures idle callbacks and verifies that input restarts animation.

Run `bash tests/build.sh` after building the application.
Install `out/tests/tests.apk` only for testing.
Remove the test application after testing.

## Startup and source response

The watch row creates six cards initially.
It adds later cards as the user approaches them.
Package queries and app image decoding run outside the main thread.
Service thumbnails have an 8 MiB private disk cache.
Public title metadata has a 4 MiB private disk cache.
These caches reduce repeat requests after a process restart.
Home images are released during playback.

Playback can start before all add-ons respond.
A 4K source can start immediately.
Other sources allow an 800 ms opportunity for a higher resolution.
Later results remain available for source recovery.
Source discovery stops after 15 seconds.
The player changes sources after eight seconds of initial buffering or twelve seconds of later buffering.
The retry list remains limited to twelve sources.
Failed sources do not receive additional Media3 retries before the application tries another source.

The deployment guide includes Android code compilation and repeatable startup measurements.
See `PERFORMANCE.md` for the results and test conditions.

## Verify a playback source

The application checks up to two sources in parallel before playback.
Each check requests the first 4 KiB.
The checks reject HTTP failures, empty responses, and obvious text error responses.
A timeout or unsupported byte range permits later player verification.
The checks follow at most three HTTPS redirects.
The application decodes one source at a time.

The player compares the available duration with the expected movie or episode runtime.
If the expected runtime is at least ten minutes, the player checks for a short placeholder.
It rejects clips shorter than two minutes and one-fifth of the expected runtime.
The player rejects audio-only sources.
It requires a rendered video frame and advancing audio output before it records playback progress.
If either output check fails within eight seconds, it tries another source.
A black cover hides rejected clips while the player changes sources.
An error clip cannot mark the episode complete.
These checks cannot prove the semantic content of every video.

The default audio language is English.
Use Audio in the player to select another available language.
The application saves that choice for the movie or series.
It preserves the choice when it changes sources.
A title choice does not change the English default for other titles.
The source must contain the selected audio track.

## Episode controls and service order

Back returns from series playback directly to the selected episode card.
Back cancels an active seek preview before it exits playback.
The player does not restore the source-search screen after normal Back navigation.

Next episode is available in the series player controls.
The application finds the next aired episode in season order.
It continues into later seasons when necessary.
It does not start future episodes or mix specials into regular seasons.
At the end, a ten-second countdown starts the next episode.
Cancel autoplay leaves the completed episode on screen.
Back exits the completed episode.

An installed service with a subscription offer becomes the first title-page action.
For an available Netflix title, the action reads Watch on Netflix.
Play with Stremio remains a separate action for internal playback.
Rental and purchase offers do not become the default action.
Catalog availability does not establish access through the user's subscription.
Internal Continue Watching entries identify Stremio as their source.
Older local entries use the same label when the application reads them.

## Audio verification

The internal player decodes compressed audio to PCM before TV output.
This avoids relying on compressed-audio passthrough support reported by the device.
A source needs a supported video track and a supported selected audio track.
The player requires a rendered video frame and an advancing audio output position.
A source cannot save progress before both checks pass.
Subtitle changes can temporarily clear track data.
The player waits for current track data before it rejects that source.
It ignores playback-state events that no longer match the current player state.
Audio errors and a missing output confirmation cause automatic source recovery.
The retry preserves the saved episode position and selected audio language.

The output-position check cannot measure speaker sound or identify intentionally silent material.
TV mute, volume, receiver faults, and silent encoded tracks remain outside this check.
PCM output does not preserve compressed Dolby Atmos passthrough.

## Seek previews

The player prepares nearby AVC preview frames during normal playback.
Prepared images appear without seeking the playback decoder.
A background worker uses JCodec for supported independent AVC frames up to 1080p.
It does not create another Android video codec.
Images use bounded memory and private disk caches.
Unsupported sources retain the previous seek-and-capture method.
The title screen releases artwork during playback and restores it on return.
See `SEEK-PREVIEWS.md` for resource limits and actual device measurements.

Each arrow press restarts a three-second confirmation timer.
When input stops for three seconds, the player confirms the selected time.
OK confirms immediately.
Back cancels the seek.
If playback was paused before seeking, confirmation keeps it paused.
A source that does not support fast seeking can still delay an uncached preview.

## Skip intro

The player reads episode intro markers from the public IntroDB API.
A valid marker displays Skip intro and Keep intro during the intro.
Skip intro shows a five-second countdown.
The application skips automatically when the countdown reaches zero.
Select Skip intro to skip immediately.
Select Keep intro to cancel automatic skipping for that episode.
Pausing playback stops the countdown.
Leaving the intro interval removes the controls.

The application checks the show, season, episode, confidence, bounds, and available runtime.
It does not estimate an unknown intro length.
Community markers can differ between release versions.
If markers are missing or fail validation, no intro control appears.
The application caches public markers for one day in its existing bounded metadata cache.
Requests contain the episode identity only.
They do not contain Stremio credentials or video addresses.
No additional account or paid service is required.

## System media controls

The visible player registers an Android media session.
System Play, Pause, Forward, Rewind, and Next commands control this player.
The session becomes inactive when the player stops.
It has no background media-button receiver that can reopen playback.

The earlier player did not register a media session.
That omission allowed Android media commands to reach another application's session.
It is a possible cause of the reported unexpected Netflix launch.
The historical launch cannot be confirmed without its original Android logs.
The application opens an external service only after a user selects its service action.
A dedicated Netflix remote button still belongs to Netflix.
