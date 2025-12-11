Android build scripts for native dependencies
===========================================

This folder contains helper scripts and instructions to cross-compile OpenSSL, libcurl and SQLCipher for Android using the Android NDK CMake toolchain.

High-level notes / requirements
- Android NDK installed (recommended r25c or r26). Set `NDK_HOME` or pass `-ndk` argument to scripts.
- Android SDK (for general Android builds).
- CMake 3.22+ (NDK includes a CMake version; scripts use NDK's toolchain file).
- Bash (Git Bash/WSL) for the .sh scripts or PowerShell for the .ps1 scripts on Windows.
- Internet access to clone source repositories (or download tarballs and set env vars to point to them).
- Perl may be required for some OpenSSL versions if using Configure; the scripts prefer CMake-based build where available.

Overview of scripts
- build_openssl_android.ps1 / build_openssl_android.sh
  Builds OpenSSL for a given ABI and API level using the NDK toolchain (CMake path).

- build_libcurl_android.ps1 / build_libcurl_android.sh
  Builds libcurl linking against the OpenSSL build output.

- build_sqlcipher_android.ps1 / build_sqlcipher_android.sh
  Builds SQLCipher (sqlite with codec) for Android and links to OpenSSL if requested.

Usage (PowerShell example)
1. Open PowerShell and set variables (adjust paths for your machine):

   $NDK = "C:\\Android\\ndk\\r25c"
   $API = 24
   $ABI = "arm64-v8a"
   $OUT = "C:\\Users\\$env:USERNAME\\native-android-builds"

2. Build OpenSSL (example):

   .\\build_openssl_android.ps1 -ndk $NDK -abi $ABI -api $API -srcPath "C:\\sources\\openssl-3.1.3" -outDir $OUT\\openssl

3. Build libcurl (example):

   .\\build_libcurl_android.ps1 -ndk $NDK -abi $ABI -api $API -opensslDir $OUT\\openssl -srcPath "C:\\sources\\curl-8.4.0" -outDir $OUT\\libcurl

4. Build SQLCipher (example):

   .\\build_sqlcipher_android.ps1 -ndk $NDK -abi $ABI -api $API -opensslDir $OUT\\openssl -srcPath "C:\\sources\\sqlcipher" -outDir $OUT\\sqlcipher

After building, point your Android/CMake configuration to the produced library directories and headers. Example CMake options:

  -DOPENSSL_ROOT_DIR=<out>/openssl
  -DCURL_ROOT=<out>/libcurl
  -DSQLCIPHER_ROOT=<out>/sqlcipher

Notes and troubleshooting
- If a script fails because OpenSSL uses Configure/Perl, install Strawberry Perl on Windows or run scripts under WSL.
- For reproducibility, pin OpenSSL/libcurl/sqlcipher versions matching your desktop build where possible.
- Some projects include Android-specific patches or CMake helper files; consult their README if the CMake approach fails.

If you want, puedo ejecutar los scripts para un ABI específico en tu máquina (necesitaré `ndk.dir` en `local.properties` o la ruta del NDK). 
