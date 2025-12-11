#include "linkdownloadmanager.h"
#include "logger.h"
#include <openssl/rand.h>
#include <openssl/evp.h>
#include <openssl/aes.h>
#include <sstream>
#include <iomanip>
#include <fstream>
#include <filesystem>
#include <future>
#include <thread>
#include <chrono>

namespace TelegramCloud {

LinkDownloadManager::LinkDownloadManager(TelegramHandler* telegramHandler)
    : m_telegramHandler(telegramHandler)
    , m_tempDB(std::make_unique<TempDownloadDB>()) {
}

LinkDownloadManager::~LinkDownloadManager() {
    // NO eliminar la BD al destruir - solo cerrar conexión
    // La BD se elimina solo cuando TODAS las descargas se completen
    LOG_INFO("LinkDownloadManager destructor - preserving temp database for persistence");
}

bool LinkDownloadManager::initialize() {
    LOG_INFO("Initializing LinkDownloadManager");
    
    if (!m_tempDB->initialize()) {
        LOG_ERROR("Failed to initialize temporary download database");
        return false;
    }
    
    LOG_INFO("LinkDownloadManager initialized successfully");
    return true;
}

std::vector<LinkDownloadInfo> LinkDownloadManager::checkIncompleteDownloads() {
    LOG_INFO("Checking for incomplete link downloads");
    return m_tempDB->getActiveDownloads();
}

std::string LinkDownloadManager::generateDownloadId() {
    unsigned char bytes[16];
    if (RAND_bytes(bytes, 16) != 1) {
        return "";
    }
    
    std::stringstream ss;
    ss << "linkdl_";
    for (int i = 0; i < 16; i++) {
        ss << std::hex << std::setw(2) << std::setfill('0') << (int)bytes[i];
    }
    
    return ss.str();
}

bool LinkDownloadManager::parseLinkData(
    const std::string& shareData,
    std::string& fileId,
    std::string& fileName,
    std::string& fileType,
    int64_t& fileSize,
    bool& isEncrypted,
    std::vector<ChunkInfo>& chunks,
    std::string& telegramFileId
) {
    // Parser JSON simple
    auto extractString = [&shareData](const std::string& key) -> std::string {
        size_t pos = shareData.find("\"" + key + "\":\"");
        if (pos == std::string::npos) return "";
        pos += key.length() + 4;
        size_t end = shareData.find("\"", pos);
        return shareData.substr(pos, end - pos);
    };
    
    auto extractNumber = [&shareData](const std::string& key) -> int64_t {
        size_t pos = shareData.find("\"" + key + "\":");
        if (pos == std::string::npos) return 0;
        pos += key.length() + 3;
        size_t end = shareData.find_first_of(",}", pos);
        std::string numStr = shareData.substr(pos, end - pos);
        try {
            return std::stoll(numStr);
        } catch (...) {
            return 0;
        }
    };
    
    auto extractBool = [&shareData](const std::string& key) -> bool {
        size_t pos = shareData.find("\"" + key + "\":");
        if (pos == std::string::npos) return false;
        pos += key.length() + 3;
        return shareData.substr(pos, 4) == "true";
    };
    
    // Extraer datos básicos
    fileId = extractString("file_id");
    fileName = extractString("filename");
    fileType = extractString("type");
    fileSize = extractNumber("size");
    isEncrypted = extractBool("encrypted");
    
    if (fileId.empty() || fileType.empty()) {
        LOG_ERROR("Failed to parse basic link data");
        return false;
    }
    
    // Parsear chunks si es chunked
    if (fileType == "chunked") {
        size_t chunksPos = shareData.find("\"chunks\":[");
        if (chunksPos == std::string::npos) {
            LOG_ERROR("Chunked file but no chunks data");
            return false;
        }
        
        chunksPos += 10;
        size_t chunksEnd = shareData.find("]", chunksPos);
        std::string chunksData = shareData.substr(chunksPos, chunksEnd - chunksPos);
        
        size_t pos = 0;
        while (pos < chunksData.length()) {
            size_t chunkStart = chunksData.find("{", pos);
            if (chunkStart == std::string::npos) break;
            
            size_t chunkEnd = chunksData.find("}", chunkStart);
            if (chunkEnd == std::string::npos) break;
            
            std::string chunkStr = chunksData.substr(chunkStart, chunkEnd - chunkStart + 1);
            
            ChunkInfo chunk;
            
            size_t nPos = chunkStr.find("\"n\":");
            if (nPos != std::string::npos) {
                nPos += 4;
                size_t nEnd = chunkStr.find_first_of(",}", nPos);
                chunk.chunkNumber = std::stoi(chunkStr.substr(nPos, nEnd - nPos));
            }
            
            size_t tidPos = chunkStr.find("\"tid\":\"");
            if (tidPos != std::string::npos) {
                tidPos += 7;
                size_t tidEnd = chunkStr.find("\"", tidPos);
                chunk.telegramFileId = chunkStr.substr(tidPos, tidEnd - tidPos);
            }
            
            size_t sPos = chunkStr.find("\"s\":");
            if (sPos != std::string::npos) {
                sPos += 4;
                size_t sEnd = chunkStr.find_first_of(",}", sPos);
                chunk.chunkSize = std::stoll(chunkStr.substr(sPos, sEnd - sPos));
            }
            
            size_t hPos = chunkStr.find("\"h\":\"");
            if (hPos != std::string::npos) {
                hPos += 5;
                size_t hEnd = chunkStr.find("\"", hPos);
                chunk.chunkHash = chunkStr.substr(hPos, hEnd - hPos);
            }
            
            chunks.push_back(chunk);
            pos = chunkEnd + 1;
        }
        
        LOG_INFO("Parsed " + std::to_string(chunks.size()) + " chunks from link data");
    } else if (fileType == "direct") {
        telegramFileId = extractString("telegram_file_id");
        if (telegramFileId.empty()) {
            LOG_ERROR("Direct file but no telegram_file_id");
            return false;
        }
    }
    
    return true;
}

std::string LinkDownloadManager::startDownloadFromLink(
    const std::string& shareData,
    const std::string& saveDirectory,
    const std::string& filePassword,
    LinkDownloadProgressCallback progressCallback
) {
    LOG_INFO("Starting new download from link");
    
    // Parsear datos del link
    std::string fileId, fileName, fileType, telegramFileId;
    int64_t fileSize;
    bool isEncrypted;
    std::vector<ChunkInfo> chunks;
    
    if (!parseLinkData(shareData, fileId, fileName, fileType, fileSize, isEncrypted, chunks, telegramFileId)) {
        LOG_ERROR("Failed to parse link data");
        return "";
    }
    
    // Generar ID de descarga
    std::string downloadId = generateDownloadId();
    if (downloadId.empty()) {
        LOG_ERROR("Failed to generate download ID");
        return "";
    }
    
    // Crear registro en BD temporal
    LinkDownloadInfo info;
    info.downloadId = downloadId;
    info.fileId = fileId;
    info.fileName = fileName;
    info.fileType = fileType;
    info.fileSize = fileSize;
    info.isEncrypted = isEncrypted;
    info.saveDirectory = saveDirectory;
    info.status = "active";
    info.completedChunks = 0;
    info.totalChunks = chunks.size();
    info.progressPercent = 0.0;
    info.shareData = shareData;
    
    if (!m_tempDB->saveDownload(info)) {
        LOG_ERROR("Failed to save download to temp DB");
        return "";
    }
    
    LOG_INFO("Download registered in temp DB: " + downloadId);
    
    // Iniciar descarga
    bool success = false;
    if (fileType == "chunked") {
        success = downloadChunkedFile(downloadId, info, filePassword, progressCallback);
    } else if (fileType == "direct") {
        success = downloadDirectFile(downloadId, info, filePassword, progressCallback);
    }
    
    if (success) {
        // Marcar como completada y eliminar de BD temporal
        m_tempDB->markDownloadComplete(downloadId);
        LOG_INFO("Download completed and removed from temp DB: " + downloadId);
    }
    
    return downloadId;
}

bool LinkDownloadManager::resumeDownload(
    const std::string& downloadId,
    const std::string& filePassword,
    LinkDownloadProgressCallback progressCallback
) {
    LOG_INFO("Resuming download: " + downloadId);
    
    // Obtener información de la descarga
    LinkDownloadInfo info = m_tempDB->getDownload(downloadId);
    if (info.downloadId.empty()) {
        LOG_ERROR("Download not found in temp DB: " + downloadId);
        return false;
    }
    
    // Actualizar estado a activo
    m_tempDB->updateDownloadStatus(downloadId, "active");
    
    // Continuar descarga
    bool success = false;
    if (info.fileType == "chunked") {
        success = downloadChunkedFile(downloadId, info, filePassword, progressCallback);
    } else if (info.fileType == "direct") {
        success = downloadDirectFile(downloadId, info, filePassword, progressCallback);
    }
    
    if (success) {
        m_tempDB->markDownloadComplete(downloadId);
        LOG_INFO("Resumed download completed: " + downloadId);
    }
    
    return success;
}

bool LinkDownloadManager::downloadChunkedFile(
    const std::string& downloadId,
    const LinkDownloadInfo& info,
    const std::string& filePassword,
    LinkDownloadProgressCallback progressCallback
) {
    LOG_INFO("Downloading chunked file: " + info.fileName);
    
    // Parsear chunks del shareData
    std::string fileId, fileName, fileType, telegramFileId;
    int64_t fileSize;
    bool isEncrypted;
    std::vector<ChunkInfo> chunks;
    
    if (!parseLinkData(info.shareData, fileId, fileName, fileType, fileSize, isEncrypted, chunks, telegramFileId)) {
        LOG_ERROR("Failed to parse chunks");
        m_tempDB->updateDownloadStatus(downloadId, "failed");
        return false;
    }
    
    if (chunks.empty()) {
        LOG_ERROR("No chunks to download");
        m_tempDB->updateDownloadStatus(downloadId, "failed");
        return false;
    }
    
    // FASE 1: Descargar chunks en paralelo
    if (progressCallback) {
        progressCallback(0, chunks.size(), 0.0, "Downloading chunks");
    }
    
    std::string tempDir = "temp_linkdl_" + downloadId;
    std::filesystem::create_directories(tempDir);
    
    std::atomic<int> downloadedChunks(info.completedChunks);
    int totalChunks = chunks.size();
    
    const int MAX_CONCURRENT = 5;
    bool allSuccess = true;
    
    for (size_t i = 0; i < chunks.size(); i += MAX_CONCURRENT) {
        std::vector<std::future<bool>> futures;
        size_t batchEnd = std::min(i + MAX_CONCURRENT, chunks.size());
        
        for (size_t j = i; j < batchEnd; j++) {
            const ChunkInfo& chunk = chunks[j];
            
            // Verificar si este chunk ya existe
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
                    int completed = ++downloadedChunks;
                    double percent = (double)completed / totalChunks * 100.0;
                    
                    // Actualizar BD
                    m_tempDB->updateDownloadProgress(downloadId, completed, percent);
                    
                    if (progressCallback) {
                        progressCallback(completed, totalChunks, percent, "Downloading chunks");
                    }
                }
                
                return success;
            });
            
