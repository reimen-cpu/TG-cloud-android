#ifndef DATABASE_H
#define DATABASE_H

#include <string>
#include <vector>
#include <memory>
#include <sqlite3.h>
#include <random>
#include <sstream>
#include <iomanip>

namespace TelegramCloud {

// Forward declarations
struct ChunkInfo;

struct FileInfo {
    int64_t id = 0;
    std::string fileId;
    std::string fileName;
    int64_t fileSize = 0;
    std::string mimeType;
    std::string category;
    std::string uploadDate;
    int64_t messageId = 0;
    std::string telegramFileId;
    std::string uploaderBotToken;
    bool isEncrypted = false;
};

struct ChunkInfo {
    int64_t id;
    std::string fileId;
    int64_t chunkNumber;
    int64_t totalChunks;
    int64_t chunkSize;
    std::string chunkHash;
    std::string telegramFileId;
    int64_t messageId;
    std::string status;
    std::string uploaderBotToken;
};

struct ChunkedFileInfo {
    std::string fileId;
    std::string originalFilename;
    std::string mimeType;
    int64_t totalSize;
    int64_t totalChunks;
    int64_t completedChunks;
    std::string status;
    std::string originalFileHash;
    bool isEncrypted;
};

struct DownloadInfo {
    std::string downloadId;
    std::string fileId;
    std::string fileName;
    std::string destPath;
    int64_t totalSize;
    int64_t totalChunks;
    int64_t completedChunks;
    std::string status;
    std::string tempDir;
};

/**
 * @brief Manejo de base de datos SQLite
 */
class Database {
public:
    Database();
    ~Database();
    
    bool initialize(const std::string& dbPath);
    void close();
    bool setupTables();
    
    // Encryption methods
    std::string generateEncryptionKey();
    bool setEncryptionKey(const std::string& key);
    bool isDatabaseEncrypted();
    
    // File operations
    bool saveFileInfo(const FileInfo& fileInfo);
    std::vector<FileInfo> getFiles();
    FileInfo getFileInfo(const std::string& fileId);
    bool deleteFile(const std::string& fileId);
    std::vector<std::pair<int64_t, std::string>> getMessagesToDelete(const std::string& fileId);
    
    // Chunk operations
    bool registerChunkedFile(const ChunkedFileInfo& fileInfo);
    bool saveChunkInfo(const ChunkInfo& chunkInfo);
    std::vector<ChunkInfo> getFileChunks(const std::string& fileId);
    
    // Upload progress persistence
    bool updateUploadState(const std::string& fileId, const std::string& state);
    bool updateChunkState(const std::string& fileId, int64_t chunkNumber, const std::string& state);
    std::vector<ChunkedFileInfo> getIncompleteUploads();
    std::vector<int64_t> getCompletedChunks(const std::string& fileId);
    bool validateChunkIntegrity(const std::string& fileId, int64_t chunkNumber, const std::string& expectedHash);
    bool deleteUploadProgress(const std::string& fileId);
    bool updateUploadProgress(const std::string& fileId, int64_t completedChunks);
    bool markAllActiveUploadsAsPaused();
    bool finalizeChunkedFile(const std::string& fileId, const std::string& telegramFileId = "");
    
    // Download progress persistence
    bool registerDownload(const DownloadInfo& downloadInfo);
    bool updateDownloadState(const std::string& downloadId, const std::string& state);
    bool updateDownloadChunkState(const std::string& downloadId, int64_t chunkNumber, const std::string& state);
    std::vector<DownloadInfo> getIncompleteDownloads();
    std::vector<int64_t> getCompletedDownloadChunks(const std::string& downloadId);
    bool validateDownloadChunkExists(const std::string& downloadId, int64_t chunkNumber);
    bool deleteDownloadProgress(const std::string& downloadId);
    bool updateDownloadProgress(const std::string& downloadId, int64_t completedChunks);
    bool markAllActiveDownloadsAsPaused();
    
    // Statistics
    int64_t getTotalStorageUsed();
    int getTotalFilesCount();
    
private:
    sqlite3* m_db;
    std::string m_dbPath;
    std::string m_encryptionKey;
    bool m_isEncrypted;
    
    bool executeQuery(const std::string& query);
    std::string getLastError() const;
    bool configureEncryption();
};

} // namespace TelegramCloud

#endif // DATABASE_H
