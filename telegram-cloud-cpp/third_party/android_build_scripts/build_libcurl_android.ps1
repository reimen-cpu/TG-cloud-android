param(
    [string]$ndk,
    [string]$abi = "arm64-v8a",
    [int]$api = 28,
    [string]$opensslDir,
    [string]$srcPath,
    [string]$outDir
)

$ErrorActionPreference = "Stop"

# Validate required parameters
if (-not $ndk -or -not $opensslDir -or -not $srcPath -or -not $outDir) {
    Write-Error "Usage: -ndk <ndk-path> -opensslDir <openssl-build-dir> -srcPath <curl-source> -outDir <output-dir>"
    Write-Host "Example: .\build_libcurl_android.ps1 -ndk C:\Android\ndk\25.2.9519653 -opensslDir C:\builds\openssl\build_arm64_v8a -srcPath C:\sources\curl-8.7.1 -outDir C:\builds\libcurl"
    exit 1
}

if (-not (Test-Path $srcPath)) {
    Write-Error "libcurl source not found: $srcPath"
    exit 1
}

if (-not (Test-Path $ndk)) {
    Write-Error "NDK path not found: $ndk"
    exit 1
}

if (-not (Test-Path "$opensslDir/installed")) {
    Write-Error "OpenSSL build directory not found: $opensslDir/installed"
    Write-Host "Make sure to build OpenSSL first."
    exit 1
}

$abiNormalized = $abi -replace "-", "_"
$buildDir = Join-Path $outDir "build_$abiNormalized"
$installDir = Join-Path $buildDir "installed"

# Clean previous build
if (Test-Path $buildDir) {
    Write-Host "Cleaning previous build..."
    Remove-Item -Recurse -Force $buildDir
}

New-Item -ItemType Directory -Force -Path $buildDir | Out-Null
New-Item -ItemType Directory -Force -Path $installDir | Out-Null

$toolchain = Join-Path $ndk "build/cmake/android.toolchain.cmake"
if (-not (Test-Path $toolchain)) {
    Write-Error "CMake toolchain not found: $toolchain"
    exit 1
}

# Check if CMake is available
$cmakePath = Get-Command cmake -ErrorAction SilentlyContinue
if (-not $cmakePath) {
    Write-Error "CMake is not installed. Download from: https://cmake.org/download/"
    exit 1
}

Push-Location $buildDir

try {
    Write-Host "Configuring libcurl for $abi (API $api)..."
    Write-Host "OpenSSL: $opensslDir/installed"
    
    $cmakeArgs = @(
        "-G", "Ninja",
        "-DCMAKE_TOOLCHAIN_FILE=$toolchain",
        "-DANDROID_ABI=$abi",
        "-DANDROID_PLATFORM=android-$api",
        "-DCMAKE_BUILD_TYPE=Release",
        "-DCMAKE_INSTALL_PREFIX=$installDir",
        "-DBUILD_CURL_EXE=OFF",
        "-DBUILD_SHARED_LIBS=OFF",
        "-DCURL_STATICLIB=ON",
        "-DCURL_USE_OPENSSL=ON",
        "-DOPENSSL_ROOT_DIR=$opensslDir/installed",
        "-DOPENSSL_INCLUDE_DIR=$opensslDir/installed/include",
        "-DOPENSSL_CRYPTO_LIBRARY=$opensslDir/installed/lib/libcrypto.a",
        "-DOPENSSL_SSL_LIBRARY=$opensslDir/installed/lib/libssl.a",
        "-DCURL_DISABLE_LDAP=ON",
        "-DCURL_DISABLE_LDAPS=ON",
        "-DCURL_CA_BUNDLE=none",
        "-DCURL_CA_PATH=none",
        "-S", $srcPath,
        "-B", "."
    )
    
    & cmake @cmakeArgs
    
    if ($LASTEXITCODE -ne 0) {
        throw "CMake configuration failed with exit code: $LASTEXITCODE"
    }
    
    Write-Host "Building libcurl..."
    & cmake --build . --config Release --parallel $([Environment]::ProcessorCount)
    
    if ($LASTEXITCODE -ne 0) {
        throw "Build failed with exit code: $LASTEXITCODE"
    }
    
    Write-Host "Installing libcurl..."
    & cmake --install . --config Release
    
    if ($LASTEXITCODE -ne 0) {
        throw "Install failed with exit code: $LASTEXITCODE"
    }
    
    Write-Host "`n✓ libcurl built successfully"
    Write-Host "Location: $installDir"
    Write-Host "Include: $installDir/include"
    Write-Host "Libs: $installDir/lib"
    
} catch {
    Write-Error "Error building libcurl: $_"
    Pop-Location
    exit 1
}

