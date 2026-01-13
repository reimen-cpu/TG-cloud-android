#!/usr/bin/env bash
set -euo pipefail

# SQLCipher build script for Android using CMake
# This script cross-compiles SQLCipher for Android using the NDK

# Default values
NDK=""
ABI="arm64-v8a"
API=28
OPENSSL_DIR=""
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
    -opensslDir)
      OPENSSL_DIR="$2"
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
      echo "Usage: $0 -ndk <ndk-path> -abi <abi> -api <api-level> -opensslDir <openssl-dir> -srcPath <sqlcipher-src> -outDir <output-dir>"
      exit 1
      ;;
  esac
done

# Validate required parameters
if [ -z "$NDK" ] || [ -z "$SRC_PATH" ] || [ -z "$OUT_DIR" ]; then
  echo "Error: Missing required parameters"
  echo "Usage: $0 -ndk <ndk-path> -abi <abi> -api <api-level> -opensslDir <openssl-dir> -srcPath <sqlcipher-src> -outDir <output-dir>"
  echo ""
  echo "Example:"
  echo "  $0 -ndk /path/to/ndk -abi arm64-v8a -api 28 -opensslDir /path/to/openssl -srcPath /path/to/sqlcipher -outDir /path/to/output"
  exit 1
fi

# Verify paths exist
if [ ! -d "$NDK" ]; then
  echo "Error: NDK path not found: $NDK"
  exit 1
fi

if [ -n "$OPENSSL_DIR" ] && [ ! -d "$OPENSSL_DIR" ]; then
  echo "Error: OpenSSL directory not found: $OPENSSL_DIR"
  exit 1
fi

if [ ! -d "$SRC_PATH" ]; then
  echo "Error: SQLCipher source path not found: $SRC_PATH"
  exit 1
fi

# Normalize ABI for directory names
ABI_NORMALIZED="${ABI//-/_}"
BUILD_DIR="$OUT_DIR/build_$ABI_NORMALIZED"
INSTALL_DIR="$BUILD_DIR/installed"

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "SQLCipher Build Configuration"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "NDK:         $NDK"
echo "ABI:         $ABI"
echo "API Level:   $API"
echo "OpenSSL:     $OPENSSL_DIR"
echo "Source:      $SRC_PATH"
echo "Build Dir:   $BUILD_DIR"
echo "Install Dir: $INSTALL_DIR"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# Clean and create build directory
if [ -d "$BUILD_DIR" ]; then
  echo "Cleaning previous build..."
  rm -rf "$BUILD_DIR"
fi

mkdir -p "$BUILD_DIR"

# Verify NDK toolchain file exists
TOOLCHAIN="$NDK/build/cmake/android.toolchain.cmake"
if [ ! -f "$TOOLCHAIN" ]; then
  echo "Error: CMake toolchain not found at $TOOLCHAIN"
  exit 1
fi

cd "$BUILD_DIR"

echo ""
echo "[1/3] Configuring SQLCipher with CMake..."

# Build CMake command
CMAKE_ARGS=(
  -G "Ninja"
  -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN"
  -DANDROID_ABI="$ABI"
  -DANDROID_PLATFORM="android-$API"
  -DCMAKE_BUILD_TYPE=Release
  -DSQLITE_HAS_CODEC=ON
  -DCMAKE_INSTALL_PREFIX="$INSTALL_DIR"
  -S "$SRC_PATH"
  -B .
)

# Add OpenSSL if provided
if [ -n "$OPENSSL_DIR" ]; then
  CMAKE_ARGS+=(
    -DSQLCIPHER_CRYPTO_OPENSSL=ON
    -DOPENSSL_ROOT_DIR="$OPENSSL_DIR"
  )
fi

cmake "${CMAKE_ARGS[@]}"

if [ $? -ne 0 ]; then
  echo "Error: CMake configuration failed"
  exit 1
fi

echo ""
echo "[2/3] Building SQLCipher..."
cmake --build . --config Release -- -j

if [ $? -ne 0 ]; then
  echo "Error: Build failed"
  exit 1
fi

echo ""
echo "[3/3] Installing SQLCipher..."
cmake --install . --config Release

if [ $? -ne 0 ]; then
  echo "Error: Install failed"
  exit 1
fi

# Verify installation
echo ""
echo "Verifying installation..."
if [ ! -f "$INSTALL_DIR/lib/libsqlcipher.a" ] && [ ! -f "$INSTALL_DIR/lib/libsqlite3.a" ]; then
  echo "Error: SQLCipher library not found in $INSTALL_DIR/lib"
  exit 1
fi

if [ ! -f "$INSTALL_DIR/include/sqlite3.h" ]; then
  echo "Error: SQLCipher headers not found in $INSTALL_DIR/include"
  exit 1
fi

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "✓ SQLCipher build completed successfully"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "Install location: $INSTALL_DIR"
echo "Library: $INSTALL_DIR/lib/"
echo "Headers: $INSTALL_DIR/include/sqlite3.h"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
