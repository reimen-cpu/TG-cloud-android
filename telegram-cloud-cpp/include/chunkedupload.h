#ifndef CHUNKEDUPLOAD_H
#define CHUNKEDUPLOAD_H

#include <string>
#include <vector>
#include <atomic>
#include <chrono>
#include <functional>
#include <mutex>
#include <set>
#include <map>
#include "database.h"

namespace TelegramCloud {

class TelegramHandler;
class TelegramNotifier;

/**
 * @brief Gestor de subida de archivos con chunking paralelo
 * 
 * Divide archivos grandes en chunks de 4MB y los sube en paralelo
 * usando múltiples bots en round-robin.
 */
class ChunkedUpload {
public:
    ChunkedUpload(Database* database, TelegramHandler* telegramHandler, TelegramNotifier* notifier = nullptr);
    ~ChunkedUpload();
    
    // Iniciar subida
    std::string startUpload(const std::string& filePath);
    std::string resumeUpload(const std::string& uploadId, const std::string& filePath);
    
    // Controles de gestión
    bool pauseUpload(const std::string& uploadId);
    bool stopUpload(const std::string& uploadId);
    bool cancelUpload(const std::string& uploadId);
    
    // Estado
    std::string uploadId() const { return m_uploadId; }
    bool isActive() const { return m_isActive; }
    bool isPaused() const { return m_isPaused; }
    double progress() const;
    int64_t completedChunks() const { return m_completedChunks; }
    int64_t totalChunks() const { return m_totalChunks; }
    
    // Callback de progreso
    using ProgressCallback = std::function<void(int64_t completed, int64_t total, double percent)>;
    void setProgressCallback(ProgressCallback callback) { m_progressCallback = callback; }
    
    // Recuperar cargas incompletas
    std::vector<ChunkedFileInfo> getIncompleteUploads();
    
private:
    void uploadChunksParallel(const std::set<int64_t>& skipChunks = {});
    bool uploadSingleChunk(int64_t chunkIndex, const std::vector<char>& chunkData,
                          const std::string& chunkHash, const std::string& botToken);
    
    std::string calculateFileHash(const std::string& filePath);
    std::string calculateChunkHash(const std::vector<char>& data);
    std::string detectMimeType(const std::string& fileName);
    std::string generateUUID();
    void cleanup();
    
    // Validación y reanudación
    bool validateExistingChunks(const std::string& filePath, std::set<int64_t>& validChunks);
    bool loadUploadState(const std::string& uploadId);
    
    // Estado compartido entre instancias para control de pause/cancel
    static std::map<std::string, std::atomic<bool>> s_pausedUploads;
    static std::map<std::string, std::atomic<bool>> s_canceledUploads;
    static std::mutex s_controlMutex;
    
    Database* m_database;
    TelegramHandler* m_telegramHandler;
    TelegramNotifier* m_notifier;
    
    // Upload state
    std::string m_uploadId;
    std::string m_filePath;
    std::string m_fileName;
    std::string m_mimeType;
    int64_t m_fileSize;
    std::string m_fileHash;
    std::atomic<bool> m_isActive;
    std::atomic<bool> m_isCanceled;
    std::atomic<bool> m_isPaused;
    
    // Chunks
    int64_t m_totalChunks;
    std::atomic<int64_t> m_completedChunks;
    int64_t m_currentChunkIndex;
    
    // Sincronización
    std::mutex m_stateMutex;
    
    // Progress callback
    ProgressCallback m_progressCallback;
};

} // namespace TelegramCloud

#endif // CHUNKEDUPLOAD_H
