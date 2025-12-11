#include <jni.h>
#include <memory>
#include <string>
#include <vector>
#include <fstream>
#include <filesystem>
#include <chrono>
#include <mutex>
#include <thread>
#include <atomic>
#include <algorithm>
#include <stdexcept>
#include <android/log.h>

#include "database.h"
#include "telegramhandler.h"
#include "config.h"
#include "backupmanager.h"
#include "envmanager.h"
#include "logger.h"
#include <nlohmann/json.hpp>

static const char* TAG = "TelegramCloudWrapper";

#define JNILOG_INFO(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define JNILOG_ERROR(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define JNILOG_DEBUG(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

using namespace TelegramCloud;
namespace fs = std::filesystem;

enum class TransferDirection {
    DOWNLOAD,
    UPLOAD,
    LINK_DOWNLOAD
};

struct TransferRequest {
    std::string taskId;
    TransferDirection direction = TransferDirection::DOWNLOAD;
    nlohmann::json payload = nlohmann::json::object();
};

static TransferDirection directionFromString(const std::string& value) {
    std::string lower = value;
    std::transform(lower.begin(), lower.end(), lower.begin(), ::tolower);
    if (lower == "upload") return TransferDirection::UPLOAD;
    if (lower == "link_download") return TransferDirection::LINK_DOWNLOAD;
    return TransferDirection::DOWNLOAD;
}

static std::vector<std::string> extractTokens(const nlohmann::json& payload) {
    std::vector<std::string> result;
    if (!payload.is_object()) return result;
    if (!payload.contains("tokens")) return result;
    const auto& field = payload.at("tokens");
    if (!field.is_array()) return result;
    for (const auto& element : field) {
        if (element.is_string()) {
            auto token = element.get<std::string>();
            if (!token.empty()) {
                result.emplace_back(token);
            }
        }
    }
    return result;
}

static std::string extractStringField(const nlohmann::json& payload, const std::string& key) {
    if (!payload.is_object()) return "";
    if (!payload.contains(key)) return "";
    const auto& value = payload.at(key);
    if (!value.is_string()) return "";
    return value.get<std::string>();
}

static TransferRequest parseTransferRequest(const std::string& jsonStr) {
    TransferRequest result;
    try {
        auto src = nlohmann::json::parse(jsonStr);
        result.taskId = src.value("taskId", "");
        result.direction = directionFromString(src.value("direction", "download"));
        if (src.contains("payload")) {
            result.payload = src.at("payload");
        }
    } catch (const std::exception& ex) {
        JNILOG_ERROR("parseTransferRequest: failed to parse JSON: %s", ex.what());
    }
    return result;
}

static void notifyTransferProgress(int nativeId, float percent, const std::string& message);
static void notifyTransferCompleted(int nativeId, const std::string& message);
static void notifyTransferFailed(int nativeId, const std::string& error);
static void performTransferTask(int nativeId, TransferRequest request);

static JavaVM* g_javaVm = nullptr;
static jclass g_dispatcherClass = nullptr;
static jmethodID g_progressMethod = nullptr;
static jmethodID g_completedMethod = nullptr;
static jmethodID g_failedMethod = nullptr;

static std::unique_ptr<Database> g_database;
static std::unique_ptr<TelegramHandler> g_handler;

static std::atomic<int> g_nextNativeId{1};
static std::mutex g_workerMutex;
static const char* DISPATCHER_CLASS = "com/telegram/cloud/native/NativeTransferDispatcher";

struct ScopedUtfChars {
    ScopedUtfChars(JNIEnv* env, jstring str)
        : env(env), str(str) {
        if (str) {
            chars = env->GetStringUTFChars(str, nullptr);
        }
    }

    ~ScopedUtfChars() {
        if (str && chars) {
            env->ReleaseStringUTFChars(str, chars);
        }
    }

    const char* get() const { return chars; }

private:
    JNIEnv* env;
    jstring str;
    const char* chars = nullptr;
};

struct JniEnvScope {
    JniEnvScope() {
        env = nullptr;
        attached = false;
        if (g_javaVm) {
            jint status = g_javaVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
            if (status == JNI_EDETACHED) {
                g_javaVm->AttachCurrentThread(&env, nullptr);
                attached = true;
            }
        }
    }