            futures.push_back(std::move(future));
        }
        
        for (auto& f : futures) {
            if (!f.get()) {
                allSuccess = false;
            }
        }
        
        if (!allSuccess) break;
    }
    
    if (!allSuccess) {
        LOG_ERROR("Failed to download all chunks");
        m_tempDB->updateDownloadStatus(downloadId, "failed");
        return false;
    }
    
    // FASE 2: Reconstruir archivo
    if (progressCallback) {
        progressCallback(0, totalChunks, 0.0, "Reconstructing file");
    }
    
    std::string destPath = info.saveDirectory + "/" + info.fileName;
    std::ofstream finalFile(destPath, std::ios::binary);
    if (!finalFile.is_open()) {
        LOG_ERROR("Failed to create output file");
        std::filesystem::remove_all(tempDir);
        m_tempDB->updateDownloadStatus(downloadId, "failed");
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
            double percent = (double)reconstructed / totalChunks * 100.0;
            
            if (progressCallback) {
                progressCallback(reconstructed, totalChunks, percent, "Reconstructing file");
            }
        }
    }
    
    finalFile.close();
    std::filesystem::remove_all(tempDir);
    
    LOG_INFO("File reconstructed: " + destPath);
    
    // FASE 3: Desencriptar si es necesario
    if (info.isEncrypted && !filePassword.empty()) {
        if (progressCallback) {
            progressCallback(0, 1, 0.0, "Decrypting file");
        }
        
        std::string tempEncrypted = destPath + ".tmp";
        std::filesystem::rename(destPath, tempEncrypted);
        
        if (!decryptFile(tempEncrypted, destPath, filePassword)) {
            LOG_ERROR("Decryption failed");
            std::filesystem::rename(tempEncrypted, destPath);
            m_tempDB->updateDownloadStatus(downloadId, "failed");
            return false;
        }
        
        std::filesystem::remove(tempEncrypted);
        
        if (progressCallback) {
            progressCallback(1, 1, 100.0, "Decrypting file");
        }
    }
    
    LOG_INFO("Chunked file download completed: " + info.fileName);
    return true;
}

