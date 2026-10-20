# TV deployment and maintenance

This file describes the modified Free My TV application on the build Mac.
The application includes home app editing, search, automatic playback, Continue Watching, and remote seek previews.

## Files on this Mac

| Item | Location |
| --- | --- |
| Project | `path/to/lumatv` |
| Deployment instructions | `TV-DEPLOYMENT.md` in the project directory |
| Built APK | `FreeMyTV.apk` in the project directory |
| Deliverables | `path/to/outputs` |
| Installer copy | `outputs/FreeMyTV-San-Francisco-Celsius.apk` |
| Source patch | `outputs/FreeMyTV-search-playback.patch` |
| Private project backup | `outputs/FreeMyTV-build-backup.tar.gz` |

The code changes remain in the local working directory.
No Git commit or pull request is required for deployment.
The source patch contains the changes relative to the original repository.
The private backup includes the local configuration and signing key.
The backup does not contain the TVs' Stremio accounts or application preferences.

## Current deployment scope

The user authorized work on the second TV again on October 20, 2026.
Do not deploy to the Hisense TV without a new user request for that TV.
The installed second-TV application matches the saved APK by SHA-256.
The October 20 updates include episode controls, audio verification, intro controls, and Android media controls.
See `SEEK-PREVIEWS.md` for the later preview preparation update and its limits.
The test APK was removed after verification.
The saved track preferences were restored.
English remains the default audio language.
The TV was returned to the home screen.

## TV addresses

| TV | Address | Android version | ADB address |
| --- | --- | --- | --- |
| Hisense | `<TV1-IP>` | Android 12, API 31 | `<TV1-IP>:5555` |
| Second TV, model G3 | `<TV2-IP>` | Android 11, API 30 | `<TV2-IP>:5555` |

The modified build supports Android 11 and later.
The original upstream instructions require Android 12 or later.
The TV addresses can change after a router or TV restart.

## Connect from the Mac

1. Connect the Mac and TV to the same local network.
2. Turn on the TV.
3. Open the TV's Developer options.
4. Enable USB debugging.
5. Check the address under Settings → Network & Internet.
6. Open Terminal on the Mac.
7. Run the applicable connection command.

```bash
adb connect <TV1-IP>:5555
adb connect <TV2-IP>:5555
adb devices
```

If Android shows a debugging approval prompt, select **Always allow from this computer**.
Then select **Allow**.
The Mac and Free My TV use separate debugging keys.
Android can request approval for each key.

Use `-s` with every TV command when both TVs are connected.
This prevents deployment or input commands from reaching the wrong TV.

```bash
adb -s <TV1-IP>:5555 shell getprop ro.product.model
adb -s <TV2-IP>:5555 shell getprop ro.build.version.sdk
```

If a connection shows `offline`, reconnect that TV.

```bash
adb disconnect <TV2-IP>:5555
adb connect <TV2-IP>:5555
```

Do not restart the entire ADB server during another active TV operation.
If a connection fails, check the TV's current address and debugging setting.
A TV that uses Wireless debugging can require pairing and a different port.
The commands above use the currently configured TCP port, `5555`.

## Build the application

The current Mac has JDK 17 and Android command-line tools installed through Homebrew.
The build requires Python 3, Android platform 36, and Android build tools 36.0.0.
The project does not use Gradle.

```bash
cd path/to/lumatv
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./build.sh
```

The build creates `FreeMyTV.apk`.
The build downloads dependencies specified in `player-dependencies.lock`.
The build checks each dependency's SHA-256 hash.

Keep these files private:

- `config.properties`: the TMDB token, region, location, and weather settings.
- `freemytv.keystore`: the signing key required for application updates.
- `out/gen/io/freemytv/Config.java`: generated code that contains the TMDB token.
- The APK and private backup: these include the configured application.

Do not replace the signing key.
If an update uses a different key, Android rejects it.
Do not uninstall the application to bypass a signing error.
An uninstall deletes its private preferences and account connection.

