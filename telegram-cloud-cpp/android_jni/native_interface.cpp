#include <jni.h>
#include <string>
#include <android/log.h>

static const char* TAG = "TelegramCloud";

extern "C" JNIEXPORT jboolean JNICALL
Java_com_telegram_cloud_NativeLib_nativeInit(JNIEnv* env, jclass /*clazz*/) {
    __android_log_print(ANDROID_LOG_INFO, TAG, "nativeInit called");
    // TODO: initialize core subsystems, configuration, logging, thread pools, etc.
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_telegram_cloud_NativeLib_nativeOpenDatabase(JNIEnv* env, jclass /*clazz*/, jstring jPath, jstring jPassphrase) {
    const char* path = env->GetStringUTFChars(jPath, nullptr);
    const char* pass = jPassphrase ? env->GetStringUTFChars(jPassphrase, nullptr) : nullptr;
    __android_log_print(ANDROID_LOG_INFO, TAG, "nativeOpenDatabase path=%s", path);

    // TODO: call into C++ core to open sqlcipher database using provided passphrase

    if (jPassphrase) env->ReleaseStringUTFChars(jPassphrase, pass);
    env->ReleaseStringUTFChars(jPath, path);
    return JNI_TRUE; // return JNI_FALSE on error
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_telegram_cloud_NativeLib_nativeCloseDatabase(JNIEnv* env, jclass /*clazz*/) {
    __android_log_print(ANDROID_LOG_INFO, TAG, "nativeCloseDatabase called");
    // TODO: close DB
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_telegram_cloud_NativeLib_nativeExportBackup(JNIEnv* env, jclass /*clazz*/, jstring jPath) {
    const char* path = env->GetStringUTFChars(jPath, nullptr);
    __android_log_print(ANDROID_LOG_INFO, TAG, "nativeExportBackup path=%s", path);
    // TODO: export DB/attachments into backup file
    env->ReleaseStringUTFChars(jPath, path);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_telegram_cloud_NativeLib_nativeImportBackup(JNIEnv* env, jclass /*clazz*/, jstring jPath) {
    const char* path = env->GetStringUTFChars(jPath, nullptr);
    __android_log_print(ANDROID_LOG_INFO, TAG, "nativeImportBackup path=%s", path);
    // TODO: import backup file and restore DB/files
    env->ReleaseStringUTFChars(jPath, path);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_telegram_cloud_NativeLib_nativeStartDownload(JNIEnv* env, jclass /*clazz*/, jstring jUrl, jstring jDestPath) {
    const char* url = env->GetStringUTFChars(jUrl, nullptr);
    const char* dest = env->GetStringUTFChars(jDestPath, nullptr);
    __android_log_print(ANDROID_LOG_INFO, TAG, "nativeStartDownload url=%s dest=%s", url, dest);
    // TODO: start download using native downloader and return download id
    env->ReleaseStringUTFChars(jUrl, url);
    env->ReleaseStringUTFChars(jDestPath, dest);
    return -1;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_telegram_cloud_NativeLib_nativeStopDownload(JNIEnv* env, jclass /*clazz*/, jint downloadId) {
    __android_log_print(ANDROID_LOG_INFO, TAG, "nativeStopDownload id=%d", downloadId);
    // TODO: stop download
    return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_telegram_cloud_NativeLib_nativeStartUpload(JNIEnv* env, jclass /*clazz*/, jstring jFilePath, jstring jTarget) {
    const char* filePath = env->GetStringUTFChars(jFilePath, nullptr);
    const char* target = env->GetStringUTFChars(jTarget, nullptr);
    __android_log_print(ANDROID_LOG_INFO, TAG, "nativeStartUpload file=%s target=%s", filePath, target);
    // TODO: start upload and return upload id
    env->ReleaseStringUTFChars(jFilePath, filePath);
    env->ReleaseStringUTFChars(jTarget, target);
    return -1;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_telegram_cloud_NativeLib_nativeGetDownloadStatus(JNIEnv* env, jclass /*clazz*/, jint downloadId) {
    __android_log_print(ANDROID_LOG_INFO, TAG, "nativeGetDownloadStatus id=%d", downloadId);
    // TODO: return JSON string with status/progress
    const char* stub = "{\"status\":\"unknown\",\"progress\":0}";
    return env->NewStringUTF(stub);
}
