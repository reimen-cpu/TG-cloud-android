#pragma once

#include "database.h"
#include "telegramhandler.h"
#include <memory>
#include <string>
#include <vector>
#include <functional>
#include <atomic>

namespace TelegramCloud {

// Estructura para descarga pausada/interrumpida (descargas normales, no por link)
struct DownloadState {
    std::string downloadId;           // ID único
    std::string fileId;               // ID del archivo en BD
    std::string fileName;             // Nombre del archivo
    std::string fileType;             // "chunked" o "direct"
    std::string saveDirectory;        // Directorio destino
    int64_t completedChunks;          // Chunks descargados
    int64_t totalChunks;              // Total chunks
    double progressPercent;           // Porcentaje
    std::string status;               // "active", "paused", "failed"
    bool isEncrypted;                 // Si está encriptado
};

// Callback para progreso de descarga
using DownloadProgressCallback = std::function<void(int64_t completed, int64_t total, double percent, const std::string& phase)>;

// Gestor de estado para descargas normales (no por link)
class DownloadStateManager {
public:
    DownloadStateManager(Database* database, TelegramHandler* telegramHandler);
    ~DownloadStateManager();
    
    // Iniciar descarga con persistencia
    std::string startDownload(
        const std::string& fileId,
        const std::string& saveDirectory,
        const std::string& decryptPassword,
        DownloadProgressCallback progressCallback
    );
    
    // Pausar descarga activa
    bool pauseDownload(const std::string& downloadId);
    
    // Reanudar descarga
    bool resumeDownload(
        const std::string& downloadId,
        const std::string& decryptPassword,
        DownloadProgressCallback progressCallback
    );
    
    // Cancelar descarga
    bool cancelDownload(const std::string& downloadId);
    
    // Obtener descargas incompletas
    std::vector<DownloadState> getIncompleteDownloads();
    bool hasIncompleteDownloads();
    
    // Información de descarga
    DownloadState getDownloadInfo(const std::string& downloadId);
    
private:
    Database* m_database;
    TelegramHandler* m_telegramHandler;
    
    std::atomic<bool> m_pauseRequested;
    std::string m_currentDownloadId;
    
    // Helper para descargar chunks con soporte de pausa
    bool downloadChunkedFileWithPause(
        const std::string& downloadId,
        const std::string& fileId,
        const std::string& saveDirectory,
        const std::string& decryptPassword,
        int64_t startChunk,
        DownloadProgressCallback progressCallback
    );
    
    // Helper para guardar estado
    bool saveDownloadState(const DownloadState& state);
    bool updateDownloadProgress(const std::string& downloadId, int64_t completedChunks, double percent);
    DownloadState loadDownloadState(const std::string& downloadId);
    bool deleteDownloadState(const std::string& downloadId);
    
    std::string generateDownloadId();
};

} // namespace TelegramCloud










