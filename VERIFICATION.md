# LumaTV redesign verification

This record applies to the `redesign/lumatv` branch on October 20, 2026.
It separates implemented behavior from physical-device checks that remain incomplete.
The approved design remains the primary reference.
Dark appearance is the default at all times, following the latest user instruction.

## Test conditions

Device tests used one Android 11 G3 TV.
The Hisense TV was excluded from all installation and control commands.
The application kept its original package and signing certificate.
Tests used isolated preferences and selected state files.
They did not upload test progress to Stremio.

The private result directory contains JSONL records with each suite's APK SHA-256.
It also contains native screenshots and performance samples.
These files remain outside Git because they can contain household details.
A passing suite verifies its stated conditions, not every possible service response.

## Approved requirements

The identifiers below group the 55 approved improvements without changing their original scope.
“Device” means the behavior was exercised on the G3.
“Logic” means production logic passed controlled Android or host checks.
“Pending” identifies an incomplete physical or operational check.

| ID | Requirement | Evidence and remaining limits |
| --- | --- | --- |
| U1 | LumaTV name and original package | Device: installed name, Home, settings, and update package. |
| U2 | Flora icon and banner | Generated resources included in the APK. |
| U3 | Phone search through QR | Device: `PhoneLinkTests`; authenticated text delivery. |
| U4 | Remote application button mapping | Logic: protected keys and mapping. Device: Inputs target. Physical branded buttons remain model-dependent. |
| U5 | Prepare the next episode | Logic: source preparation and bounded media prefetch. Full continuous two-episode network measurement remains pending. |
| U6 | Late-night Still watching prompt | Logic: three-episode and clock conditions. Prompt and timer implemented. |
| U7 | Stremio library and progress | Device: connected account, imported library, and local merge. Tests suppress remote progress writes. |
| U8 | People and related titles | Device: search, person page, credits, and related-title layouts. |
| U9 | Flora opening | Implemented skippable opening with cached paths. Home tests wait for it to finish. |
| U10 | Match video refresh rate | Implemented supported display-mode selection. Physical panel cadence remains unmeasured. |
| U11 | Avoid unsupported sources | Logic: codec and source classification; device recovery tests. |
| U12 | Remember successful add-on and release | Logic: per-show memory and source ranking. |
| U13 | Prepare Resume sources on focus | Implemented Home chip preparation with bounded source and media reads. |
| U14 | One card per show | Logic: deduplication, service priority, and movie/series separation. |
| U15 | Quick settings on a held Settings press | Device: panel opens and closes without changing TV settings. |
| U16 | Default and per-title languages | Device: audio and subtitle selection; English household default preserved. |
| U17 | Signed GitHub updates | Logic: manifest, HTTPS hosts, package, version, hash, and signer checks. Full self-update installation remains pending. |
| U18 | Paired-TV synchronization | Logic: PIN agreement, encryption, replay rejection, and merge. Physical two-TV pairing remains pending. |
| U19 | Recovery and unavailable states | Device: playback recovery, offline handling, setup state, and source failures. |
| U20 | Search add-on catalogs | Logic: supported catalogs and identifiers. Device: asynchronous integration with public search results. |
| U21 | Automatic white application logos | Device: installed application row and text fallback; isolated reorder and removal. |
| T1 | Public repository safety | Secret scanner, ignored household file, upstream attribution, and pre-push hook implemented. |
| T2 | Protect the signing key | Password rotated in Keychain. AES-256 backup mounted and checked. Off-computer copy and separate password storage remain pending. |
| T3 | Limit backward-buffer memory | Logic: bitrate limits through 200 Mbps. Actual high-bitrate 4K playback remains unmeasured. |
| T4 | Recover focus after a card disappears | Implemented row rebuild and empty-row focus recovery. |
| T5 | Visible day-mode focus | Shared focus treatment implemented. Dark is now the default. Optional day mode remains available. |
| T6 | Remove private setup from the APK | Device: encrypted setup migration. Release APK excludes the token, city, latitude, and longitude. |
| T7 | Up next progress cards | Logic and device: completion thresholds, watched history, and next-episode card. |
| T8 | Release wake state when paused | Device player checks; wake flag follows playback and countdown state. |
| T9 | Restrict launcher crash fallback | Logic: foreground Home/screensaver classification and fallback controls. No deliberate device crash was required. |
| T10 | Explain source failures | Device: controlled failures and recovery. Retry, more sources, and Stremio fallback implemented. |
| T11 | Recover on the same source after network loss | Device: controlled network-state failure and recovery. TV Wi-Fi settings were not changed. |
| T12 | Recap and credits markers | Device: Skip recap, credits countdown, cancellation, and watched progress. |
| T13 | Select and replace add-on subtitles | Device: external subtitle selection without player replacement; parsing and failure handling. |
| T14 | Promote selected subscriptions | Device: Netflix priority and service list. Logic: disabled services excluded. |
| S1 | Prefer exact-file subtitles | Logic: file-match ranking and language ordering. |
| S2 | Remember manual subtitle timing | Device: timing adjustment saved per release. |
| S3 | Correct subtitle speed drift | Logic and device: correction, setting control, manual precedence, and Undo. |
| S4 | Align subtitles with dialogue | Logic: decoded-PCM speech matching and confidence rejection. Device: scheduler and Undo. Accuracy on arbitrary releases remains unproven. |
| Q1 | Forced subtitles when full subtitles are off | Implemented track-selection rules; controlled subtitle checks. |
| Q2 | Open title pages from Home picks | Implemented direct title intent; redesign navigation uses the title page. |
| Q3 | Include released Stremio titles in Home picks | Logic: service filtering and release eligibility. |
| Q4 | Rank episode filenames correctly | Logic: episode mismatch, combined files, and resolution-number cases. |
| Q5 | Temporary subtitles after a short rewind | Implemented built-in subtitle replay until the previous position. |
| Q6 | Correct interface text | Search counts, search instructions, and Live TV visibility updated. |
| Q7 | Portuguese text and system clock format | Shared controls use scalable layouts. Clock follows the Android setting. Long-label device checks remain limited. |
| Q8 | Settings contrast in day mode | Shared theme and scene dimming implemented. Native dark settings screenshots reviewed. |
| Q9 | Show the application row before the full scan | Device: separate shelf readiness benchmark. |
| A1 | Episode watched history | Logic and device: watched marks, progress, first-unwatched selection, and Stremio flags. |
| A2 | Isolate device-test data | Device: isolated preferences/files and unchanged protected household checksums. |
| A3 | Use provider episode numbering | Logic: Cinemeta identity, names, dates, specials, and ambiguity handling. Device: canonical names and season navigation. |
| A4 | On-TV playback and crash log | Logic: bounded log; settings Notices integration. |
| A5 | Maintain debugging approval quietly | Implemented quiet daily ADB job. Seven-day approval retention requires a longer observation period. |
| A6 | Open the actual Netflix title | Device: installed app accepts a direct title intent. Delayed opening is canceled when the viewer leaves. |
| A7 | Run logic checks on the computer | Host: 57 checks against production classes. |

