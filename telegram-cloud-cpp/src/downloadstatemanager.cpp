#include "downloadstatemanager.h"
#include "logger.h"
#include <filesystem>
#include <fstream>
#include <future>
#include <thread>
#include <chrono>
#include <openssl/evp.h>
#include <openssl/aes.h>

namespace TelegramCloud {

DownloadStateManager::DownloadStateManager(Database* database, TelegramHandler* telegramHandler)
    : m_database(database)
    , m_telegramHandler(telegramHandler)
    , m_pauseRequested(false) {
}

DownloadStateManager::~DownloadStateManager() {
}

std::string DownloadStateManager::generateDownloadId() {
    // Generar ID único basado en timestamp + aleatorio
    auto now = std::chrono::system_clock::now();
    auto timestamp = std::chrono::duration_cast<std::chrono::milliseconds>(now.time_since_epoch()).count();
    
    std::stringstream ss;
    ss << "dl_" << timestamp << "_" << (rand() % 10000);
    return ss.str();
}

std::string DownloadStateManager::startDownload(
    const std::string& fileId,
    const std::string& saveDirectory,
    const std::string& decryptPassword,
    DownloadProgressCallback progressCallback
) {
    if (!m_database || fileId.empty()) {
        LOG_ERROR("Cannot start download: invalid parameters");
        return "";
    }
    
    LOG_INFO("Starting download with state persistence: " + fileId);
    
    // Obtener info del archivo
    FileInfo fileInfo = m_database->getFileInfo(fileId);
    if (fileInfo.fileId.empty()) {
        LOG_ERROR("File not found in database: " + fileId);
        return "";
    }
    
    // Generar ID de descarga
    std::string downloadId = generateDownloadId();
    
    // Crear estado inicial
    DownloadState state;
    state.downloadId = downloadId;
    state.fileId = fileId;
    state.fileName = fileInfo.fileName;
    state.fileType = fileInfo.category;
    state.saveDirectory = saveDirectory;
    state.completedChunks = 0;
    state.totalChunks = 0;
    state.progressPercent = 0.0;
    state.status = "active";
    state.isEncrypted = fileInfo.isEncrypted;
    
    if (fileInfo.category == "chunked") {
        auto chunks = m_database->getFileChunks(fileId);
        state.totalChunks = chunks.size();
    } else {
        state.totalChunks = 1;
    }
    
    // Guardar estado inicial
    if (!saveDownloadState(state)) {
        LOG_ERROR("Failed to save download state");
        return "";
    }
    
    // Iniciar descarga
    m_currentDownloadId = downloadId;
    m_pauseRequested = false;
    
    if (fileInfo.category == "chunked") {
        bool success = downloadChunkedFileWithPause(
            downloadId, fileId, saveDirectory, decryptPassword, 0, progressCallback
        );
        
        if (success) {
            // Eliminar estado al completar
            deleteDownloadState(downloadId);
        }
    } else {
        // Descarga directa (sin chunks)
        // TODO: Implementar descarga directa con persistencia si es necesario
        LOG_WARNING("Direct download persistence not yet fully implemented");
    }
    
    m_currentDownloadId = "";
    return downloadId;
}

bool DownloadStateManager::downloadChunkedFileWithPause(
    const std::string& downloadId,
    const std::string& fileId,
    const std::string& saveDirectory,
    const std::string& decryptPassword,
    int64_t startChunk,
    DownloadProgressCallback progressCallback
) {
    // Obtener chunks
    auto chunks = m_database->getFileChunks(fileId);
    if (chunks.empty()) {
        LOG_ERROR("No chunks found for file: " + fileId);
        return false;
    }
    
    FileInfo fileInfo = m_database->getFileInfo(fileId);
    std::string destPath = saveDirectory + "/" + fileInfo.fileName;
    
    // Crear directorio temporal para chunks
    std::string tempDir = "temp_dl_" + downloadId;
    std::filesystem::create_directories(tempDir);
    
    // Descargar chunks con soporte de pausa
    std::atomic<int64_t> downloadedChunks(startChunk);
    int64_t totalChunks = chunks.size();
    
    if (progressCallback) {
        progressCallback(startChunk, totalChunks, (double)startChunk / totalChunks * 100.0, "Downloading chunks");
    }
    
    const int MAX_CONCURRENT = 5;
    
    for (size_t i = startChunk; i < chunks.size(); i += MAX_CONCURRENT) {
        // Verificar si se solicitó pausa
        if (m_pauseRequested) {
            LOG_INFO("Download paused by user at chunk " + std::to_string(i));
            updateDownloadProgress(downloadId, i, (double)i / totalChunks * 100.0);
            return false;
        }
        
        std::vector<std::future<bool>> futures;
        size_t batchEnd = std::min(i + MAX_CONCURRENT, chunks.size());
        
        for (size_t j = i; j < batchEnd; j++) {
            const ChunkInfo& chunk = chunks[j];
            
            // Verificar si chunk ya existe
            std::string chunkPath = tempDir + "/chunk_" + std::to_string(chunk.chunkNumber) + ".tmp";
            if (std::filesystem::exists(chunkPath)) {
                downloadedChunks++;
                continue;
            }
            
            auto future = std::async(std::launch::async, [this, chunk, tempDir, &downloadedChunks, totalChunks, downloadId, progressCallback]() {
                std::string chunkPath = tempDir + "/chunk_" + std::to_string(chunk.chunkNumber) + ".tmp";
                
                bool success = false;
                for (int retry = 0; retry < 3 && !success; retry++) {
                    if (retry > 0) {
                        std::this_thread::sleep_for(std::chrono::seconds(1));
                    }
                    success = m_telegramHandler->downloadFile(chunk.telegramFileId, chunkPath);
                }
                
                if (success) {
                    int64_t completed = ++downloadedChunks;
                    double percent = (double)completed / totalChunks * 100.0;
                    
                    // Actualizar estado en BD cada 10 chunks
                    if (completed % 10 == 0) {
                        updateDownloadProgress(downloadId, completed, percent);
                    }
                    
                    if (progressCallback) {
                        progressCallback(completed, totalChunks, percent, "Downloading chunks");
                    }
                }
                
                return success;
            });
            
            futures.push_back(std::move(future));
        }
        
        // Esperar lote
        for (auto& f : futures) {
            if (!f.get()) {
                LOG_ERROR("Failed to download chunk");
                return false;
            }
        }
    }
    
    // Reconstruir archivo
    if (progressCallback) {
        progressCallback(0, totalChunks, 0.0, "Reconstructing file");
    }
    
    std::ofstream finalFile(destPath, std::ios::binary);
    if (!finalFile.is_open()) {
        LOG_ERROR("Failed to create output file");
        std::filesystem::remove_all(tempDir);
        return false;
    }
    
    int reconstructed = 0;
    for (const ChunkInfo& chunk : chunks) {
        std::string chunkPath = tempDir + "/chunk_" + std::to_string(chunk.chunkNumber) + ".tmp";
        
        std::ifstream chunkFile(chunkPath, std::ios::binary);
        if (chunkFile.is_open()) {
            finalFile << chunkFile.rdbuf();
            chunkFile.close();
            
            reconstructed++;
            if (progressCallback) {
                double percent = (double)reconstructed / totalChunks * 100.0;
                progressCallback(reconstructed, totalChunks, percent, "Reconstructing file");
            }
        }
    }
    
    finalFile.close();
    std::filesystem::remove_all(tempDir);
    
    // Desencriptar si es necesario
    if (fileInfo.isEncrypted && !decryptPassword.empty()) {
        // TODO: Implementar desencriptación
        LOG_WARNING("File decryption not yet implemented in DownloadStateManager");
    }
    
    LOG_INFO("Download completed successfully: " + fileInfo.fileName);
    return true;
}

bool DownloadStateManager::pauseDownload(const std::string& downloadId) {
    if (downloadId != m_currentDownloadId) {
        LOG_WARNING("Cannot pause: download ID mismatch");
        return false;
    }
    
    LOG_INFO("Requesting pause for download: " + downloadId);
    m_pauseRequested = true;
    
    // Actualizar estado en BD
    // TODO: Implementar actualización de estado a "paused" en BD
    
    return true;
}

bool DownloadStateManager::resumeDownload(
    const std::string& downloadId,
    const std::string& decryptPassword,
    DownloadProgressCallback progressCallback
) {
    LOG_INFO("Resuming download: " + downloadId);
    
    // Cargar estado
    DownloadState state = loadDownloadState(downloadId);
    if (state.downloadId.empty()) {
        LOG_ERROR("Download state not found: " + downloadId);
        return false;
    }
    
    // Reanudar desde último chunk
    m_currentDownloadId = downloadId;
    m_pauseRequested = false;
    
    bool success = downloadChunkedFileWithPause(
        downloadId,
        state.fileId,
        state.saveDirectory,
        decryptPassword,
        state.completedChunks,
        progressCallback
    );
    
    if (success) {
        deleteDownloadState(downloadId);
    }
    
    m_currentDownloadId = "";
    return success;
}

bool DownloadStateManager::cancelDownload(const std::string& downloadId) {
    LOG_INFO("Cancelling download: " + downloadId);
    
    // Eliminar archivos temporales
    std::string tempDir = "temp_dl_" + downloadId;
    try {
        if (std::filesystem::exists(tempDir)) {
            std::filesystem::remove_all(tempDir);
        }
    } catch (...) {}
    
    // Eliminar estado
    return deleteDownloadState(downloadId);
}

std::vector<DownloadState> DownloadStateManager::getIncompleteDownloads() {
    std::vector<DownloadState> downloads;
    
    if (!m_database) return downloads;
    
    const char* sql = R"(
        SELECT download_id, file_id, file_name, file_type, save_directory, completed_chunks,
               total_chunks, progress_percent, status, is_encrypted
        FROM download_states
        WHERE status IN ('active', 'paused')
        ORDER BY last_update_time DESC
    )";
    