bool LinkDownloadManager::downloadDirectFile(
    const std::string& downloadId,
    const LinkDownloadInfo& info,
    const std::string& filePassword,
    LinkDownloadProgressCallback progressCallback
) {
    LOG_INFO("Downloading direct file: " + info.fileName);
    
    // Parsear telegram_file_id
    std::string fileId, fileName, fileType, telegramFileId;
    int64_t fileSize;
    bool isEncrypted;
    std::vector<ChunkInfo> chunks;
    
    if (!parseLinkData(info.shareData, fileId, fileName, fileType, fileSize, isEncrypted, chunks, telegramFileId)) {
        LOG_ERROR("Failed to parse direct file data");
        m_tempDB->updateDownloadStatus(downloadId, "failed");
        return false;
    }
    
    if (progressCallback) {
        progressCallback(0, 1, 0.0, "Downloading file");
    }
    
    std::string destPath = info.saveDirectory + "/" + info.fileName;
    
    bool success = m_telegramHandler->downloadFile(telegramFileId, destPath);
    
    if (!success) {
        LOG_ERROR("Direct file download failed");
        m_tempDB->updateDownloadStatus(downloadId, "failed");
        return false;
    }
    
    if (progressCallback) {
        progressCallback(1, 1, 100.0, "Downloading file");
    }
    
    // Desencriptar si es necesario
    if (info.isEncrypted && !filePassword.empty()) {
        if (progressCallback) {
            progressCallback(0, 1, 0.0, "Decrypting file");
        }
        
        std::string tempEncrypted = destPath + ".tmp";
        std::filesystem::rename(destPath, tempEncrypted);
        
        if (!decryptFile(tempEncrypted, destPath, filePassword)) {
            LOG_ERROR("Decryption failed");
            std::filesystem::rename(tempEncrypted, destPath);
            m_tempDB->updateDownloadStatus(downloadId, "failed");
            return false;
        }
        
        std::filesystem::remove(tempEncrypted);
        
        if (progressCallback) {
            progressCallback(1, 1, 100.0, "Decrypting file");
        }
    }
    
    LOG_INFO("Direct file download completed: " + info.fileName);
    return true;
}