The current configuration uses the owner's city, US content, and Celsius.
Edit `config.properties` before building if those settings must change.
Do not print the TMDB token in terminal output or documentation.

## Deploy an update

1. Build the application.
2. Connect to the target TV.
3. Install the APK with `--no-incremental -r`.
4. Grant access to shared TV listings for Continue Watching.

For the Hisense TV:

```bash
adb -s <TV1-IP>:5555 install --no-incremental -r FreeMyTV.apk
adb -s <TV1-IP>:5555 shell pm grant io.freemytv android.permission.READ_TV_LISTINGS
```

For the second TV:

```bash
adb -s <TV2-IP>:5555 install --no-incremental -r FreeMyTV.apk
adb -s <TV2-IP>:5555 shell pm grant io.freemytv android.permission.READ_TV_LISTINGS
adb -s <TV2-IP>:5555 shell cmd package compile -m speed -f io.freemytv
```

The compilation command prepares this application's code on the TV.
It can take additional installation time and storage.
Repeat it after an application update.
It does not change other applications or Android animation settings.

An update preserves application order, saved playback positions, and the encrypted Stremio connection.
Each TV has separate application preferences and Android Keystore keys.
Installing an APK does not transfer the other TV's Stremio connection.

To open the home screen directly:

```bash
adb -s <TV2-IP>:5555 shell am start -a android.intent.action.MAIN -c android.intent.category.HOME -n io.freemytv/.HomeActivity
```

To open the application settings:

```bash
adb -s <TV2-IP>:5555 shell am start -n io.freemytv/.SettingsActivity
```

For initial activation, select **Home Screen → Free My TV** in the application settings.
If Android requests debugging approval, select **Always allow from this computer**.
Then select **Allow**.
The application records the original launcher and the packages it disables.
The **Original** setting restores that recorded configuration.
Do not disable TV packages manually.

## Continue Watching

The home screen shows up to 40 recent unfinished titles.
The application keeps the source accounts unchanged.
New Free My TV playback positions stay on the TV where playback occurred.
The home screen combines these sources:

- Playback positions saved by Free My TV.
- The library from the authorized Stremio account.
- Watch Next entries shared by supported installed TV applications.

Stremio library synchronization uses the official account API.
The application stores the account key with Android Keystore encryption.
A legacy add-on connection can play sources without retaining an account key.
That legacy connection needs account approval before Stremio library synchronization can work.

Netflix and Prime Video entries depend on data those applications share with Android.
The application cannot read their private account databases.
Missing entries do not prove that the user has no unfinished titles.
A shared service entry opens that service's own application.
A Free My TV or Stremio library entry opens internal playback for the saved title and episode.

The player buffers video ahead and retains a bounded private video cache.
Remote seeking displays decoded video frames when the source supports seeking.
Press Left or Right to choose a position.
Press OK to confirm the position.
Press Back to cancel the selection.
Three seconds without another arrow press confirms the selected position.
Hold Left or Right to increase the seek step.

## Run TV tests

Run tests only on the intended test TV.
Testing opens screens, sends remote input, and plays generated media.
Inform the person using that TV before testing.

Build the test application after building the main application.

```bash
bash tests/build.sh
adb -s <TV2-IP>:5555 install --no-incremental -r out/tests/tests.apk
adb -s <TV2-IP>:5555 shell input keyevent KEYCODE_WAKEUP
```

Run the required suites separately.

