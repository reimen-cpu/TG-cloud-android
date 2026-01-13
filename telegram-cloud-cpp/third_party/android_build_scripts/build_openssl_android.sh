#!/usr/bin/env bash
set -euo pipefail

# OpenSSL build script for Android using Configure + make (native method)
# This script cross-compiles OpenSSL for Android using the NDK

# Default values
NDK=""
ABI="arm64-v8a"
API=28
SRC_PATH=""
OUT_DIR=""

# Parse arguments
while [[ $# -gt 0 ]]; do
  case $1 in
    -ndk)
      NDK="$2"
      shift 2
      ;;
    -abi)
      ABI="$2"
      shift 2
      ;;
    -api)
      API="$2"
      shift 2
      ;;
    -srcPath)
      SRC_PATH="$2"
      shift 2
      ;;
    -outDir)
      OUT_DIR="$2"
      shift 2
      ;;
    *)
      echo "Unknown option: $1"
      echo "Usage: $0 -ndk <ndk-path> -abi <abi> -api <api-level> -srcPath <openssl-src> -outDir <output-dir>"
      exit 1
      ;;
  esac
done

# Validate required parameters
if [ -z "$NDK" ] || [ -z "$SRC_PATH" ] || [ -z "$OUT_DIR" ]; then
  echo "Error: Missing required parameters"
  echo "Usage: $0 -ndk <ndk-path> -abi <abi> -api <api-level> -srcPath <openssl-src> -outDir <output-dir>"
  echo ""
  echo "Example:"
  echo "  $0 -ndk /path/to/ndk -abi arm64-v8a -api 28 -srcPath /path/to/openssl-3.2.0 -outDir /path/to/output"
  exit 1
fi

# Verify paths exist
if [ ! -d "$NDK" ]; then
  echo "Error: NDK path not found: $NDK"
  exit 1
fi

if [ ! -d "$SRC_PATH" ]; then
  echo "Error: OpenSSL source path not found: $SRC_PATH"
  exit 1
fi

# Map Android ABI to OpenSSL target architecture
case "$ABI" in
  arm64-v8a)
    OPENSSL_TARGET="android-arm64"
    ARCH="aarch64"
    ;;
  armeabi-v7a)
    OPENSSL_TARGET="android-arm"
    ARCH="armv7a"
    ;;
  x86)
    OPENSSL_TARGET="android-x86"
    ARCH="i686"
    ;;
  x86_64)
    OPENSSL_TARGET="android-x86_64"
    ARCH="x86_64"
    ;;
  *)
    echo "Error: Unsupported ABI: $ABI"
    echo "Supported ABIs: arm64-v8a, armeabi-v7a, x86, x86_64"
    exit 1
    ;;
esac

# Normalize ABI for directory names (replace - with _)
ABI_NORMALIZED="${ABI//-/_}"
BUILD_DIR="$OUT_DIR/build_$ABI_NORMALIZED"
INSTALL_DIR="$BUILD_DIR/installed"

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "OpenSSL Build Configuration"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "NDK:           $NDK"
echo "ABI:           $ABI"
echo "API Level:     $API"
echo "OpenSSL Target: $OPENSSL_TARGET"
echo "Architecture:  $ARCH"
echo "Source:        $SRC_PATH"
echo "Build Dir:     $BUILD_DIR"
echo "Install Dir:   $INSTALL_DIR"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# Clean previous build
if [ -d "$BUILD_DIR" ]; then
  echo "Cleaning previous build..."
  rm -rf "$BUILD_DIR"
fi

