#ifndef CHUNKEDDOWNLOAD_H
#define CHUNKEDDOWNLOAD_H

#include <string>
#include <vector>
#include <atomic>
#include <functional>
#include <mutex>
#include <set>
#include <map>
#include "database.h"

namespace TelegramCloud {

class TelegramHandler;
class TelegramNotifier;

/**
 * @brief Gestor de descarga de archivos chunked con persistencia
 * 
 * Descarga archivos fragmentados en paralelo y permite reanudar
 * descargas interrumpidas.
 */
class ChunkedDownload {
public:
    ChunkedDownload(Database* database, TelegramHandler* telegramHandler, TelegramNotifier* notifier = nullptr);
    ~ChunkedDownload();
    
    // Iniciar descarga
    std::string startDownload(const std::string& fileId, const std::string& destPath);
    std::string resumeDownload(const std::string& downloadId, const std::string& destPath);
    
    // Controles de gestión
    bool pauseDownload(const std::string& downloadId);
    bool stopDownload(const std::string& downloadId);
    bool cancelDownload(const std::string& downloadId);
    
    // Estado
    std::string downloadId() const { return m_downloadId; }
    bool isActive() const { return m_isActive; }
    bool isPaused() const { return m_isPaused; }
    double progress() const;
    int64_t completedChunks() const { return m_completedChunks; }
    int64_t totalChunks() const { return m_totalChunks; }
    
    // Callback de progreso
    using ProgressCallback = std::function<void(int64_t completed, int64_t total, double percent)>;
    void setProgressCallback(ProgressCallback callback) { m_progressCallback = callback; }
    
    // Recuperar descargas incompletas
    std::vector<DownloadInfo> getIncompleteDownloads();
    
private:
    void downloadChunksParallel(const std::set<int64_t>& skipChunks = {});
    bool downloadSingleChunk(const ChunkInfo& chunk, const std::string& tempDir);
    bool reconstructFile(const std::string& tempDir, const std::string& destPath);
    
    std::string generateUUID();
    void cleanup();
    
    // Validación y reanudación
    bool validateExistingChunks(const std::string& tempDir, std::set<int64_t>& validChunks);
    bool loadDownloadState(const std::string& downloadId);
    
    // Estado compartido entre instancias para control de pause/cancel
    static std::map<std::string, std::atomic<bool>> s_pausedDownloads;
    static std::map<std::string, std::atomic<bool>> s_canceledDownloads;
    static std::mutex s_controlMutex;
    
    Database* m_database;
    TelegramHandler* m_telegramHandler;
    TelegramNotifier* m_notifier;
    
    // Download state
    std::string m_downloadId;
    std::string m_fileId;
    std::string m_fileName;
    std::string m_destPath;
    int64_t m_fileSize;
    std::atomic<bool> m_isActive;
    std::atomic<bool> m_isCanceled;
    std::atomic<bool> m_isPaused;
    
    // Chunks
    int64_t m_totalChunks;
    std::atomic<int64_t> m_completedChunks;
    std::vector<ChunkInfo> m_chunks;
    
    // Sincronización
    std::mutex m_stateMutex;
    
    // Progress callback
    ProgressCallback m_progressCallback;
};

} // namespace TelegramCloud

#endif // CHUNKEDDOWNLOAD_H



