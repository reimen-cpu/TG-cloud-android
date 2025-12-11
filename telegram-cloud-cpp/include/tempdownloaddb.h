#pragma once

#include <string>
#include <vector>
#include <memory>

struct sqlite3;

namespace TelegramCloud {

// Estructura para almacenar información de descarga por link
struct LinkDownloadInfo {
    std::string downloadId;           // ID único de la descarga
    std::string fileId;               // ID del archivo
    std::string fileName;             // Nombre del archivo
    std::string fileType;             // "chunked" o "direct"
    int64_t fileSize;                 // Tamaño total
    bool isEncrypted;                 // Si está encriptado
    std::string saveDirectory;        // Directorio de destino
    std::string status;               // "active", "paused", "failed"
    int64_t completedChunks;          // Chunks completados
    int64_t totalChunks;              // Total de chunks
    double progressPercent;           // Porcentaje completado
    std::string shareData;            // Datos del link original (JSON comprimido)
    std::string startTime;            // Timestamp de inicio
    std::string lastUpdateTime;       // Última actualización
};

// Base de datos temporal cifrada para descargas por link
class TempDownloadDB {
public:
    TempDownloadDB();
    ~TempDownloadDB();
    
    // Inicializar BD temporal cifrada
    bool initialize();
    
    // Guardar/actualizar descarga
    bool saveDownload(const LinkDownloadInfo& info);
    bool updateDownloadProgress(const std::string& downloadId, int64_t completedChunks, double progressPercent);
    bool updateDownloadStatus(const std::string& downloadId, const std::string& status);
    
    // Recuperar descargas
    std::vector<LinkDownloadInfo> getActiveDownloads();
    LinkDownloadInfo getDownload(const std::string& downloadId);
    
    // Completar y limpiar
    bool markDownloadComplete(const std::string& downloadId);
    bool deleteDownload(const std::string& downloadId);
    
    // Limpiar BD completa (eliminar archivo)
    bool cleanupDatabase();
    
    // Verificar si hay descargas activas
    bool hasActiveDownloads();
    
private:
    sqlite3* m_db;
    std::string m_dbPath;
    std::string m_encryptionKey;
    
    bool createTables();
    std::string generateEncryptionKey();
    bool encryptDatabase(const std::string& key);
};

} // namespace TelegramCloud










