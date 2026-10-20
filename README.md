# LumaTV by Madfer Operations

LumaTV is an ad-free Android TV home screen with search, episode browsing, and a built-in player.
It is based on [Free My TV](https://github.com/simbasjungles/free-my-tv).
The Android package remains `io.freemytv`, so updates preserve existing application data.

The approved redesign and 55 improvements are listed in [ROADMAP.md](ROADMAP.md).
The approved feature groups are implemented.
Device verification and remaining external actions are recorded below.
See [VERIFICATION.md](VERIFICATION.md) for the evidence and incomplete physical checks.

## Features

- Search has the initial home focus.
- Search suggestions include movies, shows, people, and supported Stremio add-on catalogs.
- A phone can send search text through a QR link.
- Series pages show seasons, episode names, artwork, progress, and watched marks.
- Installed services can receive a direct title link when one is available.
- The built-in player selects sources automatically.
- Failed sources trigger recovery without a quality-selection screen.
- The player checks video frames, decoded audio output, and expected runtime.
- Playback includes next-episode controls, automatic playback, audio selection, and subtitles.
- Prepared seek previews reduce repeated decoding during remote input.
- The home screen includes Continue Watching and a configurable application row.
- Dark appearance is the default throughout the day.
- The screensaver shows a dark landscape, clock, date, and weather.

Continue Watching combines local progress, the connected Stremio library, and entries that other applications publish through Android Watch Next.
Netflix and Prime Video do not provide complete account histories through this interface.
Their paid content opens in their own applications.

## Install or update

Android 11 or later is required.
Connect the computer and TV to the same local network.

On the TV, enable Developer options under Settings → System → About.
Press **Android TV OS build** seven times.
Then enable **USB debugging** in Developer options.
Read the TV address under Settings → Network & Internet.

Install JDK 17, Python 3, Android command-line tools, and ADB on the computer.
The build requires Android platform 36 and build tools 36.0.0.

```bash
git clone https://github.com/LufeMC/lumatv
cd lumatv
```

For an existing installation, restore its original `freemytv.keystore` and `signing.sha256` before building.
A different signing key cannot update that installation.
Do not uninstall the application to bypass a signing error.

For an existing signing key, build an APK without personal configuration:

```bash
./build.sh --release
```

For a new installation with no previous signing key, use:

```bash
./build.sh --new-key --release
```

Keep the generated signing key and fingerprint for future updates.
The build stops if its signing certificate differs from the saved fingerprint.

Select one TV explicitly:

```bash
TV_SERIAL='192.0.2.50:5555'
adb connect "$TV_SERIAL"
adb -s "$TV_SERIAL" install --no-incremental -r FreeMyTV.apk
```

Replace the example address with the TV address.
If Android requests debugging approval, select **Always allow from this computer**.
Then select **Allow**.

Open LumaTV on the TV.
Select **Home screen → LumaTV** to activate the launcher.
Select **Original** to restore the factory launcher.

See [TV-DEPLOYMENT.md](TV-DEPLOYMENT.md) for connection, signing, deployment, recovery, and test instructions.

## Setup

Use the setup QR code to enter your TMDB token, city, country, and temperature unit.
The TV encrypts these saved values.
Use Settings → Stremio account to authorize the official Stremio device connection.
The application imports the authorized account's add-ons.

Private local builds can read `config.properties`.
These builds include that configuration and must remain private.
An existing private build transfers its setup into encrypted TV storage before an update without personal configuration.
The `--release` build excludes the TMDB key, city, latitude, and longitude.

Keep these local files private:

- `config.properties`
- `freemytv.keystore`
- `signing.sha256`
- Private APKs and backups

The keystore password can come from `LUMATV_KEY_PASS` or the macOS Keychain item `lumatv-keystore`.
Keep an encrypted signing-key backup outside the build computer.
Keep its recovery password separately.

## Verification

Build the application before running local logic tests:

```bash
./build.sh --release
python3 tools/test-logic.py
```

Device tests use separate copies of application preferences and state files.
They do not send test progress to the Stremio library.
Device tests can control the selected TV's screen and playback.
Run them only during an authorized test session.

The recorded performance measurements and their limits are in [PERFORMANCE.md](PERFORMANCE.md).
Playback behavior is documented in [PLAYBACK.md](PLAYBACK.md).
Seek-preview preparation is documented in [SEEK-PREVIEWS.md](SEEK-PREVIEWS.md).

## Privacy and updates

The application uses TMDB, Open-Meteo, configured Stremio add-ons, the authorized Stremio account, and service-link metadata.
The weather drawer can also request public market prices.
Phone typing and setup use a temporary, token-protected HTTP server on the local network.
Paired TVs use authenticated encryption for synchronization.

Updates come from this repository's GitHub releases.
The updater checks the APK hash, package, version, and signing certificate before installation.
Publishing a release remains a separate action.

## Credits

- Based on [Free My TV](https://github.com/simbasjungles/free-my-tv) by simbasjungles, under the MIT license.
- The redesign uses the Flora mark and the LumaTV name by Madfer Operations.
- This product uses the TMDB API but is not endorsed or certified by [TMDB](https://www.themoviedb.org/).
- Weather data: [Open-Meteo](https://open-meteo.com/).
- Font: [Inter](https://rsms.me/inter/), under the SIL Open Font License.
- See the repository's license and dependency files for other acknowledgments.