    sqlite3* db = m_database->getDB();
    sqlite3_stmt* stmt;
    
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) != SQLITE_OK) {
        LOG_ERROR("Failed to prepare incomplete downloads query");
        return downloads;
    }
    
    while (sqlite3_step(stmt) == SQLITE_ROW) {
        DownloadState state;
        state.downloadId = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 0));
        state.fileId = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 1));
        state.fileName = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 2));
        state.fileType = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 3));
        state.saveDirectory = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 4));
        state.completedChunks = sqlite3_column_int64(stmt, 5);
        state.totalChunks = sqlite3_column_int64(stmt, 6);
        state.progressPercent = sqlite3_column_double(stmt, 7);
        state.status = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 8));
        state.isEncrypted = sqlite3_column_int(stmt, 9) == 1;
        
        downloads.push_back(state);
    }
    
    sqlite3_finalize(stmt);
    
    LOG_INFO("Found " + std::to_string(downloads.size()) + " incomplete downloads");
    return downloads;
}

bool DownloadStateManager::hasIncompleteDownloads() {
    return !getIncompleteDownloads().empty();
}

DownloadState DownloadStateManager::getDownloadInfo(const std::string& downloadId) {
    return loadDownloadState(downloadId);
}

bool DownloadStateManager::saveDownloadState(const DownloadState& state) {
    if (!m_database) {
        LOG_ERROR("Database not initialized");
        return false;
    }
    
    LOG_INFO("Saving download state: " + state.downloadId + " (progress: " + std::to_string(state.progressPercent) + "%)");
    
    const char* sql = R"(
        INSERT OR REPLACE INTO download_states 
        (download_id, file_id, file_name, file_type, save_directory, completed_chunks, 
         total_chunks, progress_percent, status, is_encrypted, last_update_time)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, datetime('now'))
    )";
    
    sqlite3* db = m_database->getDB();
    sqlite3_stmt* stmt;
    
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) != SQLITE_OK) {
        LOG_ERROR("Failed to prepare download state save");
        return false;
    }
    
    sqlite3_bind_text(stmt, 1, state.downloadId.c_str(), -1, SQLITE_TRANSIENT);
    sqlite3_bind_text(stmt, 2, state.fileId.c_str(), -1, SQLITE_TRANSIENT);
    sqlite3_bind_text(stmt, 3, state.fileName.c_str(), -1, SQLITE_TRANSIENT);
    sqlite3_bind_text(stmt, 4, state.fileType.c_str(), -1, SQLITE_TRANSIENT);
    sqlite3_bind_text(stmt, 5, state.saveDirectory.c_str(), -1, SQLITE_TRANSIENT);
    sqlite3_bind_int64(stmt, 6, state.completedChunks);
    sqlite3_bind_int64(stmt, 7, state.totalChunks);
    sqlite3_bind_double(stmt, 8, state.progressPercent);
    sqlite3_bind_text(stmt, 9, state.status.c_str(), -1, SQLITE_TRANSIENT);
    sqlite3_bind_int(stmt, 10, state.isEncrypted ? 1 : 0);
    
    bool success = (sqlite3_step(stmt) == SQLITE_DONE);
    sqlite3_finalize(stmt);
    
    return success;
}

