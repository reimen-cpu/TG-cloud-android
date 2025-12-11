#include "uploadstatemanager.h"
#include "logger.h"
#include <chrono>
#include <iomanip>
#include <sstream>

namespace TelegramCloud {

UploadStateManager::UploadStateManager(Database* database, TelegramHandler* telegramHandler)
    : m_database(database)
    , m_telegramHandler(telegramHandler) {
}

UploadStateManager::~UploadStateManager() {
}

std::vector<ChunkedFileInfo> UploadStateManager::getIncompleteUploads() {
    if (!m_database) {
        LOG_ERROR("Database not initialized");
        return {};
    }
    
    LOG_INFO("Retrieving incomplete uploads from database");
    return m_database->getIncompleteUploads();
}

bool UploadStateManager::hasIncompleteUploads() {
    auto incompleteUploads = getIncompleteUploads();
    return !incompleteUploads.empty();
}

bool UploadStateManager::pauseCurrentUpload(const std::string& uploadId) {
    if (uploadId.empty()) {
        LOG_WARNING("Cannot pause upload: empty upload ID");
        return false;
    }
    
    LOG_INFO("Pausing upload: " + uploadId);
    
    ChunkedUpload upload(m_database, m_telegramHandler);
    bool success = upload.pauseUpload(uploadId);
    
    if (success) {
        LOG_INFO("Upload paused successfully: " + uploadId);
    } else {
        LOG_ERROR("Failed to pause upload: " + uploadId);
    }
    
    return success;
}

bool UploadStateManager::stopCurrentUpload(const std::string& uploadId) {
    if (uploadId.empty()) {
        LOG_WARNING("Cannot stop upload: empty upload ID");
        return false;
    }
    
    LOG_INFO("Stopping upload: " + uploadId);
    
    ChunkedUpload upload(m_database, m_telegramHandler);
    bool success = upload.stopUpload(uploadId);
    
    if (success) {
        LOG_INFO("Upload stopped successfully (progress saved): " + uploadId);
    } else {
        LOG_ERROR("Failed to stop upload: " + uploadId);
    }
    
    return success;
}

bool UploadStateManager::cancelUpload(const std::string& uploadId) {
    if (uploadId.empty()) {
        LOG_WARNING("Cannot cancel upload: empty upload ID");
        return false;
    }
    
    LOG_INFO("Cancelling upload (will delete all progress): " + uploadId);
    
    ChunkedUpload upload(m_database, m_telegramHandler);
    bool success = upload.cancelUpload(uploadId);
    
    if (success) {
        LOG_INFO("Upload cancelled successfully: " + uploadId);
    } else {
        LOG_ERROR("Failed to cancel upload: " + uploadId);
    }
    
    return success;
}

std::unique_ptr<ChunkedUpload> UploadStateManager::createChunkedUpload(UploadProgressCallback progressCallback) {
    auto upload = std::make_unique<ChunkedUpload>(m_database, m_telegramHandler);
    
    if (progressCallback) {
        upload->setProgressCallback(progressCallback);
    }
    
    return upload;
}

std::string UploadStateManager::resumeUpload(
    const std::string& fileId,
    const std::string& filePath,
    UploadProgressCallback progressCallback
) {
    if (fileId.empty()) {
        LOG_ERROR("Cannot resume upload: empty file ID");
        return "";
    }
    
    if (filePath.empty()) {
        LOG_ERROR("Cannot resume upload: empty file path");
        return "";
    }
    
    LOG_INFO("Resuming upload: " + fileId + " from file: " + filePath);
    
    auto upload = createChunkedUpload(progressCallback);
    std::string result = upload->resumeUpload(fileId, filePath);
    
    if (!result.empty()) {
        LOG_INFO("Upload resumed successfully: " + fileId);
    } else {
        LOG_ERROR("Failed to resume upload: " + fileId);
    }
    
    return result;
}

std::string UploadStateManager::startChunkedUpload(
    const std::string& filePath,
    UploadProgressCallback progressCallback
) {
    if (filePath.empty()) {
        LOG_ERROR("Cannot start upload: empty file path");
        return "";
    }
    
    LOG_INFO("Starting new chunked upload: " + filePath);
    
    auto upload = createChunkedUpload(progressCallback);
    std::string uploadId = upload->startUpload(filePath);
    
    if (!uploadId.empty()) {
        LOG_INFO("Chunked upload started successfully: " + uploadId);
    } else {
        LOG_ERROR("Failed to start chunked upload: " + filePath);
    }
    
    return uploadId;
}

ChunkedFileInfo UploadStateManager::getUploadInfo(const std::string& fileId) {
    ChunkedFileInfo info;
    
    if (!m_database || fileId.empty()) {
        return info;
    }
    
    // Obtener información de la carga desde la BD
    auto incompleteUploads = getIncompleteUploads();
    
    for (const auto& upload : incompleteUploads) {
        if (upload.fileId == fileId) {
            return upload;
        }
    }
    
    LOG_WARNING("Upload info not found for file ID: " + fileId);
    return info;
}

void UploadStateManager::cleanupOldFailedUploads() {
    if (!m_database) {
        LOG_ERROR("Database not initialized - cannot cleanup old uploads");
        return;
    }
    
    LOG_INFO("Cleaning up old failed uploads (older than 7 days)");
    
    auto incompleteUploads = getIncompleteUploads();
    int cleanedCount = 0;
    
    // Obtener timestamp actual
    auto now = std::chrono::system_clock::now();
    auto nowTime = std::chrono::system_clock::to_time_t(now);
    
    for (const auto& upload : incompleteUploads) {
        // Solo limpiar cargas con estado "failed" o "paused" antiguas
        // TODO: Implementar limpieza basada en fecha una vez que ChunkedFileInfo tenga campo lastUpdateTime
        if (upload.status == "failed") {
            LOG_INFO("Found old failed upload: " + upload.fileId);
            // Por ahora, no eliminamos automáticamente - dejamos que el usuario decida
        }
    }
    
    if (cleanedCount > 0) {
        LOG_INFO("Cleaned up " + std::to_string(cleanedCount) + " old failed uploads");
    } else {
        LOG_INFO("No old failed uploads to clean up");
    }
}

} // namespace TelegramCloud

