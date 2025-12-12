
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

val localNativeProps = Properties().apply {
    val propsFile = rootProject.file("local.properties")
    if (propsFile.exists()) {
        propsFile.inputStream().use { load(it) }
    }
}

android {

signingConfigs {
    create("release") {
        val keystoreFile = file("../keystore/release-keystore.jks")
        if (keystoreFile.exists()) {
            storeFile = keystoreFile
            storePassword = project.findProperty("RELEASE_STORE_PASSWORD") as String? 
                ?: System.getenv("RELEASE_STORE_PASSWORD")
            keyAlias = project.findProperty("RELEASE_KEY_ALIAS") as String? 
                ?: System.getenv("RELEASE_KEY_ALIAS")
            keyPassword = project.findProperty("RELEASE_KEY_PASSWORD") as String? 
                ?: System.getenv("RELEASE_KEY_PASSWORD")
        }
    }
}

buildTypes {
    getByName("release") {
        val keystoreFile = file("../keystore/release-keystore.jks")
        if (keystoreFile.exists()) {
            signingConfig = signingConfigs.getByName("release")
        }
        isMinifyEnabled = true
        proguardFiles(
            getDefaultProguardFile("proguard-android-optimize.txt"),
            "proguard-rules.pro"
        )
    }
}

    namespace = "com.telegram.cloud"
    compileSdk = 34
    ndkVersion = "25.2.9519653"

    defaultConfig {
        applicationId = "com.telegram.cloud"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }
        ndk {
            // Target common ABIs; add x86_64 if you need emulator support
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        externalNativeBuild {
            cmake {
                val resolveNativePath = { key: String ->
                    val projectValue = project.findProperty(key) as String?
                    val localValue = localNativeProps.getProperty(key)
                    (projectValue ?: localValue)
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?.replace("\\", "/")
                        ?.removeSuffix("/")
                }

                val cmakeKeys = listOf(
                    "OPENSSL_ROOT_DIR" to "native.openssl",
                    "CURL_ROOT" to "native.curl",
                    "SQLCIPHER_ROOT" to "native.sqlcipher"
                )

                val abiList = ndk.abiFilters.takeIf { it.isNotEmpty() } ?: setOf("arm64-v8a")

                abiList.forEach { abi ->
                    val abiToken = abi.uppercase().replace("-", "_")
                    cmakeKeys.forEach { (cmakeKey, propPrefix) ->
                        val propValue = resolveNativePath("$propPrefix.$abi") ?: resolveNativePath(propPrefix)
                        if (propValue != null) {
                            arguments += "-D${cmakeKey}_${abiToken}=$propValue"
                        }
                    }
                }

                val vcpkgRoot: String? = System.getenv("VCPKG_ROOT") ?: System.getenv("VCPKG_ROOT_DIR") ?: System.getenv("VCPKG_HOME")
                if (vcpkgRoot != null) {
                    val vcpkgPath = vcpkgRoot.replace("\\", "/")
                    arguments += "-DOPENSSL_ROOT_DIR=$vcpkgPath/installed/arm64-android"
                    arguments += "-DCURL_ROOT=$vcpkgPath/installed/arm64-android"
                    arguments += "-DSQLCIPHER_ROOT=$vcpkgPath/installed/arm64-android"
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // Configure CMake/NDK integration so we can build the native core from the sibling telegram-cloud-cpp
    externalNativeBuild {
        cmake {
            path = file("../../telegram-cloud-cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")

    // SQLCipher for Android - encrypted database compatible with desktop version
    implementation("net.zetetic:sqlcipher-android:4.6.1@aar")
    implementation("androidx.sqlite:sqlite:2.4.0")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.google.android.material:material:1.12.0")
    
    // Accompanist SwipeRefresh para pull-to-refresh
    implementation("com.google.accompanist:accompanist-swiperefresh:0.32.0")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.11.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    
    // Gson for JSON serialization (chunk progress persistence)
    implementation("com.google.code.gson:gson:2.10.1")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    
    // Coil for image loading in Compose
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("io.coil-kt:coil-video:2.6.0")
    
    // Glide for reliable thumbnail generation (especially videos)
    implementation("com.github.bumptech.glide:glide:4.16.0")
    ksp("com.github.bumptech.glide:ksp:4.16.0")
    
    // Media3 (ExoPlayer) for video streaming
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}