    ~JniEnvScope() {
        if (attached && g_javaVm) {
            g_javaVm->DetachCurrentThread();
        }
    }

    JNIEnv* get() const { return env; }

private:
    JNIEnv* env;
    bool attached;
};

// Helper to convert jstring to std::string
static std::string jstringToStd(JNIEnv* env, jstring js) {
    if (!js) return std::string();
    const char* s = env->GetStringUTFChars(js, nullptr);
    std::string ret(s);
    env->ReleaseStringUTFChars(js, s);
    return ret;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_telegram_cloud_NativeLib_nativeInit(JNIEnv* env, jclass /*clazz*/) {
    __android_log_print(ANDROID_LOG_INFO, TAG, "wrapper nativeInit called");
    // Initialize config singleton (loads from env or file)
    Config& cfg = Config::instance();
    __android_log_print(ANDROID_LOG_INFO, TAG, "Config DB path=%s", cfg.databasePath().c_str());

    if (env->GetJavaVM(&g_javaVm) != JNI_OK) {
        JNILOG_ERROR("nativeInit: failed to cache JavaVM");
    }

    jclass dispatcher = env->FindClass(DISPATCHER_CLASS);
    if (dispatcher) {
        g_dispatcherClass = reinterpret_cast<jclass>(env->NewGlobalRef(dispatcher));
        g_progressMethod = env->GetStaticMethodID(g_dispatcherClass, "onNativeTransferProgress", "(IFLjava/lang/String;)V");
        g_completedMethod = env->GetStaticMethodID(g_dispatcherClass, "onNativeTransferCompleted", "(ILjava/lang/String;)V");
        g_failedMethod = env->GetStaticMethodID(g_dispatcherClass, "onNativeTransferFailed", "(ILjava/lang/String;)V");
        env->DeleteLocalRef(dispatcher);
    }

    // Instantiate handler
    if (!g_handler) {
        g_handler = std::make_unique<TelegramHandler>();
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_telegram_cloud_NativeLib_nativeOpenDatabase(JNIEnv* env, jclass /*clazz*/, jstring jPath, jstring jPassphrase) {
    std::string path = jstringToStd(env, jPath);
    std::string pass = jstringToStd(env, jPassphrase);
    __android_log_print(ANDROID_LOG_INFO, TAG, "nativeOpenDatabase path=%s", path.c_str());

    if (!g_database) {
        g_database = std::make_unique<Database>();
    }
    bool ok = g_database->initialize(path);
    if (!pass.empty()) {
        g_database->setEncryptionKey(pass);
    }
    if (ok) {
        g_database->setupTables();
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_telegram_cloud_NativeLib_nativeCloseDatabase(JNIEnv* env, jclass /*clazz*/) {
    (void)env;
    __android_log_print(ANDROID_LOG_INFO, TAG, "nativeCloseDatabase called");
    if (g_database) {
        g_database->close();
        g_database.reset();
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_telegram_cloud_NativeLib_nativeExportBackup(JNIEnv* env, jclass /*clazz*/, jstring jPath) {
    std::string path = jstringToStd(env, jPath);
    __android_log_print(ANDROID_LOG_INFO, TAG, "nativeExportBackup path=%s", path.c_str());
    // Simple implementation: copy DB file to target path
    if (!g_database) return JNI_FALSE;
    // TODO: implement proper backup of DB + attachments (here just copy sqlite file)
    // For now, assume DB path is accessible
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_telegram_cloud_NativeLib_nativeImportBackup(JNIEnv* env, jclass /*clazz*/, jstring jPath) {
    std::string archivePath = jstringToStd(env, jPath);
    JNILOG_INFO("nativeImportBackup: archivePath=%s", archivePath.c_str());
    
    // This is a simplified import for Android - expects unzipped backup folder
    // The Android app should unzip the backup first and pass the folder path
    
    try {
        fs::path backupDir(archivePath);
        
        // Check if it's a directory (unzipped backup)
        if (!fs::is_directory(backupDir)) {
            JNILOG_ERROR("nativeImportBackup: Path is not a directory: %s", archivePath.c_str());
            return JNI_FALSE;
        }
        
        // List contents for debugging
        JNILOG_INFO("nativeImportBackup: Listing backup directory contents:");
        for (const auto& entry : fs::directory_iterator(backupDir)) {
            JNILOG_INFO("  - %s", entry.path().filename().string().c_str());
        }
        
        // Check for manifest
        fs::path manifestPath = backupDir / "backup_manifest.json";
        bool encrypted = false;
        
        if (fs::exists(manifestPath)) {
            JNILOG_INFO("nativeImportBackup: Found manifest at %s", manifestPath.string().c_str());
            std::ifstream mf(manifestPath);
            if (mf) {
                std::string content((std::istreambuf_iterator<char>(mf)), std::istreambuf_iterator<char>());
                JNILOG_INFO("nativeImportBackup: Manifest content: %s", content.c_str());
                // Simple JSON parsing for "encrypted":true/false
                if (content.find("\"encrypted\":true") != std::string::npos || 
                    content.find("\"encrypted\": true") != std::string::npos) {
                    encrypted = true;
                }
            }
        } else {
            JNILOG_INFO("nativeImportBackup: No manifest found, assuming unencrypted");
        }
        
        JNILOG_INFO("nativeImportBackup: Backup encrypted=%d", encrypted);
        
        // Check for .env file
        fs::path envPath = encrypted ? (backupDir / ".env.enc") : (backupDir / ".env");
        fs::path dbPath = encrypted ? (backupDir / "telegram_cloud.db.enc") : (backupDir / "database" / "telegram_cloud.db");
        
        // Also check flat structure (no database subfolder)
        if (!fs::exists(dbPath) && !encrypted) {
            dbPath = backupDir / "telegram_cloud.db";
        }
        
        JNILOG_INFO("nativeImportBackup: Looking for env at %s", envPath.string().c_str());
        JNILOG_INFO("nativeImportBackup: Looking for db at %s", dbPath.string().c_str());
        
        bool envExists = fs::exists(envPath);
        bool dbExists = fs::exists(dbPath);
        
        JNILOG_INFO("nativeImportBackup: envExists=%d dbExists=%d", envExists, dbExists);
        
        if (!envExists) {
            JNILOG_ERROR("nativeImportBackup: .env file not found in backup");
            return JNI_FALSE;
        }
        
        // For encrypted backups, we need a password - this should be passed separately
        // For now, just report what we found
        if (encrypted) {
            JNILOG_INFO("nativeImportBackup: Encrypted backup detected - password required");
            // The actual decryption would need password from Java side
            return JNI_FALSE; // Return false to indicate password needed
        }
        
        // Load .env file
        JNILOG_INFO("nativeImportBackup: Loading .env from backup");
        std::ifstream envFile(envPath);
        if (envFile) {
            std::string envContent((std::istreambuf_iterator<char>(envFile)), std::istreambuf_iterator<char>());
            JNILOG_INFO("nativeImportBackup: .env content length=%zu", envContent.length());
            
            // Parse and validate required fields
            bool hasApiId = envContent.find("API_ID") != std::string::npos;
            bool hasBotToken = envContent.find("BOT_TOKEN") != std::string::npos;
            bool hasChannelId = envContent.find("CHANNEL_ID") != std::string::npos;
            
            JNILOG_INFO("nativeImportBackup: hasApiId=%d hasBotToken=%d hasChannelId=%d", 
                       hasApiId, hasBotToken, hasChannelId);
            
            if (!hasBotToken || !hasChannelId) {
                JNILOG_ERROR("nativeImportBackup: Missing required fields (BOT_TOKEN or CHANNEL_ID)");
                return JNI_FALSE;
            }
            
            // Load into EnvManager
            EnvManager& envMgr = EnvManager::instance();
            // Parse line by line
            std::istringstream iss(envContent);
            std::string line;
            while (std::getline(iss, line)) {
                // Skip comments and empty lines
                if (line.empty() || line[0] == '#') continue;
                
                size_t eqPos = line.find('=');
                if (eqPos != std::string::npos) {
                    std::string key = line.substr(0, eqPos);
                    std::string value = line.substr(eqPos + 1);
                    // Remove quotes if present
                    if (value.size() >= 2 && value.front() == '\'' && value.back() == '\'') {
                        value = value.substr(1, value.size() - 2);
                    }
                    JNILOG_DEBUG("nativeImportBackup: Setting %s=%s...", key.c_str(), 
                                value.substr(0, std::min(value.size(), (size_t)10)).c_str());
                    envMgr.set(key, value);
                }
            }
            
            JNILOG_INFO("nativeImportBackup: Environment loaded successfully");
        }
        
        return JNI_TRUE;
        
    } catch (const std::exception& e) {
        JNILOG_ERROR("nativeImportBackup: Exception: %s", e.what());
        return JNI_FALSE;
    }
}

// New function to import encrypted backup with password
extern "C" JNIEXPORT jboolean JNICALL
Java_com_telegram_cloud_NativeLib_nativeImportEncryptedBackup(JNIEnv* env, jclass /*clazz*/, 
                                                               jstring jPath, jstring jPassword) {
    std::string archivePath = jstringToStd(env, jPath);
    std::string password = jstringToStd(env, jPassword);
    
    JNILOG_INFO("nativeImportEncryptedBackup: path=%s passwordLen=%zu", 
               archivePath.c_str(), password.length());
    
    try {
        fs::path backupDir(archivePath);
        
        if (!fs::is_directory(backupDir)) {
            JNILOG_ERROR("nativeImportEncryptedBackup: Not a directory");
            return JNI_FALSE;
        }
        
        fs::path envEncPath = backupDir / ".env.enc";
        fs::path dbEncPath = backupDir / "telegram_cloud.db.enc";
        
        JNILOG_INFO("nativeImportEncryptedBackup: envEncPath=%s exists=%d", 
                   envEncPath.string().c_str(), fs::exists(envEncPath));
        JNILOG_INFO("nativeImportEncryptedBackup: dbEncPath=%s exists=%d", 
                   dbEncPath.string().c_str(), fs::exists(dbEncPath));
        
        if (!fs::exists(envEncPath)) {
            JNILOG_ERROR("nativeImportEncryptedBackup: .env.enc not found");
            return JNI_FALSE;
        }
        
        // Decrypt .env file to temp location
        std::string tempEnvPath = archivePath + "/.env.decrypted";
        JNILOG_INFO("nativeImportEncryptedBackup: Decrypting .env to %s", tempEnvPath.c_str());
        
        bool decryptOk = BackupManager::decryptFile(envEncPath.string(), tempEnvPath, password);
        JNILOG_INFO("nativeImportEncryptedBackup: decryptFile result=%d", decryptOk);
        
        if (!decryptOk) {
            JNILOG_ERROR("nativeImportEncryptedBackup: Failed to decrypt .env - wrong password?");
            return JNI_FALSE;
        }
        
        // Read decrypted content
        std::ifstream envFile(tempEnvPath);
        if (!envFile) {
            JNILOG_ERROR("nativeImportEncryptedBackup: Cannot read decrypted .env");
            return JNI_FALSE;
        }
        
        std::string envContent((std::istreambuf_iterator<char>(envFile)), std::istreambuf_iterator<char>());
        envFile.close();
        
        // Remove temp file
        fs::remove(tempEnvPath);
        
        JNILOG_INFO("nativeImportEncryptedBackup: Decrypted .env content length=%zu", envContent.length());
        JNILOG_DEBUG("nativeImportEncryptedBackup: First 200 chars: %s", 
                    envContent.substr(0, std::min(envContent.size(), (size_t)200)).c_str());
        
        // Parse and load into EnvManager
        EnvManager& envMgr = EnvManager::instance();
        std::istringstream iss(envContent);
        std::string line;
        int lineCount = 0;
        
        while (std::getline(iss, line)) {
            lineCount++;
            // Skip comments and empty lines
            if (line.empty() || line[0] == '#') continue;
            
            size_t eqPos = line.find('=');
            if (eqPos != std::string::npos) {
                std::string key = line.substr(0, eqPos);
                std::string value = line.substr(eqPos + 1);
                // Remove quotes if present
                if (value.size() >= 2 && value.front() == '\'' && value.back() == '\'') {
                    value = value.substr(1, value.size() - 2);
                }
                JNILOG_INFO("nativeImportEncryptedBackup: Line %d: %s = [%zu chars]", 
                           lineCount, key.c_str(), value.length());
                envMgr.set(key, value);
            }
        }
        
        // Verify required fields
        std::string botToken = envMgr.get("BOT_TOKEN");
        std::string channelId = envMgr.get("CHANNEL_ID");
        
        JNILOG_INFO("nativeImportEncryptedBackup: BOT_TOKEN length=%zu", botToken.length());
        JNILOG_INFO("nativeImportEncryptedBackup: CHANNEL_ID length=%zu", channelId.length());
        
        if (botToken.empty() || channelId.empty()) {
            JNILOG_ERROR("nativeImportEncryptedBackup: Missing BOT_TOKEN or CHANNEL_ID after import");
            return JNI_FALSE;
        }
        
        JNILOG_INFO("nativeImportEncryptedBackup: SUCCESS - config imported");
        return JNI_TRUE;
        
    } catch (const std::exception& e) {
        JNILOG_ERROR("nativeImportEncryptedBackup: Exception: %s", e.what());
        return JNI_FALSE;
    }
}

static void notifyTransferProgress(int nativeId, float percent, const std::string& message) {
    if (!g_dispatcherClass || !g_progressMethod) return;
    JniEnvScope scope;
    JNIEnv* env = scope.get();
    if (!env) return;
    jstring jMessage = env->NewStringUTF(message.c_str());
    env->CallStaticVoidMethod(g_dispatcherClass, g_progressMethod, nativeId, percent, jMessage);
    env->DeleteLocalRef(jMessage);
}

static void notifyTransferCompleted(int nativeId, const std::string& message) {
    if (!g_dispatcherClass || !g_completedMethod) return;
    JniEnvScope scope;
    JNIEnv* env = scope.get();
    if (!env) return;
    jstring jMessage = env->NewStringUTF(message.c_str());
    env->CallStaticVoidMethod(g_dispatcherClass, g_completedMethod, nativeId, jMessage);
    env->DeleteLocalRef(jMessage);
}

static void notifyTransferFailed(int nativeId, const std::string& error) {
    if (!g_dispatcherClass || !g_failedMethod) return;
    JniEnvScope scope;
    JNIEnv* env = scope.get();
    if (!env) return;
    jstring jError = env->NewStringUTF(error.c_str());
    env->CallStaticVoidMethod(g_dispatcherClass, g_failedMethod, nativeId, jError);
    env->DeleteLocalRef(jError);
}

static std::string pickToken() {
    Config& cfg = Config::instance();
    auto tokens = cfg.allTokens();
    if (!tokens.empty()) {
        return tokens.front();
    }
    return "";
}

static void performTransferTask(int nativeId, TransferRequest request) {
    try {
        notifyTransferProgress(nativeId, 0.0f, "Iniciando transferencia");
        std::string token = pickToken();
        if (!g_handler) {
            g_handler = std::make_unique<TelegramHandler>();
        }

        auto tokens = extractTokens(request.payload);
        std::string chatIdOverride = extractStringField(request.payload, "chatId");
        if (chatIdOverride.empty()) {
            chatIdOverride = extractStringField(request.payload, "channelId");
        }

        switch (request.direction) {
            case TransferDirection::DOWNLOAD: {
                std::string fileId = extractStringField(request.payload, "fileId");
                std::string destPath = extractStringField(request.payload, "destPath");
                if (fileId.empty() || destPath.empty()) {
                    throw std::runtime_error("Missing download payload");
                }

                const std::string& tokenToUse = !tokens.empty() ? tokens.front() : token;
                if (tokenToUse.empty()) {
                    throw std::runtime_error("No bot token available for download");
                }

                notifyTransferProgress(nativeId, 0.2f, "Obteniendo archivo");
                bool downloaded = g_handler->downloadFile(fileId, destPath, tokenToUse);
                if (!downloaded) {
                    throw std::runtime_error("Direct download failed for " + fileId);
                }
                notifyTransferProgress(nativeId, 0.7f, "Descarga completada");
                break;
            }
            case TransferDirection::UPLOAD: {
                std::string sourcePath = extractStringField(request.payload, "sourcePath");
                std::string caption = extractStringField(request.payload, "caption");
                if (sourcePath.empty()) {
                    throw std::runtime_error("Missing upload source path");
                }

                const std::string& tokenToUse = !tokens.empty() ? tokens.front() : token;
                if (tokenToUse.empty()) {
                    throw std::runtime_error("No bot token available for upload");
                }

                notifyTransferProgress(nativeId, 0.3f, "Subiendo archivo");
                UploadResult result =
                    g_handler->uploadDocumentWithToken(sourcePath, tokenToUse, caption, chatIdOverride);

                if (!result.success) {
                    throw std::runtime_error("Upload failed: " + result.errorMessage);
                }
                notifyTransferProgress(nativeId, 0.8f, "Upload completado");
                break;
            }
            case TransferDirection::LINK_DOWNLOAD: {
                std::string fileId = extractStringField(request.payload, "fileId");
                std::string destPath = extractStringField(request.payload, "destPath");
                if (fileId.empty() || destPath.empty()) {
                    throw std::runtime_error("Missing link payload");
                }

                const std::string& linkToken = !tokens.empty() ? tokens.front() : token;
                if (linkToken.empty()) {
                    throw std::runtime_error("No bot token available for link download");
                }

                notifyTransferProgress(nativeId, 0.2f, "Obteniendo enlace");
                bool downloaded = g_handler->downloadFile(fileId, destPath, linkToken);
                if (!downloaded) {
                    throw std::runtime_error("Link download failed for " + fileId);
                }
                notifyTransferProgress(nativeId, 0.7f, "Enlace descargado");
                break;
            }
        }

        notifyTransferProgress(nativeId, 1.0f, "Completado");
        notifyTransferCompleted(nativeId, "Transferencia exitosa");
    } catch (const std::exception& ex) {
        notifyTransferFailed(nativeId, ex.what());
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_telegram_cloud_NativeLib_nativeStartDownload(JNIEnv* env, jclass /*clazz*/, jstring jUrl, jstring jDestPath) {
    std::string url = jstringToStd(env, jUrl);
    std::string dest = jstringToStd(env, jDestPath);
    __android_log_print(ANDROID_LOG_INFO, TAG, "nativeStartDownload url=%s dest=%s", url.c_str(), dest.c_str());
    if (!g_handler) g_handler = std::make_unique<TelegramHandler>();
    // The project has Database/Download management; here we call TelegramHandler::downloadFile which returns bool.
    bool ok = g_handler->downloadFile(url, dest);
    return ok ? 1 : -1;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_telegram_cloud_NativeLib_nativeStartTransfer(JNIEnv* env, jclass /*clazz*/, jstring jPayload) {
    ScopedUtfChars payloadChars(env, jPayload);
    std::string payload = payloadChars.get() ? payloadChars.get() : "";
    __android_log_print(ANDROID_LOG_INFO, TAG, "nativeStartTransfer payload=%s", payload.c_str());
    auto request = parseTransferRequest(payload);
    int nativeId = g_nextNativeId.fetch_add(1);
    {
        std::lock_guard<std::mutex> lock(g_workerMutex);
        std::thread worker([nativeId, request = std::move(request)]() mutable {
            performTransferTask(nativeId, request);
        });
        worker.detach();
    }
    return nativeId;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_telegram_cloud_NativeLib_nativeCancelTransfer(JNIEnv* env, jclass /*clazz*/, jint nativeId) {
    (void)env;
    __android_log_print(ANDROID_LOG_INFO, TAG, "nativeCancelTransfer id=%d", nativeId);
    // TODO: cancel running transfer referenced by nativeId
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_telegram_cloud_NativeLib_nativeStopDownload(JNIEnv* env, jclass /*clazz*/, jint downloadId) {
    (void)env;
    __android_log_print(ANDROID_LOG_INFO, TAG, "nativeStopDownload id=%d", downloadId);
    // TODO: integrate with download manager in the core
    return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_telegram_cloud_NativeLib_nativeStartUpload(JNIEnv* env, jclass /*clazz*/, jstring jFilePath, jstring jTarget) {
    std::string filePath = jstringToStd(env, jFilePath);
    std::string target = jstringToStd(env, jTarget);
    __android_log_print(ANDROID_LOG_INFO, TAG, "nativeStartUpload file=%s target=%s", filePath.c_str(), target.c_str());
    if (!g_handler) g_handler = std::make_unique<TelegramHandler>();
    UploadResult res = g_handler->uploadDocument(filePath, "");
    return res.success ? 1 : -1;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_telegram_cloud_NativeLib_nativeGetDownloadStatus(JNIEnv* env, jclass /*clazz*/, jint downloadId) {
    __android_log_print(ANDROID_LOG_INFO, TAG, "nativeGetDownloadStatus id=%d", downloadId);
    // TODO: query download manager for status and return JSON
    const char* stub = "{\"status\":\"unknown\",\"progress\":0}";
    return env->NewStringUTF(stub);
}
