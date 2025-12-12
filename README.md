code
Markdown
# Telegram Cloud Android

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Android](https://img.shields.io/badge/Android-9.0%2B-green.svg)](https://www.android.com/)

Aplicación Android para gestionar archivos en la nube usando Telegram como backend. **Tu nube, tus reglas.**

## 🚀 Características Principales

### Sin Límites de Tamaño ni Almacenamiento

**⚠️ IMPORTANTE: Esta aplicación NO tiene límites de tamaño de archivo ni de almacenamiento.**

- **Archivos grandes**: Utiliza subida por fragmentos (chunked upload) de 4MB para archivos de cualquier tamaño.
- **Almacenamiento ilimitado**: Telegram no impone límites prácticos de almacenamiento.
- **Múltiples bots**: Soporte para múltiples tokens de bot para mayor velocidad.
- **Subida paralela**: Los fragmentos se suben simultáneamente.

### Funcionalidades

- 📤 **Subida y descarga** nativa.
- 🖼️ **Galería de medios** con sincronización automática.
- 🔐 **Backups cifrados** y base de datos segura (SQLCipher).
- 🔗 **Archivos .link** para compartir contenido protegido.
- 📱 **Interfaz Material Design 3** (Jetpack Compose).
- 🎬 **Reproductor de video** integrado (ExoPlayer).

## 📋 Requisitos

- **Android 9.0 (API 28)** o superior.
- **Bot de Telegram** (Token obtenido de @BotFather).
- **Conexión a Internet**.

### Para Compilar desde Código Fuente

- **Android SDK** con API 28+.
- **Android NDK** (Versión recomendada: **r25c**).
- **CMake 3.22+**.
- **Linux/WSL** (Ubuntu 22.04+ recomendado).
- **Paquetes**: `git`, `wget`, `tar`, `perl`, `build-essential`, `tcl`, `dos2unix`.

## 📦 Instalación

### Opción 1: Descargar APK (Recomendado)
Ve a la sección [Releases]([https://github.com/reimen-cpu/telegram-cloud-android/releases](https://github.com/reimen-cpu/TG-cloud-android/releases/tag/V1.0.0)) y descarga la última versión.

### Opción 2: Compilación Manual
Sigue las instrucciones a continuación.

---

## 🔨 Guía de Compilación Manual

Debido a la complejidad de las dependencias nativas (C++), sigue estos pasos estrictamente en orden.

### 1. Preparar Entorno

Instala las herramientas necesarias y configura las rutas. Ajusta `ANDROID_NDK_HOME` si tu versión es diferente.

```bash
# Instalar dependencias del sistema
sudo apt-get update
sudo apt-get install -y git wget tar perl build-essential tcl dos2unix
```

# Configurar variables (Ajusta la ruta del NDK según tu instalación)

```bash
export ANDROID_HOME="$HOME/android-sdk"
export ANDROID_NDK_HOME="$HOME/android-sdk/ndk/25.2.9519653"
export API=28
```

2. Crear Wrapper para CMake
Este paso es necesario para inyectar configuraciones que los scripts originales no contemplan (como rutas de OpenSSL estáticas y correcciones para Ninja).
Copia y pega este bloque completo en tu terminal:

```bash
mkdir -p "$HOME/cmake-wrap"

cat > "$HOME/cmake-wrap/cmake" << 'EOF'
#!/bin/bash
# Wrapper para corregir compilación en Android NDK

for arg in "$@"; do
  # Corregir error de Ninja: "-j" vacío -> "-jN"
  if [ "$arg" = "--build" ]; then
    exec /usr/bin/cmake --build . -- -j$(nproc)
  fi
  # Corregir error de Ninja: "--config Release" no soportado
  if [ "$arg" = "--install" ]; then
    exec /usr/bin/cmake --install .
  fi
done

# Inyectar rutas de OpenSSL y forzar librerías estáticas
exec /usr/bin/cmake \
  -DBUILD_SHARED_LIBS=OFF \
  -DCMAKE_INSTALL_LIBDIR=lib \
  -DOPENSSL_ROOT_DIR="$OPENSSL_ROOT_DIR" \
  -DOPENSSL_INCLUDE_DIR="$OPENSSL_ROOT_DIR/include" \
  -DOPENSSL_CRYPTO_LIBRARY="$OPENSSL_ROOT_DIR/lib/libcrypto.a" \
  -DOPENSSL_SSL_LIBRARY="$OPENSSL_ROOT_DIR/lib/libssl.a" \
  "$@"
EOF

chmod +x "$HOME/cmake-wrap/cmake"
export PATH="$HOME/cmake-wrap:$PATH"
```


3. Descargar y Preparar Código Fuente
Descarga las librerías y prepara SQLCipher (que requiere un paso manual de generación de código).


```bash
mkdir -p $HOME/android-native-sources
cd $HOME/android-native-sources

# Descargar OpenSSL y Curl
wget https://www.openssl.org/source/openssl-3.2.0.tar.gz && tar xf openssl-3.2.0.tar.gz
wget https://curl.se/download/curl-8.7.1.tar.gz && tar xf curl-8.7.1.tar.gz

# Descargar y Preparar SQLCipher
git clone https://github.com/sqlcipher/sqlcipher.git
cd sqlcipher
./configure
make sqlite3.c

find $HOME/android-native-sources/openssl-3.2.0 -type f -exec dos2unix {} \;
```

Ahora creamos el archivo de configuración CMakeLists.txt que SQLCipher necesita:

```bash
cat > CMakeLists.txt << 'EOF'
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
```

4. Parchear Scripts de Compilación
Para evitar errores en dispositivos antiguos (ARMv7), debemos desactivar el ensamblador en OpenSSL. Modificamos el script original para permitir inyección de opciones.

```bash
cd /mnt/c/Users/Lenovo/Desktop/jugar/prueba-github
sed -i "s|./Configure|./Configure \$OPENSSL_OPTS -fPIC|g" telegram-cloud-cpp/third_party/android_build_scripts/build_openssl_android.sh
```

5. Compilar Librerías Nativas
Ejecuta este bloque para compilar OpenSSL, Libcurl y SQLCipher para ambas arquitecturas (arm64-v8a y armeabi-v7a).

```bash
mkdir -p $HOME/android-native-builds/{openssl,libcurl,sqlcipher}

for ABI in arm64-v8a armeabi-v7a; do
  echo ">>> Compilando para $ABI..."
  
  # Definir opciones específicas para ARMv7
  export OPENSSL_OPTS=""
  if [ "$ABI" == "armeabi-v7a" ]; then
      export OPENSSL_OPTS="no-asm"
  fi

  # 1. Compilar OpenSSL
  ./telegram-cloud-cpp/third_party/android_build_scripts/build_openssl_android.sh \
    -ndk "$ANDROID_NDK_HOME" -abi "$ABI" -api "$API" \
    -srcPath "$HOME/android-native-sources/openssl-3.2.0" \
    -outDir "$HOME/android-native-builds/openssl"

### Nota Importante sobre ABIs y Rutas

#Los scripts originales esperan rutas con **guion alto** (`build-armeabi-v7a`), pero la #compilación de OpenSSL genera carpetas con **guion bajo** (`build_armeabi_v7a`).  

#Para corregirlo automáticamente antes de compilar Libcurl o SQLCipher:
# Renombrar carpetas OpenSSL de guion bajo a guion alto
cd $HOME/android-native-builds/openssl

if [ "$ABI" == "armeabi-v7a" ] && [ -d "build_armeabi_v7a" ] && [ ! -d "build-armeabi-v7a" ]; then
    mv build_armeabi_v7a build-armeabi-v7a
fi

if [ "$ABI" == "arm64-v8a" ] && [ -d "build_arm64_v8a" ] && [ ! -d "build-arm64-v8a" ]; then
    mv build_arm64_v8a build-arm64-v8a
fi

# Actualizar variable para los scripts
export OPENSSL_ROOT_DIR="$HOME/android-native-builds/openssl/build-$ABI/installed"
cd -

  # 2. Compilar Libcurl
  ./telegram-cloud-cpp/third_party/android_build_scripts/build_libcurl_android.sh \
    -ndk "$ANDROID_NDK_HOME" -abi "$ABI" -api "$API" \
    -opensslDir "$OPENSSL_ROOT_DIR" \
    -srcPath "$HOME/android-native-sources/curl-8.7.1" \
    -outDir "$HOME/android-native-builds/libcurl"

  # Mover librería si se instaló en lib64 por error
  if [ -f "$HOME/android-native-builds/libcurl/build_${ABI}/installed/lib64/libcurl.a" ]; then
      mkdir -p "$HOME/android-native-builds/libcurl/build_${ABI}/installed/lib"
      cp "$HOME/android-native-builds/libcurl/build_${ABI}/installed/lib64/libcurl.a" \
         "$HOME/android-native-builds/libcurl/build_${ABI}/installed/lib/"
  fi

  # 3. Compilar SQLCipher
  ./telegram-cloud-cpp/third_party/android_build_scripts/build_sqlcipher_android.sh \
    -ndk "$ANDROID_NDK_HOME" -abi "$ABI" -api "$API" \
    -opensslDir "$OPENSSL_ROOT_DIR" \
    -srcPath "$HOME/android-native-sources/sqlcipher" \
    -outDir "$HOME/android-native-builds/sqlcipher"
done
```

6. Generar APK
Configura Gradle con las rutas exactas de las librerías compiladas y genera la aplicación.

```bash
cat > local.properties <<EOF
sdk.dir=$ANDROID_HOME
ndk.dir=$ANDROID_NDK_HOME
native.openssl.arm64-v8a=/home/enovo/android-native-builds/openssl/build_arm64_v8a/installed
native.openssl.armeabi-v7a=/home/enovo/android-native-builds/openssl/build_armeabi_v7a/installed
native.curl.arm64-v8a=/home/enovo/android-native-builds/libcurl/build_arm64_v8a/installed
native.curl.armeabi-v7a=/home/enovo/android-native-builds/libcurl/build_armeabi_v7a/installed
native.sqlcipher.arm64-v8a=/home/enovo/android-native-builds/sqlcipher/build_arm64_v8a/installed
native.sqlcipher.armeabi-v7a=/home/enovo/android-native-builds/sqlcipher/build_armeabi_v7a/installed
EOF

# Corregir formato de archivo gradlew (para WSL)
dos2unix android/gradlew
chmod +x android/gradlew

# Compilar
cd android
./gradlew assembleDebug
```


La APK aparecerá en: android/app/build/outputs/apk/debug/app-debug.apk

📁 Estructura del Proyecto

```bash
telegram-cloud-android/
├── README.md                      # Instrucciones
├── android/                       # Aplicación Android (Kotlin)
├── telegram-cloud-cpp/            # Núcleo nativo (C++)
└── scripts/                       # Scripts (Experimental)
```

🤝 Contribuir

Haz Fork del repositorio.

Crea una rama (git checkout -b feature/NuevaFeature).
Envía un Pull Request.

📝 Licencia

GNU General Public License v3.0 - ver archivo LICENSE.

Tu nube, tus reglas. 🚀