bool DownloadStateManager::updateDownloadProgress(const std::string& downloadId, int64_t completedChunks, double percent) {
    if (!m_database) return false;
    
    LOG_DEBUG("Update download progress: " + downloadId + " - " + std::to_string(completedChunks) + " chunks (" + std::to_string(percent) + "%)");
    
    const char* sql = R"(
        UPDATE download_states 
        SET completed_chunks = ?, progress_percent = ?, last_update_time = datetime('now')
        WHERE download_id = ?
    )";
    
    sqlite3* db = m_database->getDB();
    sqlite3_stmt* stmt;
    
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) != SQLITE_OK) {
        return false;
    }
    
    sqlite3_bind_int64(stmt, 1, completedChunks);
    sqlite3_bind_double(stmt, 2, percent);
    sqlite3_bind_text(stmt, 3, downloadId.c_str(), -1, SQLITE_TRANSIENT);
    
    bool success = (sqlite3_step(stmt) == SQLITE_DONE);
    sqlite3_finalize(stmt);
    
    return success;
}

DownloadState DownloadStateManager::loadDownloadState(const std::string& downloadId) {
    DownloadState state;
    
    if (!m_database) return state;
    
    const char* sql = R"(
        SELECT download_id, file_id, file_name, file_type, save_directory, completed_chunks,
               total_chunks, progress_percent, status, is_encrypted
        FROM download_states
        WHERE download_id = ?
    )";
    
    sqlite3* db = m_database->getDB();
    sqlite3_stmt* stmt;
    
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) != SQLITE_OK) {
        LOG_ERROR("Failed to prepare download state load");
        return state;
    }
    
    sqlite3_bind_text(stmt, 1, downloadId.c_str(), -1, SQLITE_TRANSIENT);
    
    if (sqlite3_step(stmt) == SQLITE_ROW) {
        state.downloadId = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 0));
        state.fileId = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 1));
        state.fileName = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 2));
        state.fileType = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 3));
        state.saveDirectory = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 4));
        state.completedChunks = sqlite3_column_int64(stmt, 5);
        state.totalChunks = sqlite3_column_int64(stmt, 6);
        state.progressPercent = sqlite3_column_double(stmt, 7);
        state.status = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 8));
        state.isEncrypted = sqlite3_column_int(stmt, 9) == 1;
    }
    
    sqlite3_finalize(stmt);
    return state;
}

bool DownloadStateManager::deleteDownloadState(const std::string& downloadId) {
    if (!m_database) return false;
    
    LOG_INFO("Deleting download state: " + downloadId);
    
    const char* sql = "DELETE FROM download_states WHERE download_id = ?";
    
    sqlite3* db = m_database->getDB();
    sqlite3_stmt* stmt;
    
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) != SQLITE_OK) {
        return false;
    }
    
    sqlite3_bind_text(stmt, 1, downloadId.c_str(), -1, SQLITE_TRANSIENT);
    
    bool success = (sqlite3_step(stmt) == SQLITE_DONE);
    sqlite3_finalize(stmt);
    
    // Limpiar archivos temporales
    std::string tempDir = "temp_dl_" + downloadId;
    try {
        if (std::filesystem::exists(tempDir)) {
            std::filesystem::remove_all(tempDir);
        }
    } catch (...) {}
    
    return success;
}

} // namespace TelegramCloud

