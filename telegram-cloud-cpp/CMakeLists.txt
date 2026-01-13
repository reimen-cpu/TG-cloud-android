cmake_minimum_required(VERSION 3.22)
project(TelegramCloudCore LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(BUILD_SHARED_LIBS ON)

# --- 1. Verificación de Entorno Android ---
if(NOT ANDROID)
    message(FATAL_ERROR "Este CMakeLists.txt es para compilación en Android (NDK).")
endif()

message(STATUS ">>> Configurando para ABI: ${ANDROID_ABI}")

# --- 2. Definición de Fuentes ---
set(SOURCES
    src/config.cpp
    src/database.cpp
    src/envmanager.cpp
    src/obfuscated_strings_android.cpp
    src/telegramhandler.cpp
    src/apiserver.cpp
    src/fileuploader.cpp
    src/filedownloader.cpp
    src/chunkedupload.cpp
    src/chunkeddownload.cpp
    src/batchoperations.cpp
    src/uploadprogressmanager.cpp
    src/logger.cpp
    src/universallinkgenerator.cpp
    src/universallinkdownloader.cpp
    src/telegramnotifier.cpp
    src/backupmanager.cpp
    src/tempdownloaddb.cpp
    android_jni/telegram_cloud_jni_wrapper.cpp
)

set(HEADERS
    include/config.h
    include/database.h
    include/envmanager.h
    include/telegramhandler.h
    include/apiserver.h
    include/fileuploader.h
    include/filedownloader.h
    include/chunkedupload.h
    include/uploadprogressmanager.h
    include/logger.h
    include/universallinkgenerator.h
    include/universallinkdownloader.h
    include/telegramnotifier.h
    include/backupmanager.h
    include/tempdownloaddb.h
)

# --- 3. Crear Librería Compartida ---
add_library(telegramcloud_core SHARED ${SOURCES} ${HEADERS})

# --- 4. Directorios de Inclusión ---
target_include_directories(telegramcloud_core PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/json/include
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/httplib
)

# --- 5. Configuración de Librerías Nativas Manuales ---
# Normalizar nombre de carpeta (arm64-v8a -> arm64_v8a)
# Normalizar nombre de carpeta (arm64-v8a -> arm64_v8a)
string(REPLACE "-" "_" ABI_SAFE ${ANDROID_ABI})
string(TOUPPER ${ABI_SAFE} ABI_UPPER)

# Rutas: Usar variables de Gradle si existen, sino fallback a default
if(DEFINED OPENSSL_ROOT_DIR_${ABI_UPPER})
    set(OPENSSL_ROOT "${OPENSSL_ROOT_DIR_${ABI_UPPER}}")
else()
    set(OPENSSL_ROOT "$ENV{HOME}/android-native-builds/openssl/build_${ABI_SAFE}/installed")
endif()

if(DEFINED CURL_ROOT_${ABI_UPPER})
    set(CURL_ROOT "${CURL_ROOT_${ABI_UPPER}}")
else()
    set(CURL_ROOT "$ENV{HOME}/android-native-builds/libcurl/build_${ABI_SAFE}/installed")
endif()

if(DEFINED SQLCIPHER_ROOT_${ABI_UPPER})
    set(SQL_ROOT "${SQLCIPHER_ROOT_${ABI_UPPER}}")
else()
    set(SQL_ROOT "$ENV{HOME}/android-native-builds/sqlcipher/build_${ABI_SAFE}/installed")
endif()

# Includes de librerías
target_include_directories(telegramcloud_core PRIVATE
    ${OPENSSL_ROOT}/include
    ${CURL_ROOT}/include
    ${SQL_ROOT}/include
)

# Definir archivos de librerías estáticas
set(LIB_SSL        "${OPENSSL_ROOT}/lib/libssl.a")
set(LIB_CRYPTO     "${OPENSSL_ROOT}/lib/libcrypto.a")
set(LIB_CURL       "${CURL_ROOT}/lib/libcurl.a")
set(LIB_SQLCIPHER  "${SQL_ROOT}/lib/libsqlcipher.a")

# Verificar existencia (para debug)
message(STATUS "Verificando librerías:")
message(STATUS "  Curl: ${LIB_CURL}")
message(STATUS "  SSL: ${LIB_SSL}")
message(STATUS "  Crypto: ${LIB_CRYPTO}")
message(STATUS "  SQLCipher: ${LIB_SQLCIPHER}")

if(NOT EXISTS "${LIB_CURL}")
    message(FATAL_ERROR "No se encontró libcurl.a en ${LIB_CURL}")
endif()

# --- 6. Enlazado (LINKING) - EL ORDEN ES CRÍTICO ---
# Orden: Consumidor -> Dependencia
# telegramcloud_core -> curl -> sqlcipher -> ssl -> crypto -> zlib -> log

find_library(log-lib log)
find_library(z-lib z) # Librería de compresión del sistema Android

target_link_libraries(telegramcloud_core PRIVATE
    ${LIB_CURL}       # Usa SSL, Crypto, Zlib
    ${LIB_SQLCIPHER}  # Usa Crypto
    ${LIB_SSL}        # Usa Crypto
    ${LIB_CRYPTO}     # Base
    ${z-lib}          # Requerido por Curl y OpenSSL
    ${log-lib}        # Logs de Android
)

# Flags de compilación
target_compile_options(telegramcloud_core PRIVATE -fvisibility=hidden -Wall -Wextra)
target_compile_definitions(telegramcloud_core PRIVATE -DTELEGRAMCLOUD_ANDROID)