```bash
adb -s <TV2-IP>:5555 shell am instrument -w io.freemytv.tests/io.freemytv.StreamDataTests
adb -s <TV2-IP>:5555 shell am instrument -w io.freemytv.tests/io.freemytv.ProgressDataTests
adb -s <TV2-IP>:5555 shell am instrument -w io.freemytv.tests/io.freemytv.PlayerTests
adb -s <TV2-IP>:5555 shell am instrument -w io.freemytv.tests/io.freemytv.ContinueUiTests
adb -s <TV2-IP>:5555 shell am instrument -w io.freemytv.tests/io.freemytv.DiscoveryUiTests
adb -s <TV2-IP>:5555 shell am instrument -w io.freemytv.tests/io.freemytv.PerformanceTests
adb -s <TV2-IP>:5555 shell am instrument -w io.freemytv.tests/io.freemytv.EpisodeDataTests
adb -s <TV2-IP>:5555 shell am instrument -w io.freemytv.tests/io.freemytv.EpisodeFlowTests
adb -s <TV2-IP>:5555 shell am instrument -w io.freemytv.tests/io.freemytv.IntroDataTests
adb -s <TV2-IP>:5555 shell am instrument -w io.freemytv.tests/io.freemytv.IntroPlayerTests
adb -s <TV2-IP>:5555 shell am instrument -w io.freemytv.tests/io.freemytv.SeekSpeedTests
adb -s <TV2-IP>:5555 shell am instrument -w io.freemytv.tests/io.freemytv.PreparedPreviewDataTests
adb -s <TV2-IP>:5555 shell am instrument -w io.freemytv.tests/io.freemytv.PreparedPreviewTests
adb -s <TV2-IP>:5555 shell am instrument -w io.freemytv.tests/io.freemytv.ArtworkLifecycleTests
```

Read each suite's result before continuing.
Allow thirty seconds after installation before starting device tests.
Allow five seconds between suites.
The prepared-preview device test plays an episode through the saved Stremio account.
It clears only the JPEG preview cache before measurement.
A process failure does not identify its cause.
On the G3, the manufacturer memory manager can stop applications when available memory is low.
Check the Android logs before classifying a stopped test as an application crash.

Before removal, restore the test snapshots and the English default.

```bash
adb -s <TV2-IP>:5555 shell am instrument -w io.freemytv.tests/io.freemytv.PreferenceRepair
```

After testing, remove only the test application.

```bash
adb -s <TV2-IP>:5555 uninstall io.freemytv.tests
```

Do not uninstall `io.freemytv`.
Do not clear its application data.
The tests use temporary media and restore the preferences they change.

## Measure performance

Use the same TV, installed applications, and starting state for performance comparisons.
Record several samples for each operation.
Report the median and the test conditions.

The application settings show saved launcher timing and memory measurements.
The latest idle renderer test observed zero callbacks in four seconds on the G3.
The renderer resumes animation callbacks after remote input.
The application also caches title metadata and public artwork.
The application releases home artwork and hardware layers when playback starts.
The application records the original measurements during initial activation.
Memory measurements describe the process footprint, not playback quality or source response time.

```bash
tv_home_pid=$(adb -s <TV2-IP>:5555 shell pidof io.freemytv:home | tr -d '\r')
adb -s <TV2-IP>:5555 shell dumpsys meminfo "$tv_home_pid"
adb -s <TV2-IP>:5555 shell dumpsys gfxinfo "$tv_home_pid" framestats
adb -s <TV2-IP>:5555 shell am start -W -a android.intent.action.MAIN -c android.intent.category.HOME -n io.freemytv/.HomeActivity
```

A reported launch time does not include every later artwork or network request.
Separate local navigation measurements from remote video startup measurements.

## Restore the original home screen

1. Open Free My TV settings.
2. Select **Home Screen**.
3. Select **Original**.
4. Accept the Android debugging prompt if one appears.

Keep Free My TV installed until restoration completes.
The application restores the recorded launcher, screensaver, and disabled packages.
It does not remove streaming applications or their accounts.

If the Free My TV home screen fails, open its settings from ADB.

```bash
adb -s <TV2-IP>:5555 shell am start -n io.freemytv/.SettingsActivity
```

The application also has a repeated-crash watchdog that requests the original launcher.
Do not change picture, sound, network, remote, or account settings during deployment.

## Save a deployment copy

After verification, copy `FreeMyTV.apk` to the deliverables directory.
Keep the APK, source patch, and private project backup from the same build.
Keep the private backup readable only by the Mac user.
Do not share the private backup publicly.

The source patch excludes configuration, signing keys, build output, and downloaded dependencies.
To restore the private backup, extract it to a separate directory first.
Confirm that the configuration and signing key are present before rebuilding.

