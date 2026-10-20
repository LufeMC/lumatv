# Performance and playback verification

The performance measurements used the G3 TV at `<TV2-IP>:5555` on October 19, 2026.
Additional playback tests ran on October 20.
The Hisense TV did not receive this update.
The user authorized second-TV tests again on October 20, 2026.

## Initial home screen measurements

| Measurement | Before | After |
| --- | ---: | ---: |
| Median process-cold first display | 2,077 ms | 696 ms |
| Home process PSS | 124,971 KiB | 74,866 KiB |
| Idle callbacks in a ten-second instrumented sample | 10 | 1 |
| Largest input-to-draw delay in twelve instrumented events | 323.9 ms | 14.2 ms |

The startup samples were 2,083, 2,059, and 2,077 milliseconds before the changes.
The initial optimized samples were 696, 706, and 675 milliseconds.
These samples measure a new application process, with filesystem caches available.
They do not measure a complete TV restart.
Android reported the first displayed activity through `ActivityTaskManager`.
The memory samples used the home process PID through `dumpsys meminfo`.

That optimized build used a regular installation and Android's `speed` compilation mode.
The initial installation used code from the APK.
Thus, the comparison includes application changes, installation changes, and Android code compilation.
It does not isolate the effect of each change.

The instrumented events measure application input handling through drawing.
They do not measure the physical remote connection or display response.
The small samples do not establish long-term performance limits.
That ten-second idle sample recorded no network bytes.
Periodic updates and library changes still require network traffic.

## Changes

- The home screen creates six watch cards first.
- It adds later cards when navigation approaches them.
- Background tasks load application data and images.
- The idle renderer waits for a clock update, input, or changed data.
- Background images use smaller decoded images.
- Paused screens release image memory.
- Private disk caches reduce repeated metadata and thumbnail requests.
- Library metadata checks avoid unchanged full-library downloads.
- Saved add-ons remain available during a background account refresh.

These changes require no additional paid service or hosted server.
The measurements do not establish electricity or subscription savings.

## Source verification

Two workers check small source responses in parallel.
Each requests the first 4 KiB, with bounded network waits.
HTTP failures, empty responses, and obvious text errors fail verification.
Uncertain responses can proceed to the player.
Only one video decoder plays a source at a time.

The player checks the expected runtime against the available duration.
It rejects a 30-second placeholder for an expected full episode.
It also rejects audio-only and video-only sources.
It requires rendered video and advancing decoded audio output.
Rejected clips cannot save progress or mark an episode complete.
These checks cannot prove the semantic content of every video.

The controlled source suite passed 50 checks.
The extended controlled player-failure suite passed 13 checks.
The suite included decodable error clips, audio-only responses, video-only responses, and absent audio-output confirmation.
It confirmed automatic recovery to generated full-duration video.

Two live checks used The Big Bang Theory, season 9, episode 22, through the authorized account.
The player rejected earlier sources and reached a source lasting 1,201,632 milliseconds.
Both checks measured a maximum of two parallel source checks.
The captured frame showed the episode with English subtitles.
The October 20 check observed 60 seconds of playback and confirmed that playback continued afterward.

## Remaining verification

The G3 firmware repeatedly stopped the extended track-selection test.
Its memory service checks available system memory against a 450 MiB threshold.
The service stopped the foreground application after repeated low-memory samples.
Android recorded a process stop from that service, rather than an application exception.
The service also controls other TV functions.
Its configuration remains unchanged.

The exact cause of the earlier system-memory increase remains unresolved.
The earlier application build passed all 40 extended player checks on October 20.
The episode update passed 41 checks after it corrected transient track replacement during subtitle changes.
The checks verified Spanish audio, embedded subtitles, external subtitles, automatic source changes, and decoded seek previews.
The observed available memory stayed above the firmware threshold during that successful run.
The firmware can still stop applications if system memory becomes low.
This test does not establish permanent protection from firmware cleanup.

The saved test preferences were restored after the successful run.
English remains the default audio language.
The temporary test application was removed.
The installed application matches the saved APK by SHA-256.
The TV was returned to the home screen.
The Hisense TV remains unchanged.

## Files and repeatable measurements

The output directory contains `performance-before.json` and `performance-after.json`.
The earlier instrumented baseline is `performance-before-device.json`.
The latest instrumented sample is saved as `performance-after-device.json`.
An intermediate `performance-final-device.json` contains an older sample.
Do not substitute that intermediate sample for the final idle measurements.

To repeat the measurement during an authorized test session, run this command from the project directory:

```bash
python3 tools/measure-performance.py --serial <TV2-IP>:5555 --output ../../outputs/performance-after.json
```

Android measurement references:

- [Application startup timing](https://developer.android.com/topic/performance/issues/launch-time)
- [dumpsys](https://developer.android.com/tools/dumpsys)
- [Application code and memory](https://developer.android.com/topic/performance/memory/guide/app-code)
- [HTTP byte ranges](https://www.rfc-editor.org/rfc/rfc9110.html#name-range)

## Episode update measurements

The following measurements describe the preceding episode build.
That build retained 18 preview images and used its playback decoder for uncached previews.
The new preparation system is documented in `SEEK-PREVIEWS.md`.

The controlled seek baseline measured 617 milliseconds for the first preview and 33 milliseconds for a cached forward preview.
The final controlled sample measured 351 milliseconds for the first preview and 48 milliseconds for a cached forward preview.
Those controlled measurements are saved in `outputs/seek-after.json`.
The live BBT sample required 1,822 milliseconds for an uncached preview.
The adjacent forward frame was not ready within that test's short preparation window.
A fast cached result does not establish equally fast results for remote uncached frames.
A source without cached frames can still require network data and keyframe decoding.
The automatic seek confirmation waits three seconds after the last arrow press.
These measurements do not establish a worst-case response time.

Intro markers use the existing bounded metadata cache.
They require no extra video download, decoder, paid service, or hosted server.
The player reads markers once per episode when its one-day cache has no current response.
Unknown markers leave playback unchanged.
The Android media session is active only while the player is visible.

See `EPISODE-FIXES.md` for the complete behavior and verification limits.

## Preceding episode update

The preceding episode build recorded process-cold first-display times of 846, 831, and 908 milliseconds.
The median was 846 milliseconds.
The home process used 90,678 KiB of PSS after the samples.
The earlier original baseline was 2,077 milliseconds and 124,971 KiB of PSS.
The previous smaller update measured 696 milliseconds and 74,866 KiB.
These measurements came from separate sessions with different resident application state.
They do not isolate the cost of the playback additions.

The final build used nonincremental installation and Android speed compilation.
The samples retained filesystem and metadata caches.
They did not measure a full TV restart.
The raw record is `outputs/performance-episode-update.json`.

## Prepared preview update

The accepted preview build has SHA-256 `a18d34dd9bb4b07b50b71c01af59083c89ca33c1f78c256eed5cac54fd597bc1`.
It recorded process-cold first-display times of 779, 701, and 664 milliseconds.
The median was 701 milliseconds.
The home process used 88,579 KiB of PSS after those samples.
The application used Android speed compilation.
Filesystem and metadata caches remained available.
These samples do not measure a full TV restart.
The raw record is `outputs/performance-prepared-previews.json`.

The real episode test measured 28–43 milliseconds for six prepared seek images.
It allowed sixty seconds of normal playback before input.
The observation recorded no dropped video frames.
Background decoding still requires CPU time and can request additional video data.
Unsupported formats and unprepared positions retain a slower fallback.
See `SEEK-PREVIEWS.md` for resource limits and complete measurements.
