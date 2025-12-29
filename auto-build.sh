#!/bin/bash
set -e

# ==============================================================================
# F-Droid Reproducible Build Preparation Script
# ==============================================================================
# Automates the manual compilation steps described in README.md.
# Features:
# - Progress indicators and detailed status logging
# - Local caching of source files to avoid re-downloading
# - Error handling and cleanup
# - Generates android/local.properties automatically
# ==============================================================================

# --- Helpers for UI/Logging ---

COLOR_RESET='\033[0m'
COLOR_GREEN='\033[0;32m'
COLOR_BLUE='\033[0;34m'
COLOR_RED='\033[0;31m'
COLOR_CYAN='\033[0;36m'

log_info() {
    echo -e "${COLOR_BLUE}[INFO]${COLOR_RESET} $1"
}

log_success() {
    echo -e "${COLOR_GREEN}[SUCCESS]${COLOR_RESET} $1"
}

log_error() {
    echo -e "${COLOR_RED}[ERROR]${COLOR_RESET} $1" >&2
}

log_step() {
    echo -e "\n${COLOR_CYAN}>>> $1${COLOR_RESET}"
}

# Spinner function for long running commands
# Usage: run_with_spinner "Message to display" command arg1 arg2 ...
run_with_spinner() {
    local message="$1"
    shift
    
    # Run command in background and capture output to a log file
    local log_file=$(mktemp)
    "$@" > "$log_file" 2>&1 &
    local pid=$!
    
    local delay=0.1
    local spinstr='|/-\'
    
    echo -n "  $message  "
    
    while kill -0 $pid 2>/dev/null; do
        local temp=${spinstr#?}
        printf " [%c]  " "$spinstr"
        local spinstr=$temp${spinstr%"$temp"}
        sleep $delay
        printf "\b\b\b\b\b\b"
    done
    wait $pid
    local exit_code=$?
    
    if [ $exit_code -eq 0 ]; then
        printf " ${COLOR_GREEN}[DONE]${COLOR_RESET}\n"
        rm "$log_file"
        return 0
    else
        printf " ${COLOR_RED}[FAILED]${COLOR_RESET}\n"
        echo -e "${COLOR_RED}Command failed. Output tail:${COLOR_RESET}"
        tail -n 20 "$log_file"
        rm "$log_file"
        return $exit_code
    fi
}

# Trap to clean up on exit/error
cleanup() {
    if [ $? -ne 0 ]; then
        log_error "Build failed! Check the output above for details."
    fi
}
trap cleanup EXIT

# 1. Environment Configuration
# ------------------------------------------------------------------------------

log_step "Configuring Environment..."

export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"

# Detect NDK
if [ -z "$ANDROID_NDK_HOME" ]; then
    if [ -n "$ANDROID_NDK" ]; then
         export ANDROID_NDK_HOME="$ANDROID_NDK"
    elif [ -d "$ANDROID_HOME/ndk/25.2.9519653" ]; then
        export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/25.2.9519653"
    else
         log_info "ANDROID_NDK_HOME not explicitly set. Searching in default location..."
         FOUND_NDK=$(ls -d $ANDROID_HOME/ndk/* 2>/dev/null | head -n 1)
         if [ -n "$FOUND_NDK" ]; then
             export ANDROID_NDK_HOME="$FOUND_NDK"
             log_success "Found NDK: $ANDROID_NDK_HOME"
         else
             log_error "ANDROID_NDK_HOME not found. Please set it manually."
             exit 1
         fi
    fi
fi

if [ -z "$API" ]; then
    export API=28
fi

export PROJECT_ROOT="$(pwd)"
export WORK_DIR="$PROJECT_ROOT/native-build-work"

log_info "Using settings:"
echo "  ANDROID_HOME:     $ANDROID_HOME"
echo "  ANDROID_NDK_HOME: $ANDROID_NDK_HOME"
echo "  PROJECT_ROOT:     $PROJECT_ROOT"
echo "  WORK_DIR:         $WORK_DIR"
echo "  API Level:        $API"

# Clean work directory
rm -rf "$WORK_DIR"
mkdir -p "$WORK_DIR/sources"
mkdir -p "$WORK_DIR/builds"

# 2. CMake Wrapper
# ------------------------------------------------------------------------------
log_step "Setting up CMake Wrapper..."

mkdir -p "$WORK_DIR/cmake-wrap"
cat > "$WORK_DIR/cmake-wrap/cmake" << 'EOF'
#!/bin/bash
# Wrapper to fix compilation on Android NDK

for arg in "$@"; do
  # Fix Ninja error: "-j" empty -> "-jN"
  if [ "$arg" = "--build" ]; then
    exec /usr/bin/cmake --build . -- -j$(nproc)
  fi
  # Fix Ninja error: "--config Release" not supported
  if [ "$arg" = "--install" ]; then
    exec /usr/bin/cmake --install .
  fi
done

# Inject OpenSSL paths and force static libraries
# Note: we use exec to replace the shell with cmake
exec /usr/bin/cmake \
  -DBUILD_SHARED_LIBS=OFF \
  -DCMAKE_INSTALL_LIBDIR=lib \
  -DOPENSSL_ROOT_DIR="$OPENSSL_ROOT_DIR" \
  -DOPENSSL_INCLUDE_DIR="$OPENSSL_ROOT_DIR/include" \
  -DOPENSSL_CRYPTO_LIBRARY="$OPENSSL_ROOT_DIR/lib/libcrypto.a" \
  -DOPENSSL_SSL_LIBRARY="$OPENSSL_ROOT_DIR/lib/libssl.a" \
  "$@"
EOF

chmod +x "$WORK_DIR/cmake-wrap/cmake"
export PATH="$WORK_DIR/cmake-wrap:$PATH"
log_success "CMake wrapper created."

# 3. Download and Prepare Sources
# ------------------------------------------------------------------------------
log_step "Preparing Sources..."

cd "$WORK_DIR/sources"
LOCAL_CACHE="${LOCAL_NATIVE_SOURCES:-$HOME/android-native-sources}"

# --- OpenSSL ---
if [ -f "$LOCAL_CACHE/openssl-3.2.0.tar.gz" ]; then
    log_info "Using local OpenSSL from cache."
    run_with_spinner "Copying OpenSSL..." cp "$LOCAL_CACHE/openssl-3.2.0.tar.gz" .
else
    log_info "Downloading OpenSSL..."
    run_with_spinner "Downloading..." wget -q https://www.openssl.org/source/openssl-3.2.0.tar.gz
fi
run_with_spinner "Extracting OpenSSL..." tar xf openssl-3.2.0.tar.gz

# --- Curl ---
if [ -f "$LOCAL_CACHE/curl-8.7.1.tar.gz" ]; then
    log_info "Using local Curl from cache."
    run_with_spinner "Copying Curl..." cp "$LOCAL_CACHE/curl-8.7.1.tar.gz" .
else
    log_info "Downloading Curl..."
    run_with_spinner "Downloading..." wget -q https://curl.se/download/curl-8.7.1.tar.gz
fi
run_with_spinner "Extracting Curl..." tar xf curl-8.7.1.tar.gz

# --- SQLCipher ---
if [ -d "$LOCAL_CACHE/sqlcipher" ]; then
    log_info "Using local SQLCipher from cache."
    run_with_spinner "Copying SQLCipher..." cp -r "$LOCAL_CACHE/sqlcipher" .
else
    log_info "Cloning SQLCipher..."
    run_with_spinner "Git Clone..." git clone https://github.com/sqlcipher/sqlcipher.git
fi

log_step "Configuring SQLCipher (generating sqlite3.c)..."
cd sqlcipher
run_with_spinner "Running ./configure..." ./configure
run_with_spinner "Running make sqlite3.c..." make sqlite3.c
cd ..

log_step "Applying patches..."

# Define helper for patching to avoid pipe issues with run_with_spinner
patch_openssl_crlf() {
    find "$WORK_DIR/sources/openssl-3.2.0" -type f -print0 | xargs -0 sed -i 's/\r$//'
}

# Optimize find/sed for speed - only patch .c, .h, .cpp files or just run blindly but verbose
log_info "Fixing CRLF in OpenSSL sources (this might take a few seconds)..."
run_with_spinner "Patching CRLF..." patch_openssl_crlf

# Create CMakeLists.txt for SQLCipher
cat > "$WORK_DIR/sources/sqlcipher/CMakeLists.txt" << 'EOF'
cmake_minimum_required(VERSION 3.22)
project(sqlcipher C)
find_package(OpenSSL REQUIRED)
add_library(sqlcipher STATIC sqlite3.c)
target_include_directories(sqlcipher PUBLIC ${OPENSSL_INCLUDE_DIR} ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_definitions(sqlcipher PRIVATE
    -DSQLITE_HAS_CODEC -DSQLITE_TEMP_STORE=2 -DSQLITE_ENABLE_JSON1
    -DSQLITE_ENABLE_FTS3 -DSQLITE_ENABLE_FTS3_PARENTHESIS -DSQLITE_ENABLE_FTS5
    -DSQLITE_ENABLE_RTREE -DSQLCIPHER_CRYPTO_OPENSSL -DANDROID
    -DSQLITE_EXTRA_INIT=sqlcipher_extra_init
    -DSQLITE_EXTRA_SHUTDOWN=sqlcipher_extra_shutdown
)
target_link_libraries(sqlcipher Private OpenSSL::Crypto)
install(TARGETS sqlcipher ARCHIVE DESTINATION lib)
install(FILES sqlite3.h DESTINATION include)
EOF
log_success "SQLCipher CMakeLists.txt generated."

# 4. Patch Build Scripts
# ------------------------------------------------------------------------------
log_step "Patching Repo Build Scripts..."

cd "$PROJECT_ROOT"
# We make a backup before sed ensure idempotency if run multiple times locally? 
# Actually git checkout or reset is better, but this is a build script. 
# We'll just run sed.
sed -i "s|./Configure|./Configure \$OPENSSL_OPTS -fPIC|g" telegram-cloud-cpp/third_party/android_build_scripts/build_openssl_android.sh
log_success "Scripts patched."


# 5. Compile
# ------------------------------------------------------------------------------
log_step "Starting Native Compilation..."

mkdir -p "$WORK_DIR/builds/openssl"
mkdir -p "$WORK_DIR/builds/libcurl"
mkdir -p "$WORK_DIR/builds/sqlcipher"

for ABI in arm64-v8a armeabi-v7a; do
  log_info "---------------------------------------------------"
  log_info "Building for architecture: $ABI"
  log_info "---------------------------------------------------"
  
  export OPENSSL_OPTS=""
  if [ "$ABI" == "armeabi-v7a" ]; then
      export OPENSSL_OPTS="no-asm"
  fi

  # 5.1 Compile OpenSSL
  run_with_spinner "Compiling OpenSSL ($ABI)..." \
    "$PROJECT_ROOT/telegram-cloud-cpp/third_party/android_build_scripts/build_openssl_android.sh" \
    -ndk "$ANDROID_NDK_HOME" -abi "$ABI" -api "$API" \
    -srcPath "$WORK_DIR/sources/openssl-3.2.0" \
    -outDir "$WORK_DIR/builds/openssl"

  # Fix Paths (underscore vs hyphen)
  cd "$WORK_DIR/builds/openssl"
  if [ "$ABI" == "armeabi-v7a" ] && [ -d "build_armeabi_v7a" ] && [ ! -d "build-armeabi-v7a" ]; then
      mv build_armeabi_v7a build-armeabi-v7a
  fi
  if [ "$ABI" == "arm64-v8a" ] && [ -d "build_arm64_v8a" ] && [ ! -d "build-arm64-v8a" ]; then
      mv build_arm64_v8a build-arm64-v8a
  fi
  
  export OPENSSL_ROOT_DIR="$WORK_DIR/builds/openssl/build-$ABI/installed"
  
  # 5.2 Compile Libcurl
  run_with_spinner "Compiling Libcurl ($ABI)..." \
  "$PROJECT_ROOT/telegram-cloud-cpp/third_party/android_build_scripts/build_libcurl_android.sh" \
    -ndk "$ANDROID_NDK_HOME" -abi "$ABI" -api "$API" \
    -opensslDir "$OPENSSL_ROOT_DIR" \
    -srcPath "$WORK_DIR/sources/curl-8.7.1" \
    -outDir "$WORK_DIR/builds/libcurl"

  # Move lib64 if exists
  CURL_INSTALL_DIR="$WORK_DIR/builds/libcurl/build_${ABI}/installed"
  if [ -f "$CURL_INSTALL_DIR/lib64/libcurl.a" ]; then
      mkdir -p "$CURL_INSTALL_DIR/lib"
      cp "$CURL_INSTALL_DIR/lib64/libcurl.a" "$CURL_INSTALL_DIR/lib/"
  fi

  # 5.3 Compile SQLCipher
  run_with_spinner "Compiling SQLCipher ($ABI)..." \
  "$PROJECT_ROOT/telegram-cloud-cpp/third_party/android_build_scripts/build_sqlcipher_android.sh" \
    -ndk "$ANDROID_NDK_HOME" -abi "$ABI" -api "$API" \
    -opensslDir "$OPENSSL_ROOT_DIR" \
    -srcPath "$WORK_DIR/sources/sqlcipher" \
    -outDir "$WORK_DIR/builds/sqlcipher"

done

# 6. Generate local.properties
# ------------------------------------------------------------------------------
log_step "Finalizing..."

cd "$PROJECT_ROOT"

# Ensure we use the correct SDK location for Gradle
cat > android/local.properties <<EOF
sdk.dir=$ANDROID_HOME
ndk.dir=$ANDROID_NDK_HOME
native.openssl.arm64-v8a=$WORK_DIR/builds/openssl/build_arm64_v8a/installed
native.openssl.armeabi-v7a=$WORK_DIR/builds/openssl/build_armeabi_v7a/installed
native.curl.arm64-v8a=$WORK_DIR/builds/libcurl/build_arm64_v8a/installed
native.curl.armeabi-v7a=$WORK_DIR/builds/libcurl/build_armeabi_v7a/installed
native.sqlcipher.arm64-v8a=$WORK_DIR/builds/sqlcipher/build_arm64_v8a/installed
native.sqlcipher.armeabi-v7a=$WORK_DIR/builds/sqlcipher/build_armeabi_v7a/installed
EOF

log_success "local.properties generated in 'android/local.properties'"
log_success "Build Preparation Complete! You can now run './gradlew assembleDebug' in the android/ directory."
