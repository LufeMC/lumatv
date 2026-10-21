# Device verification

Use JDK 17 and Android SDK platform 36.
The build requires Android build tools 36.0.0.
Keep the original signing key.

Build the application before building its tests:

```sh
./build.sh --release
bash tests/build.sh
```

Complete encrypted setup migration before installing a release build on an older private installation.
See `TV-DEPLOYMENT.md` for the installation procedure.

Select the authorized test TV explicitly:

```sh
TV_SERIAL='192.0.2.50:5555'
adb connect "$TV_SERIAL"
adb -s "$TV_SERIAL" install --no-incremental -r FreeMyTV.apk
adb -s "$TV_SERIAL" install --no-incremental -r out/tests/tests.apk
python3 tools/test-device.py --serial "$TV_SERIAL" --timeout 180 --output ../../outputs/device-tests.jsonl SearchAccessTests PlayerFlowTests PlayerFailureTests
```

Replace the example address with the selected TV address.
The runner records the APK hash and each suite result.
The tests can change the visible screen and start playback.
Keep the remote idle during a test.

Current suites that extend `TvTest` use isolated preferences and selected state files.
The test setup clears copied episode notices before each suite.
Notice tests then supply their own controlled release data.
Other tests suppress Stremio progress uploads.
`VisionQuestTests` reads the connected account and public metadata without uploading progress.
`StremioProgressTests` performs a real server write and restores the complete original item.
Run that server-write test only when the task requires it.

Do not run the legacy `EditorTests` or `PreferenceRepair` classes.
The current Home editor checks are in `HomeControlsTests`.
The legacy classes do not provide the same isolation guarantees.

Run the release and series-retention checks:

```sh
python3 tools/test-device.py --serial "$TV_SERIAL" --timeout 180 --output ../../outputs/device-tests.jsonl NewEpisodeTests VisionQuestTests ContinueUiTests EpisodeFlowTests
```

Run the reported playback case with isolated English captions:

```sh
python3 tools/test-device.py --serial "$TV_SERIAL" --timeout 300 --captions en --episode tt0898266:10:2 --output ../../outputs/device-tests.jsonl ReportedEpisodeTests
```

The reported-episode test observes sixty seconds of verified video and advancing audio.
The preview test also observes sixty seconds before its first seek input.
It reports Android input delivery and application drawing separately.
Physical remote transmission and panel response remain outside those measurements.

If the TV stops an instrumented process, inspect the Android log before classifying the failure.
Record a vendor force-stop separately from an application exception.
Do not disable TV services to make a test pass.

After verification, remove the temporary test application:

```sh
adb -s "$TV_SERIAL" shell am force-stop io.freemytv
adb -s "$TV_SERIAL" uninstall io.freemytv.tests
adb -s "$TV_SERIAL" shell am start -a android.intent.action.MAIN -c android.intent.category.HOME -n io.freemytv/.HomeActivity
```

Check the enabled remote-control service after instrumentation.
Preserve its existing approval and other enabled services.
Keep test records and household screenshots outside the public repository.