bool LinkDownloadManager::pauseDownload(const std::string& downloadId) {
    LOG_INFO("Pausing download: " + downloadId);
    return m_tempDB->updateDownloadStatus(downloadId, "paused");
}

bool LinkDownloadManager::cancelDownload(const std::string& downloadId) {
    LOG_INFO("Cancelling download: " + downloadId);
    
    // Eliminar archivos temporales
    std::string tempDir = "temp_linkdl_" + downloadId;
    try {
        if (std::filesystem::exists(tempDir)) {
            std::filesystem::remove_all(tempDir);
        }
    } catch (...) {}
    
    return m_tempDB->deleteDownload(downloadId);
}

LinkDownloadInfo LinkDownloadManager::getDownloadInfo(const std::string& downloadId) {
    return m_tempDB->getDownload(downloadId);
}

void LinkDownloadManager::cleanup() {
    LOG_INFO("Cleaning up LinkDownloadManager");
    
    // Solo eliminar la BD si NO hay descargas pendientes/activas
    // (es decir, todas están completadas)
    if (!m_tempDB->hasActiveDownloads()) {
        LOG_INFO("No pending downloads - cleaning up temp database");
        m_tempDB->cleanupDatabase();
    } else {
        LOG_INFO("Pending downloads exist - preserving temp database for persistence");
    }
}

