#pragma once

#include "tempdownloaddb.h"
#include "database.h"
#include "telegramhandler.h"
#include <memory>
#include <string>
#include <vector>
#include <functional>
#include <atomic>

namespace TelegramCloud {

// Callback para progreso de descarga
using LinkDownloadProgressCallback = std::function<void(int64_t completed, int64_t total, double percent, const std::string& phase)>;

class LinkDownloadManager {
public:
    LinkDownloadManager(TelegramHandler* telegramHandler);
    ~LinkDownloadManager();
    
    // Inicializar gestor
    bool initialize();
    
    // Verificar descargas incompletas al inicio
    std::vector<LinkDownloadInfo> checkIncompleteDownloads();
    
    // Iniciar nueva descarga desde link
    std::string startDownloadFromLink(
        const std::string& shareData,          // JSON del link (ya desencriptado y descomprimido)
        const std::string& saveDirectory,
        const std::string& filePassword,       // Contraseña del archivo (si está encriptado)
        LinkDownloadProgressCallback progressCallback
    );
    
    // Reanudar descarga existente
    bool resumeDownload(
        const std::string& downloadId,
        const std::string& filePassword,       // Contraseña del archivo (si está encriptado)
        LinkDownloadProgressCallback progressCallback
    );
    
    // Control de descarga
    bool pauseDownload(const std::string& downloadId);
    bool cancelDownload(const std::string& downloadId);
    
    // Obtener información de descarga
    LinkDownloadInfo getDownloadInfo(const std::string& downloadId);
    
    // Limpiar descargas completadas
    void cleanup();
    
private:
    TelegramHandler* m_telegramHandler;
    std::unique_ptr<TempDownloadDB> m_tempDB;
    
    // Métodos internos de descarga
    bool downloadChunkedFile(
        const std::string& downloadId,
        const LinkDownloadInfo& info,
        const std::string& filePassword,
        LinkDownloadProgressCallback progressCallback
    );
    
    bool downloadDirectFile(
        const std::string& downloadId,
        const LinkDownloadInfo& info,
        const std::string& filePassword,
        LinkDownloadProgressCallback progressCallback
    );
    
    // Generar ID único para descarga
    std::string generateDownloadId();
    
    // Parsear datos del link
    bool parseLinkData(
        const std::string& shareData,
        std::string& fileId,
        std::string& fileName,
        std::string& fileType,
        int64_t& fileSize,
        bool& isEncrypted,
        std::vector<ChunkInfo>& chunks,
        std::string& telegramFileId
    );
    
    // Desencriptar archivo si es necesario
    bool decryptFile(const std::string& inputPath, const std::string& outputPath, const std::string& password);
};

} // namespace TelegramCloud










