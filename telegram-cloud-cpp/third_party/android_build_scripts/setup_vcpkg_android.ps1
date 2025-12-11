<#
PowerShell helper to configure environment variables and install vcpkg ports for Android (arm64-android).
Usage: run in an elevated PowerShell session in this project root.
#>
param(
    [string]$VcpkgExe = "C:\vcpkg\vcpkg.exe",
    [string]$Ndk = "C:\Android\ndk\25.2.9519653",
    [string]$Triplet = "arm64-android"
)

if (-not (Test-Path $VcpkgExe)) {
    Write-Error "vcpkg executable not found at $VcpkgExe. Adjust the -VcpkgExe parameter or install vcpkg first."
    exit 1
}
if (-not (Test-Path $Ndk)) {
    Write-Error "NDK not found at $Ndk. Adjust the -Ndk parameter or install the Android NDK."
    exit 1
}

# Export environment variables for this session
$env:VCPKG_CHAINLOAD_TOOLCHAIN_FILE = Join-Path $Ndk 'build\cmake\android.toolchain.cmake'
$env:ANDROID_NDK = $Ndk
$env:ANDROID_NDK_ROOT = $Ndk
$env:ANDROID_SDK_ROOT = 'C:\Android'
$env:ANDROID_HOME = 'C:\Android'
$env:VCPKG_DEFAULT_TRIPLET = $Triplet
$env:VCPKG_ROOT = Split-Path -Parent $VcpkgExe

Write-Host "Using vcpkg: $VcpkgExe"
Write-Host "Using NDK: $Ndk"
Write-Host "Triplet: $Triplet"
Write-Host "VCPKG_CHAINLOAD_TOOLCHAIN_FILE = $($env:VCPKG_CHAINLOAD_TOOLCHAIN_FILE)"

# Install sequence
& $VcpkgExe install openssl:$Triplet | Tee-Object -FilePath .\vcpkg_openssl_install.log
& $VcpkgExe install curl[openssl]:$Triplet | Tee-Object -FilePath .\vcpkg_curl_install.log
# sqlcipher may sometimes require additional flags; try install and if fails, user can use source build script
& $VcpkgExe install sqlcipher:$Triplet | Tee-Object -FilePath .\vcpkg_sqlcipher_install.log

Write-Host "vcpkg installation finished. Check the generated log files for details: vcpkg_openssl_install.log, vcpkg_curl_install.log, vcpkg_sqlcipher_install.log"
Write-Host "If installation fails, paste the corresponding log file content here and I'll help debug."

# Print installed path suggestion
$installedDir = Join-Path (Split-Path -Parent $VcpkgExe) "installed\$Triplet"
Write-Host "If successful, point your CMake to: $installedDir (e.g. -DOPENSSL_ROOT_DIR=$installedDir)"

exit 0
