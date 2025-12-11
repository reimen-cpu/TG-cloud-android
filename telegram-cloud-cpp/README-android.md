Android integration notes
=========================

Goal
----
Build the non-UI core of `telegram-cloud-cpp` as a native library for Android (`telegramcloud_core`) and expose a JNI API so the Android app can reuse the same database, backup and Telegram logic as the desktop app.

High-level steps
----------------
1. Install Android SDK and NDK (recommended r25c or r26). Ensure `sdk` and `ndk` paths are available.
2. Build OpenSSL, libcurl and SQLCipher for your target ABI(s) using the scripts in `third_party/android_build_scripts/`.
3. Pass the resulting install directories to CMake when Gradle triggers the external native build. Example CMake args:

   -DOPENSSL_ROOT_DIR="/path/to/openssl/install"
   -DCURL_ROOT="/path/to/libcurl/install"
   -DSQLCIPHER_ROOT="/path/to/sqlcipher/install"

4. Gradle will call the `CMakeLists.android.txt` and build `telegramcloud_core`. The Android app includes `NativeLib.kt` to load the library and call JNI functions.

Example: building dependencies (PowerShell)
-----------------------------------------
Set variables and run the scripts (adjust paths):

```powershell
$NDK = 'C:\Android\Sdk\ndk\25.2.9519653'
$API = 24
$ABI = 'arm64-v8a'
$OUT = 'C:\builds\native'

.
\telegram-cloud-cpp\third_party\android_build_scripts\build_openssl_android.ps1 -ndk $NDK -abi $ABI -api $API -srcPath 'C:\sources\openssl-3.1.3' -outDir $OUT\openssl

.
\telegram-cloud-cpp\third_party\android_build_scripts\build_libcurl_android.ps1 -ndk $NDK -abi $ABI -api $API -opensslDir $OUT\openssl\build_arm64 -srcPath 'C:\sources\curl-8.4.0' -outDir $OUT\libcurl

.
\telegram-cloud-cpp\third_party\android_build_scripts\build_sqlcipher_android.ps1 -ndk $NDK -abi $ABI -api $API -opensslDir $OUT\openssl\build_arm64 -srcPath 'C:\sources\sqlcipher' -outDir $OUT\sqlcipher
```

Passing libraries to Gradle
--------------------------
In `android/app/build.gradle.kts` we configured `externalNativeBuild` to point to `telegram-cloud-cpp/CMakeLists.android.txt`.

To pass the prebuilt dirs, add CMake arguments in `build.gradle.kts` like:

```kotlin
externalNativeBuild {
  cmake {
    path = file("../../telegram-cloud-cpp/CMakeLists.android.txt")
    version = "3.22.1"
    // Example - replace with your paths
    arguments += listOf(
      "-DOPENSSL_ROOT_DIR=/absolute/path/to/openssl/install",
      "-DCURL_ROOT=/absolute/path/to/libcurl/install",
      "-DSQLCIPHER_ROOT=/absolute/path/to/sqlcipher/install"
    )
  }
}
```

Automatic ABI-specific paths via `local.properties`
---------------------------------------------------
Instead of hardcoding the arguments, store the directories per ABI inside `android/local.properties`
or `gradle.properties`. The Gradle script looks for the following keys and injects them as
`-D<LIB>_ROOT_DIR_<ABI>` arguments automatically:

```
native.openssl.arm64-v8a=C:\builds\native\openssl\build_arm64
native.openssl.armeabi-v7a=C:\builds\native\openssl\build_armv7
native.curl.arm64-v8a=C:\builds\native\libcurl\build_arm64
native.curl.armeabi-v7a=C:\builds\native\libcurl\build_armv7
native.sqlcipher.arm64-v8a=C:\builds\native\sqlcipher\build_arm64
native.sqlcipher.armeabi-v7a=C:\builds\native\sqlcipher\build_armv7
```

Use `/` o `\\` escapados; el script de Gradle los normaliza automáticamente. También puedes declarar
un valor genérico (por ejemplo `native.openssl=/.../openssl/default`) que se aplicará a cada ABI si
no existe una entrada específica.

Notes
-----
- The build scripts are templates and assume the dependencies support CMake. Some versions require `configure` / `make` workflows; if so, run those under WSL or adjust scripts.
- SQLCipher must be built with the same cipher settings you used on desktop if you need binary-compatible databases.
- Backups: ensure your backup routine includes the DB and any files referenced; the DB format is platform independent but encryption details must match.

Next actions I can take for you
------------------------------
- Add more detailed Gradle/CMake integration (example `externalNativeBuild` arguments populated from project properties or environment variables).
- Implement more JNI glue to map additional core methods and event callbacks.
- Run a Gradle build here if you point me to NDK path and allow running the build (I can attempt it; it will require the NDK/SDK present on your machine).
