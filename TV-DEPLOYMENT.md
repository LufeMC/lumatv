# LumaTV deployment and maintenance

These instructions apply to the `redesign/lumatv` branch.
The Android package remains `io.freemytv`.
Updates with the original signing key preserve application data.

## Private local instructions

`TV-LOCAL.md` contains the actual workspace path and authorized TV address on the build computer.
Git ignores that file.
Public instructions use example addresses.
Select one TV explicitly for every command.
Do not use a second TV without authorization.

## Requirements

- Android TV 11 or later.
- ADB and Android command-line tools.
- Android platform 36 and build tools 36.0.0.
- JDK 17 and Python 3.
- The original signing key for an existing installation.

The project does not use Gradle.
The computer and TV must use the same local network.

## Connect to the selected TV

On the TV, open Settings → System → About.
Press **Android TV OS build** seven times.
Enable **USB debugging** in Developer options.
Read the TV address under Settings → Network & Internet.

```bash
TV_SERIAL='192.0.2.50:5555'
adb connect "$TV_SERIAL"
adb -s "$TV_SERIAL" shell getprop ro.product.model
```

Replace the example address with the selected TV address.
If Android requests approval, select **Always allow from this computer**.
Then select **Allow**.
The computer and LumaTV have separate debugging keys.
Android can request approval for each key.

If the connection is offline, reconnect the selected device.

```bash
adb disconnect "$TV_SERIAL"
adb connect "$TV_SERIAL"
```

Do not restart the ADB server during another active TV operation.
Some TVs require Wireless debugging pairing and a different port.
Use the port that the TV shows.

## Build a private update