// Función de desencriptación (copiada de mainwindow.cpp)
bool LinkDownloadManager::decryptFile(const std::string& inputPath, const std::string& outputPath, const std::string& password) {
    // Implementación AES-256 similar a mainwindow.cpp
    try {
        std::ifstream inFile(inputPath, std::ios::binary);
        if (!inFile) return false;
        
        std::vector<char> fileData((std::istreambuf_iterator<char>(inFile)), std::istreambuf_iterator<char>());
        inFile.close();
        
        if (fileData.size() < 32) return false;
        
        std::string ciphertext(fileData.begin(), fileData.end());
        
        // Extraer salt, IV y datos
        std::string salt = ciphertext.substr(0, 16);
        std::string iv = ciphertext.substr(16, 16);
        std::string encrypted_data = ciphertext.substr(32);
        
        // Derivar clave
        std::string key(32, 0);
        if (PKCS5_PBKDF2_HMAC(
            password.c_str(), password.length(),
            reinterpret_cast<const unsigned char*>(salt.c_str()), salt.length(),
            10000,
            EVP_sha256(),
            32,
            reinterpret_cast<unsigned char*>(&key[0])
        ) != 1) {
            return false;
        }
        
        // Desencriptar
        EVP_CIPHER_CTX* ctx = EVP_CIPHER_CTX_new();
        if (!ctx) return false;
        
        if (EVP_DecryptInit_ex(ctx, EVP_aes_256_cbc(), NULL,
                              reinterpret_cast<const unsigned char*>(key.c_str()),
                              reinterpret_cast<const unsigned char*>(iv.c_str())) != 1) {
            EVP_CIPHER_CTX_free(ctx);
            return false;
        }
        
        int len, plaintext_len;
        std::string plaintext(encrypted_data.length() + AES_BLOCK_SIZE, 0);
        
        if (EVP_DecryptUpdate(ctx, reinterpret_cast<unsigned char*>(&plaintext[0]), &len,
                             reinterpret_cast<const unsigned char*>(encrypted_data.c_str()), encrypted_data.length()) != 1) {
            EVP_CIPHER_CTX_free(ctx);
            return false;
        }
        plaintext_len = len;
        
        if (EVP_DecryptFinal_ex(ctx, reinterpret_cast<unsigned char*>(&plaintext[0]) + len, &len) != 1) {
            EVP_CIPHER_CTX_free(ctx);
            return false;
        }
        plaintext_len += len;
        
        EVP_CIPHER_CTX_free(ctx);
        plaintext.resize(plaintext_len);
        
        // Escribir archivo
        std::ofstream outFile(outputPath, std::ios::binary);
        if (!outFile) return false;
        
        outFile.write(plaintext.c_str(), plaintext.size());
        outFile.close();
        
        return true;
        
    } catch (...) {
        return false;
    }
}

} // namespace TelegramCloud

