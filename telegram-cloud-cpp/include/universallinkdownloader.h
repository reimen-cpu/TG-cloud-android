#ifndef UNIVERSALLINKDOWNLOADER_H
#define UNIVERSALLINKDOWNLOADER_H

#include <string>
#include <vector>
#include <functional>
#include "database.h"
#include "telegramhandler.h"

namespace TelegramCloud {

class TelegramNotifier;

// Callback para progreso de descarga desde link universal
using UniversalLinkProgressCallback = std::function<void(int current, int total, const std::string& fileName, double percent)>;

/**
 * @brief Descargador de archivos desde .link universales
 * 
 * Procesa archivos .link y descarga los archivos usando la lógica
 * existente de descarga, sin depender de la base de datos local.
 */
class UniversalLinkDownloader {
public:
    UniversalLinkDownloader(TelegramHandler* telegramHandler, Database* database = nullptr, TelegramNotifier* notifier = nullptr);
    
    /**
     * @brief Inicia descarga desde un archivo .link
     * @param linkFilePath Ruta al archivo .link
     * @param password Contraseña para desencriptar el link
     * @param destinationDir Directorio donde guardar los archivos
     * @param filePassword Contraseña del archivo (si está encriptado)
     * @param progressCallback Callback para reportar progreso
     * @return true si la descarga fue exitosa
     */
    bool downloadFromLinkFile(
        const std::string& linkFilePath,
        const std::string& password,
        const std::string& destinationDir,
        const std::string& filePassword = "",
        UniversalLinkProgressCallback progressCallback = nullptr
    );
    
    /**
     * @brief Obtiene información del archivo .link sin descargarlo
     * @param linkFilePath Ruta al archivo .link
     * @param password Contraseña para desencriptar el link
     * @return Vector con información de los archivos contenidos
     */
    std::vector<FileInfo> getLinkFileInfo(
        const std::string& linkFilePath,
        const std::string& password
    );
    
private:
    TelegramHandler* m_telegramHandler;
    Database* m_database;
    TelegramNotifier* m_notifier;
    
    /**
     * @brief Lee y desencripta un archivo .link
     */
    std::string readAndDecryptLinkFile(
        const std::string& linkFilePath,
        const std::string& password
    );
    
    /**
     * @brief Parsea los datos JSON del link
     */
    bool parseLinkData(
        const std::string& jsonData,
        std::vector<FileInfo>& filesInfo,
        std::vector<std::vector<ChunkInfo>>& filesChunks
    );
    
    /**
     * @brief Descarga un archivo individual desde los datos del link
     */
    bool downloadSingleFile(
        const FileInfo& fileInfo,
        const std::vector<ChunkInfo>& chunks,
        const std::string& destinationDir,
        const std::string& filePassword,
        UniversalLinkProgressCallback progressCallback,
        int currentIndex,
        int totalFiles
    );
    
    /**
     * @brief Descarga archivo chunked usando los datos del link
     */
    bool downloadChunkedFromLink(
        const FileInfo& fileInfo,
        const std::vector<ChunkInfo>& chunks,
        const std::string& destPath,
        const std::string& filePassword
    );
    
    /**
     * @brief Descarga archivo chunked con callback de progreso
     */
    bool downloadChunkedFromLinkWithProgress(
        const FileInfo& fileInfo,
        const std::vector<ChunkInfo>& chunks,
        const std::string& destPath,
        const std::string& filePassword,
        std::function<void(int64_t completed, int64_t total)> progressCallback
    );
    
    /**
     * @brief Descarga archivo directo usando los datos del link
     */
    bool downloadDirectFromLink(
        const FileInfo& fileInfo,
        const std::string& destPath,
        const std::string& filePassword
    );
    
    /**
     * @brief Desencripta un archivo descargado
     */
    bool decryptFile(
        const std::string& inputPath,
        const std::string& outputPath,
        const std::string& password
    );
    
    /**
     * @brief Desencripta datos con AES-256
     */
    std::string decryptData(const std::string& encrypted, const std::string& password);
    
    /**
     * @brief Deriva clave de encriptación desde contraseña
     */
    std::string deriveKey(const std::string& password, const std::string& salt);
    
    /**
     * @brief Genera UUID para download ID
     */
    std::string generateDownloadId();
};

} // namespace TelegramCloud

#endif // UNIVERSALLINKDOWNLOADER_H