# Create build directory and copy sources (OpenSSL configures in-place)
mkdir -p "$BUILD_DIR"
echo "Copying OpenSSL sources to build directory..."
cp -r "$SRC_PATH"/* "$BUILD_DIR/"

# Set up NDK toolchain paths
NDK_TOOLCHAIN="$NDK/toolchains/llvm/prebuilt"

# Detect host OS for NDK toolchain
if [ -d "$NDK_TOOLCHAIN/linux-x86_64" ]; then
  NDK_TOOLCHAIN="$NDK_TOOLCHAIN/linux-x86_64"
elif [ -d "$NDK_TOOLCHAIN/darwin-x86_64" ]; then
  NDK_TOOLCHAIN="$NDK_TOOLCHAIN/darwin-x86_64"
elif [ -d "$NDK_TOOLCHAIN/windows-x86_64" ]; then
  NDK_TOOLCHAIN="$NDK_TOOLCHAIN/windows-x86_64"
else
  echo "Error: Could not find NDK toolchain in $NDK_TOOLCHAIN"
  exit 1
fi

echo "Using NDK toolchain: $NDK_TOOLCHAIN"

# Set up compiler target prefix
case "$ABI" in
  arm64-v8a)
    TARGET_PREFIX="aarch64-linux-android"
    ;;
  armeabi-v7a)
    TARGET_PREFIX="armv7a-linux-androideabi"
    ;;
  x86)
    TARGET_PREFIX="i686-linux-android"
    ;;
  x86_64)
    TARGET_PREFIX="x86_64-linux-android"
    ;;
esac

# Set up environment for cross-compilation
export ANDROID_NDK_ROOT="$NDK"
export ANDROID_NDK_HOME="$NDK"
export PATH="$NDK_TOOLCHAIN/bin:$PATH"

# Set compiler variables (OpenSSL Configure will use these)
export CC="$NDK_TOOLCHAIN/bin/${TARGET_PREFIX}${API}-clang"
export CXX="$NDK_TOOLCHAIN/bin/${TARGET_PREFIX}${API}-clang++"
export AR="$NDK_TOOLCHAIN/bin/llvm-ar"
export RANLIB="$NDK_TOOLCHAIN/bin/llvm-ranlib"

# Verify compiler exists
if [ ! -f "$CC" ]; then
  echo "Error: Compiler not found: $CC"
  echo "Verify NDK installation and API level"
  exit 1
fi

echo "Compiler: $CC"
echo "AR: $AR"
echo "RANLIB: $RANLIB"

# Change to build directory
cd "$BUILD_DIR"

# Configure OpenSSL
echo ""
echo "[1/3] Configuring OpenSSL..."
./Configure "${OPENSSL_OPTS:-}" -fPIC "$OPENSSL_TARGET" \
  -D__ANDROID_API__=$API \
  no-shared \
  --prefix="$INSTALL_DIR" \
  --openssldir="$INSTALL_DIR"

if [ $? -ne 0 ]; then
  echo "Error: OpenSSL Configure failed"
  exit 1
fi

# Build OpenSSL
echo ""
echo "[2/3] Building OpenSSL (this may take 10-15 minutes)..."
NPROC=$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)
make -j$NPROC

if [ $? -ne 0 ]; then
  echo "Error: OpenSSL build failed"
  exit 1
fi

# Install OpenSSL
echo ""
echo "[3/3] Installing OpenSSL..."
make install_sw

if [ $? -ne 0 ]; then
  echo "Error: OpenSSL install failed"
  exit 1
fi

# Verify installation
echo ""
echo "Verifying installation..."
if [ ! -f "$INSTALL_DIR/lib/libssl.a" ] || [ ! -f "$INSTALL_DIR/lib/libcrypto.a" ]; then
  echo "Error: OpenSSL libraries not found in $INSTALL_DIR/lib"
  exit 1
fi

if [ ! -d "$INSTALL_DIR/include/openssl" ]; then
  echo "Error: OpenSSL headers not found in $INSTALL_DIR/include"
  exit 1
fi

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "✓ OpenSSL build completed successfully"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "Install location: $INSTALL_DIR"
echo "Libraries:"
echo "  - $INSTALL_DIR/lib/libssl.a"
echo "  - $INSTALL_DIR/lib/libcrypto.a"
echo "Headers: $INSTALL_DIR/include/openssl"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
