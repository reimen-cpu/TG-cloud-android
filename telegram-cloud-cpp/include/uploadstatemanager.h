#pragma once

#include "database.h"
#include "chunkedupload.h"
#include <memory>
#include <string>
#include <vector>
#include <functional>

namespace TelegramCloud {

// Callback para progreso de carga
using UploadProgressCallback = std::function<void(int64_t completed, int64_t total, double percent)>;

// Gestor de estado y reanudación de cargas
class UploadStateManager {
public:
    UploadStateManager(Database* database, TelegramHandler* telegramHandler);
    ~UploadStateManager();
    
    // Verificar cargas incompletas
    std::vector<ChunkedFileInfo> getIncompleteUploads();
    bool hasIncompleteUploads();
    
    // Control de carga activa
    bool pauseCurrentUpload(const std::string& uploadId);
    bool stopCurrentUpload(const std::string& uploadId);
    bool cancelUpload(const std::string& uploadId);
    
    // Reanudar carga
    std::string resumeUpload(
        const std::string& fileId,
        const std::string& filePath,
        UploadProgressCallback progressCallback
    );
    
    // Iniciar nueva carga chunked con persistencia
    std::string startChunkedUpload(
        const std::string& filePath,
        UploadProgressCallback progressCallback
    );
    
    // Información de carga
    ChunkedFileInfo getUploadInfo(const std::string& fileId);
    
    // Limpiar cargas fallidas antiguas (más de 7 días)
    void cleanupOldFailedUploads();
    
private:
    Database* m_database;
    TelegramHandler* m_telegramHandler;
    
    // Helper para crear ChunkedUpload con callbacks
    std::unique_ptr<ChunkedUpload> createChunkedUpload(UploadProgressCallback progressCallback);
};

} // namespace TelegramCloud










