# App editor checks

Build the app with `./build.sh` first.

Set `JAVA_HOME` and `ANDROID_HOME` for JDK 17 and Android SDK 34.

Run `bash tests/build.sh` to build the test APK.

Install `FreeMyTV.apk` and `out/tests/tests.apk` on the test TV.

Run the isolated settings checks:

```sh
adb -s TV_ADDRESS:5555 shell am instrument -w io.freemytv.tests/io.freemytv.ShelfTests
```

The UI checks require eight selected apps, with Netflix first and YouTube second.
The test reads and restores the other six selections.
They temporarily change the row through the editor and restore its order on success.
Keep the remote idle during this test.

```sh
adb -s TV_ADDRESS:5555 shell am instrument -w io.freemytv.tests/io.freemytv.EditorTests
```

If a UI check fails after Save, restore the desired order through Edit apps.
The test APK has no home screen icon. Uninstall `io.freemytv.tests` after testing.
