# Prepared seek previews

This update applies to the second TV at `<TV2-IP>:5555`.
The Hisense TV remains unchanged.

## Operation

The player prepares nearby preview images during normal playback.
Prepared images appear without a network request or a playback seek.
The hidden preview layer keeps its measured layout.
Prepared bitmaps request texture preparation before the first seek input.
The playback decoder remains at its original position while the user examines prepared images.
Back cancels the selection.
OK confirms the selection immediately.
Three seconds without input confirms the selection automatically.

The player prepares three ten-second positions ahead during normal playback.
It also retains images from recently played positions.
During seeking, preparation follows the selected direction.
The application assigns images to the nearest ten-second timestamp bucket.
An image represents a nearby video keyframe within ten seconds of that bucket.
The preview does not promise an exact frame for each selected millisecond.
If Android returns a distant cluster boundary, the reader can scan packet timestamps for a nearby keyframe.
It avoids an expensive scan when a nearby keyframe already provides a usable preview.
The scan stops after 512 packets or the bounded timestamp range.
It decodes only the selected independent frame.

## Resources and privacy

The application retains one hardware playback decoder.
One background worker extracts independent AVC frames with JCodec 0.2.5.
It does not create another Android video codec.
The decoder handles one slice on the existing background thread.
The worker reuses pixel planes and macroblock state for the current source.
At 1080p, these retained arrays use approximately 5 MiB.
It does not retain reference pictures for preview decoding.
A source change releases that workspace.
Unsupported software decoding uses the previous playback-decoder preview method.

The memory image cache retains at most 32 images.
Images measure at most 320 by 240 pixels.
Typical 320-by-180 images use approximately 7 MiB across 32 entries.
The maximum retained pixel allocation is approximately 9.4 MiB.
Temporary decoder buffers and display references require additional memory.
The private JPEG cache retains at most 16 MiB and 768 files.
The random-read page cache retains approximately 1 MiB.
Video reads share the existing 64 MiB media cache.
The playback buffer target is 20 MiB.
The backward buffer uses at most one quarter of that target, based on the source bitrate.
At high bitrates, its duration can be less than one second.

Initial preparation requires at least 480 MiB of available system memory.
An initialized reader normally requires 470 MiB.
It can continue above 450 MiB when its existing Java heap has at least 12 MiB free.
This permits reuse of allocated workspace instead of another codec allocation.
Under memory pressure, the worker requests garbage collection at most once per ten seconds.
New preview reads stop below 445 MiB.
The decoder reuses Java heap memory through normal Android garbage collection.
These limits do not guarantee that every temporary codec allocation stays above those thresholds.
The G3 firmware can still stop applications during low system memory.
The firmware settings remain unchanged.
Inactive search and title screens release artwork during playback.
They restore artwork when the user returns.

Each source has a 16 MiB preview network budget per 60-second window.
A final transfer block can exceed that budget slightly.
The budget persists when the reader restarts.
The counter includes network bytes requested by the preview reader.
It excludes playback traffic and does not represent the increase over playback alone.
Cached video reads require no additional download.
The application pauses preparation when it reaches a memory, network, or read-time limit.

Image filenames contain an opaque source hash and a timestamp.
The source hash includes the video address and request headers.
Different sources cannot reuse each other's JPEG images through an identical timestamp alone.
A source change clears the memory preview cache.
Canceled requests cannot deliver an image to the replacement source.
No preview server, additional account, or paid service is required.

## Limits

Preparation requires time after playback starts.
A distant timeline selection can have no prepared image.
Background preparation supports progressive, single-slice AVC keyframes at resolutions up to 1080p.
HEVC, 4K, interlaced AVC, multiple slices, HDR, DRM, slow sources, and low memory can prevent preparation.
The application can use the previous seek-and-capture method when preparation fails.
That fallback can still require a network wait and decoder work.
Prepared-preview measurements do not establish instant previews for every title or position.

## Verification

The device test starts with an empty JPEG preview cache.
It observes an authorized BBT episode during normal playback before it sends seek input.
It checks that each requested image exists before the corresponding input.
It measures input injection through the application's first preview draw.
The measurement excludes physical remote transmission and the display panel response.
The test also checks cancellation and three-second automatic confirmation.

The version 1.1.40 regression found a 183-millisecond first draw.
The remaining five draws took 29 to 40 milliseconds.
Layout retention and bitmap preparation require a new device measurement.