Pop-Location

    Remove-Item -Recurse -Force $buildDir
}

New-Item -ItemType Directory -Force -Path $buildDir | Out-Null
New-Item -ItemType Directory -Force -Path $installDir | Out-Null

$toolchain = Join-Path $ndk "build/cmake/android.toolchain.cmake"
if (-not (Test-Path $toolchain)) {
    Write-Error "CMake toolchain not found: $toolchain"
    exit 1
}

# Check if CMake is available
$cmakePath = Get-Command cmake -ErrorAction SilentlyContinue
if (-not $cmakePath) {
    Write-Error "CMake is not installed. Download from: https://cmake.org/download/"
    exit 1
}

Push-Location $buildDir

try {
    Write-Host "Configuring libcurl for $abi (API $api)..."
    Write-Host "OpenSSL: $opensslDir/installed"
    
    $cmakeArgs = @(
        "-G", "Ninja",
        "-DCMAKE_TOOLCHAIN_FILE=$toolchain",
        "-DANDROID_ABI=$abi",
        "-DANDROID_PLATFORM=android-$api",
        "-DCMAKE_BUILD_TYPE=Release",
        "-DCMAKE_INSTALL_PREFIX=$installDir",
        "-DBUILD_CURL_EXE=OFF",
        "-DBUILD_SHARED_LIBS=OFF",
        "-DCURL_STATICLIB=ON",
        "-DCURL_USE_OPENSSL=ON",
        "-DOPENSSL_ROOT_DIR=$opensslDir/installed",
        "-DOPENSSL_INCLUDE_DIR=$opensslDir/installed/include",
        "-DOPENSSL_CRYPTO_LIBRARY=$opensslDir/installed/lib/libcrypto.a",
        "-DOPENSSL_SSL_LIBRARY=$opensslDir/installed/lib/libssl.a",
        "-DCURL_DISABLE_LDAP=ON",
        "-DCURL_DISABLE_LDAPS=ON",
        "-DCURL_CA_BUNDLE=none",
        "-DCURL_CA_PATH=none",
        "-S", $srcPath,
        "-B", "."
    )
    
    & cmake @cmakeArgs
    
    if ($LASTEXITCODE -ne 0) {
        throw "CMake configuration failed with exit code: $LASTEXITCODE"
    }
    
    Write-Host "Building libcurl..."
    & cmake --build . --config Release --parallel $([Environment]::ProcessorCount)
    
    if ($LASTEXITCODE -ne 0) {
        throw "Build failed with exit code: $LASTEXITCODE"
    }
    
    Write-Host "Installing libcurl..."
    & cmake --install . --config Release
    
    if ($LASTEXITCODE -ne 0) {
        throw "Install failed with exit code: $LASTEXITCODE"
    }
    
    Write-Host "`n✓ libcurl built successfully"
    Write-Host "Location: $installDir"
    Write-Host "Include: $installDir/include"
    Write-Host "Libs: $installDir/lib"
    
} catch {
    Write-Error "Error building libcurl: $_"
    Pop-Location
    exit 1
}

Pop-Location