## Verification record

Feature checks ran on the G3 at `<TV2-IP>:5555` on October 19 and 20, 2026.
Completed runs passed these checks:

| Suite | Checks passed |
| --- | ---: |
| Player, tracks, source retries, and seek controls | 41 |
| Source data and encrypted storage | 36 |
| Continue Watching data and seek state | 25 |
| Continue Watching navigation and resume arguments | 12 |
| Search and series navigation | 23 |
| Idle rendering and animation restart | 3 |
| Add-on response failures, library changes, and caches | 50 |
| Player recovery from controlled failures | 13 |
| Search access and lazy watch-card navigation | 11 |
| Episode order and provider priority | 14 |
| Episode navigation, next playback, and service order | 25 |
| Intro marker data and countdown | 22 |
| Intro controls and Android media routing | 15 |

The extended 41-check player suite passed on October 20.
It also checked that subtitle replacement does not reject a valid source.
It verified per-title audio selection without changing the English default for other titles.
Earlier runs were interrupted by the G3 firmware's low-memory cleanup.
The firmware configuration remains unchanged.

The latest renderer test produced zero callbacks during a four-second idle sample.
The second TV's account approval imported 19 Stremio add-ons.
The October 20 check found 36 recent Stremio entries and two Netflix entries.
Prime Video did not publish a shared Watch Next entry during that check.
The tests also verified decoded preview frames before and after the selected position.
The discovery suite restores saved playback and season preferences after its checks.

## Repeat the startup measurement

Run this command from the project directory:

```bash
python3 tools/measure-performance.py --serial <TV2-IP>:5555 --output ../../outputs/performance-after.json
```

The script stops Free My TV before each sample.
It preserves application data.
It reads Android's first-display timing for each new home instance.
This avoids a zero-time result when Android automatically restarts its default launcher.
Use the second TV while it is available for testing.

See `PERFORMANCE.md` for the measurements, test conditions, and known firmware limitations.

## Source verification

Two small source checks can run in parallel.
The player checks duration, rendered video, and advancing audio output before it saves playback progress.
The live check used The Big Bang Theory, season 9, episode 22.
It rejected earlier sources and reached a source with a duration of 1,201,632 milliseconds.
The probe limit remained two concurrent checks.
The captured frame showed the actual episode with English subtitles.
The October 20 live check observed 60 seconds of playback and confirmed that playback continued afterward.
The account connection and private source addresses remain on the TV.
The installed APK checksum and test results are recorded in `outputs/verification-2026-10-20.json`.

## Episode and media controls

See `EPISODE-FIXES.md` for the October 20 corrections and their limits.
Back from series playback returns to the selected episode.
Next episode starts the next aired episode.
A ten-second countdown follows playback completion.
Cancel autoplay prevents that transition.

A valid IntroDB marker enables Skip intro with a five-second automatic countdown.
Keep intro cancels it for the current episode.
Pause stops the countdown.
Unknown intro times do not change playback.
The public marker request does not contain account credentials or video addresses.

The player owns an Android media session while visible.
System media commands therefore reach the current player.
The application releases that session on exit.
The previous lack of a session could explain unexpected Netflix media-button handling.
The original overnight launch cannot be confirmed without its original logs.

The preceding intro update tests are recorded in `outputs/intro-session-tests.json`.
Its deployment summary records that APK checksum.

The preceding episode update recorded a median process-cold home startup of 846 milliseconds across three samples.
The home PSS sample was 90,678 KiB.
See `outputs/performance-episode-update.json` for the raw results.
See `outputs/episode-update-verification.json` for the final feature checks and APK checksum.

## Prepared preview update

See `SEEK-PREVIEWS.md` for background image preparation, measurements, and format limits.
The new test record is `outputs/prepared-preview-tests.json`.
Each suite record identifies its tested APK by SHA-256.
The record includes failed development runs.
Use `outputs/prepared-preview-verification.json` to identify the accepted runs.
The application retains the three-second seek confirmation timer.
No additional account, paid service, or hosted server is required.