Generated video tests check actual red, green, and blue frames at different positions.
The fixture retains thirty seconds in one Matroska cluster to test timestamp scanning.
They also check random reads, cache reuse, source separation, cancellation, and network limits.
The artwork test checks memory release across Search, title details, and playback.
It verifies image restoration and the preserved search query.
The results identify the tested APK by SHA-256.

### Real playback measurements

The October 20 test used The Big Bang Theory, season 9, episode 22, on the G3 TV.
The tested APK has SHA-256 `a18d34dd9bb4b07b50b71c01af59083c89ca33c1f78c256eed5cac54fd597bc1`.
The JPEG preview cache was empty at the start.
The shared video cache could contain existing data.
The test allowed sixty seconds of normal playback before its first seek input.

| Measurement | Result |
| --- | ---: |
| Six prepared-image input-to-draw samples | 43, 28, 28, 28, 28, 28 ms |
| Playback observation | 60,113 ms |
| Playback position advance | 59,088 ms |
| Dropped video frames during observation | 0 |
| Minimum available system memory | 502,689,792 bytes |
| Application CPU time during observation | 37,550 ms |
| Generated preview frames after sixty seconds | 9 |
| Preview network bytes after sixty seconds | 11,098,910 bytes |

Each image existed before the corresponding input.
The six selections did not move the playback decoder.
Back cancellation and three-second automatic confirmation passed.
The screenshot shows actual episode images at neighboring positions.
The test does not measure physical remote or panel delay.

Background decoding can take approximately three seconds for one 1080p frame on this TV.
Preparing images before input removes that decoding delay from a prepared selection.
The CPU result includes playback and preparation across the application process.
The network result counts preview requests, including any overlap with normal playback requests.
These small samples do not establish performance for every source, format, or position.
The raw records are `outputs/prepared-preview-results.json` and `outputs/prepared-preview-tests.json`.

### Regression checks

The accepted build passed all nine device suites.
Seven counted suites passed 140 checks in total.
The separate real-playback and seek-speed suites also passed.

| Suite | Checks |
| --- | ---: |
| Prepared preview data, colors, random reads, and cache limits | 27 |
| Playback, tracks, source changes, and seek controls | 41 |
| Player failure recovery | 13 |
| Episode navigation and service priority | 25 |
| Intro controls and Android media routing | 15 |
| Search access and lazy card creation | 11 |
| Artwork release and restoration | 8 |

The controlled seek fixture measured 413 milliseconds for its first preview and 43 milliseconds for a cached forward preview.
That first-preview test does not allow sixty seconds of background preparation.
Its separate record is `outputs/seek-prepared-update.json`.
Tests restored saved watch data and the English audio default.
The temporary test application was removed after verification.
The installed APK matches the tested local APK by SHA-256.
The accepted suite records are collected in `outputs/prepared-preview-verification.json`.

The home screen recorded a median process-cold first display of 701 milliseconds across three starts.
The home process PSS sample was 88,579 KiB.
Those measurements used Android speed compilation with existing filesystem caches.
They do not measure a complete TV restart.
See `outputs/performance-prepared-previews.json` for the raw startup record.

Technical references:

- [JCodec source and license](https://github.com/jcodec/jcodec)
- [Android MediaExtractor](https://developer.android.com/reference/android/media/MediaExtractor)
- [Android MediaDataSource](https://developer.android.com/reference/android/media/MediaDataSource)

## LumaTV redesign checkpoint

The October 20 redesign retained background preview preparation.
A real 60-second episode test recorded six remote-input-to-preview-draw samples.
The samples were 51, 30, 26, 31, 29, and 31 milliseconds.
These measurements use prepared frames and exclude the physical remote and panel response.
The test prepared 11 frames and recorded approximately 2 MiB of preview network reads.
It recorded no dropped playback frames during that sample.
The three-second automatic confirmation and Back cancellation passed.
Unprepared positions and unsupported codecs can require a slower fallback.
These results do not establish a maximum delay for every source.

## Installed completion build

The LumaTV 1.1.37 repeat test measured 86, 30, 32, 32, 29, and 30 milliseconds for prepared-image draws.
The test began with an empty JPEG preview cache.
It observed 60 seconds of real playback before the seek checks.
Video and advancing decoded audio remained valid, with no dropped frames.
The test also verified Back cancellation and three-second automatic confirmation.
Physical remote transmission and panel response are outside these measurements.
Unprepared positions retain the documented fallback limits.