## Current device checks

These suites passed at the integration checkpoints.
Each private result record identifies the exact APK.

| Suite | Passed checks |
| --- | ---: |
| SystemTests | 119 |
| PlaybackDataTests | 279 |
| DiscoveryDataTests | 16 |
| EpisodeDataTests | 14 |
| IntroDataTests | 22 |
| ProgressDataTests | 28 |
| StreamDataTests | 36 |
| SourceFailureTests | 50 |
| SearchAccessTests | 11 |
| PlayerFlowTests | 19 |
| PlayerTests | 40 |
| PlayerFailureTests | 13 |
| EpisodeFlowTests | 25 |
| ArtworkLifecycleTests | 8 |
| PerformanceTests | 3 |
| PhoneLinkTests | 9 |
| ServiceNavigationTests | 7 |
| PreparedPreviewDataTests | 27 |
| ScreensaverTests | 6 |
| SetupDataTests | 9 |
| HomeControlsTests | 14 |
| ContinueUiTests | 11 |
| DiscoveryUiTests | 19 |
| DesignReviewTests | 21 |

`DesignReviewTests` captured 18 native screens.
Additional captures cover Glance, quick settings, application editing, the screensaver, and the player.
The reviewed layouts use real title metadata and remote focus.

A live episode test confirmed video and advancing decoded audio for 60 seconds.
It recorded no dropped frames during that sample.
Six prepared-preview draw samples ranged from 26 to 51 milliseconds.
Three seconds without input confirmed the seek selection.
Back canceled the selection.

## Completion limits

The code includes the approved feature groups.
Full operational acceptance remains incomplete while the pending checks above remain unresolved.
Do not describe all 55 improvements as fully verified.
Do not publish a release based only on this integration record.
The signing backup still needs an off-computer destination and a separately stored recovery password.
Physical two-TV synchronization needs a separate authorized device session.
