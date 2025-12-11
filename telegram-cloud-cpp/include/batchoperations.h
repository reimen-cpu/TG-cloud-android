#pragma once

#include "database.h"
#include "telegramhandler.h"
#ifndef TELEGRAMCLOUD_ANDROID
#include <wx/wx.h>
#endif
#include <string>
#include <vector>
#include <memory>
#include <set>
#include <map>

namespace TelegramCloud {

// Estructura para información de archivo en operaciones por lote
struct BatchFileInfo {
    std::string fileId;
    std::string fileName;
    std::string fileSize;
    std::string mimeType;
    std::string uploadDate;
    bool isEncrypted;
    std::string category; // "file" o "chunked"
    
    BatchFileInfo() : isEncrypted(false) {}
};

// Callback para progreso de operaciones por lote
using BatchProgressCallback = std::function<void(int current, int total, const std::string& operation, const std::string& currentFile)>;

// Módulo para operaciones por lote
class BatchOperations {
public:
    BatchOperations(Database* database, TelegramHandler* telegramHandler);
    
    // Operaciones por lote
    bool deleteFiles(const std::set<long>& selectedIndices, 
                     const std::map<long, std::string>& itemToFileId,
                     BatchProgressCallback progressCallback = nullptr);
    
    bool downloadFiles(const std::set<long>& selectedIndices,
                       const std::map<long, std::string>& itemToFileId,
                       const std::string& destinationDir,
                       const std::string& decryptionPassword = "",
                       BatchProgressCallback progressCallback = nullptr);
    
    std::string generateGlobalShareLink(const std::set<long>& selectedIndices,
                                       const std::map<long, std::string>& itemToFileId,
                                       const std::string& password);
    
    // Utilidades
    std::vector<BatchFileInfo> getBatchFileInfo(const std::set<long>& selectedIndices,
                                               const std::map<long, std::string>& itemToFileId);
    
    std::string formatFileSize(int64_t bytes);
    std::string generateGlobalShareData(const std::vector<BatchFileInfo>& files);
    
private:
    Database* m_database;
    TelegramHandler* m_telegramHandler;
    
    // Funciones auxiliares
    bool deleteSingleFile(const std::string& fileId, const std::string& fileName);
    bool downloadSingleFile(const BatchFileInfo& fileInfo, const std::string& destinationDir, const std::string& decryptionPassword = "");
    bool downloadChunkedFile(const BatchFileInfo& fileInfo, const std::vector<ChunkInfo>& chunks, const std::string& fullPath, const std::string& decryptionPassword);
    bool downloadDirectFile(const BatchFileInfo& fileInfo, const std::string& fullPath, const std::string& decryptionPassword);
    bool decryptFile(const std::string& inputPath, const std::string& outputPath, const std::string& password);
    std::string aesDecrypt(const std::string& ciphertext, const std::string& password);
    std::string deriveKey(const std::string& password, const std::string& salt);
    std::string encryptShareData(const std::string& data, const std::string& password);
};

} // namespace TelegramCloud