On the configured Homebrew build computer, set these paths.

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./build.sh --release
```

The build creates `FreeMyTV.apk`.
It verifies dependency hashes and the signing certificate.
The version number increases with the Git commit count.
Commit the final source before building a version for distribution.

The `--release` build excludes the TMDB key, city, latitude, and longitude.
The TV reads its encrypted saved setup after installation.
An older private build automatically transfers its compiled setup into encrypted TV storage.
Complete that transfer before installing a build without personal configuration.

A normal `./build.sh` can include values from `config.properties`.
Keep that APK private.
Never upload the configuration, keystore, generated private configuration, or private backup to Git.

## Signing key and recovery

The original `freemytv.keystore` must sign every update for the current TVs.
The build stops if that file is missing.
Use `--new-key` only for a new installation.
A different key cannot update an existing installation.
Do not uninstall LumaTV to bypass a signing error.
An uninstall deletes its preferences and account connection.

The signing password comes from `LUMATV_KEY_PASS` or the macOS Keychain item `lumatv-keystore`.
The configured computer uses a random password stored in Keychain.
`signing.sha256` records the certificate fingerprint.
Keep both the keystore and fingerprint in an encrypted backup outside the computer.
Keep the backup recovery password separately.

The local encrypted disk image replaces the earlier plaintext build archive.
The encrypted payload includes the key, fingerprint, private configuration, source snapshot, and recovery instructions.
The backup does not contain the TVs' application preferences or Stremio account connections.
Mount the backup read-only before restoring files into a separate directory.
Verify the fingerprint before building an update.

## Install on the authorized TV

```bash
adb -s "$TV_SERIAL" install --no-incremental -r FreeMyTV.apk
adb -s "$TV_SERIAL" shell pm grant io.freemytv android.permission.READ_TV_LISTINGS
adb -s "$TV_SERIAL" shell cmd package compile -m speed -f io.freemytv
adb -s "$TV_SERIAL" shell am start -a android.intent.action.MAIN -c android.intent.category.HOME -n io.freemytv/.HomeActivity
```

The permission permits access to Android Watch Next entries.
Netflix and Prime Video do not expose their complete account histories through that interface.
LumaTV combines available entries with local progress and the authorized Stremio library.
The installed streaming application handles paid service playback.

LumaTV needs separate debugging approval to activate or restore the home screen.
Use Settings → Home screen → LumaTV to activate it.
Use **Original** to restore the factory launcher.
Do not clear application data during troubleshooting.

## Verify the installed APK

```bash
adb -s "$TV_SERIAL" shell pm path io.freemytv
shasum -a 256 FreeMyTV.apk
```

Pull the `base.apk` path reported by Android into a temporary local file.
Compare its SHA-256 with `FreeMyTV.apk`.
Use Android `apksigner verify --print-certs` to compare signing certificates.
Do not print credentials or private media addresses during verification.

## Device tests

Build the application before building its test APK.
The test APK uses the same signing key.

```bash
bash tests/build.sh
adb -s "$TV_SERIAL" install --no-incremental -r out/tests/tests.apk
python3 tools/test-device.py --serial "$TV_SERIAL" --output /tmp/lumatv-tests.jsonl SystemTests HomeControlsTests SearchAccessTests
```

The runner selects one TV and executes suites in sequence.
Each record includes the tested APK hash and result.
Tests use separate copies of preferences and selected state files.
Test progress cannot update the remote Stremio library.
Mode jobs and TV synchronization remain disabled within test processes.
Do not run the legacy `PreferenceRepair` class.
It does not use the current isolation contract.

Useful suites include these groups.

| Area | Suites |
| --- | --- |
| Home and design | `HomeControlsTests`, `ContinueUiTests`, `DesignReviewTests` |
| Discovery | `SearchAccessTests`, `DiscoveryUiTests`, `DiscoveryDataTests`, `EpisodeDataTests` |
| Playback | `PlayerTests`, `PlayerFlowTests`, `EpisodeFlowTests`, `PlayerFailureTests` |
| Sources | `StreamDataTests`, `SourceFailureTests` |
| Progress | `ProgressDataTests`, `PlaybackDataTests` |
| System | `SystemTests`, `SetupDataTests`, `PhoneLinkTests`, `ScreensaverTests` |
| Previews | `PreparedPreviewDataTests`, `PreparedPreviewTests` |

`HouseholdState` reports checksums without showing preference values.
It does not write household data.
Use it before and after device tests.

```bash
adb -s "$TV_SERIAL" shell am instrument -w io.freemytv.tests/io.freemytv.HouseholdState
```

After testing, stop the application to clear process-local test state.
Then restart the normal home screen.
Remove only the test package.

```bash
adb -s "$TV_SERIAL" shell am force-stop io.freemytv
adb -s "$TV_SERIAL" shell am start -a android.intent.action.MAIN -c android.intent.category.HOME -n io.freemytv/.HomeActivity
adb -s "$TV_SERIAL" uninstall io.freemytv.tests
```

If a test session requires the TV to remain awake, use `KEYCODE_WAKEUP` at ten-minute intervals.
Do not use arrow, OK, or media keys for this purpose.
Stop the wake schedule when testing ends.

## Updater integration test

`UpdateIntegrationTests` performs a real application replacement.
Run it only with an authorized newer APK and a controlled loopback HTTPS release.
Supply its `base` and DER certificate arguments through the test runner.
The test process trusts that certificate without changing Android trust settings.
A separate observer must confirm the installed version and APK hash.
Do not include this suite in ordinary regression runs.

## Performance measurements

```bash
python3 tools/measure-performance.py --serial "$TV_SERIAL" --output /tmp/lumatv-performance.json
```

The script restarts only LumaTV.
It preserves application data and records three process-cold display times.
See [PERFORMANCE.md](PERFORMANCE.md) for results and measurement limits.
See [SEEK-PREVIEWS.md](SEEK-PREVIEWS.md) for preview preparation and format limits.

## GitHub updates

`tools/release.sh` builds an APK without personal configuration and creates `latest.json`.
Its default mode does not publish a release.
Publishing requires a separate authorized `--publish` action.
The TV updater verifies the package, version, hash, and signing certificate before installation.
Do not publish a release until the remaining checks in [VERIFICATION.md](VERIFICATION.md) are complete.
