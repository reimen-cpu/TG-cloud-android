#include "batchoperations.h"
#include "logger.h"
#ifndef TELEGRAMCLOUD_ANDROID
#include <wx/filename.h>
#include <wx/msgdlg.h>
#endif
#include <filesystem>
#include <thread>
#include <future>
#include <algorithm>
#include <map>
#include <openssl/aes.h>
#include <openssl/rand.h>
#include <openssl/evp.h>
#include <openssl/sha.h>

namespace TelegramCloud {

BatchOperations::BatchOperations(Database* database, TelegramHandler* telegramHandler)
    : m_database(database), m_telegramHandler(telegramHandler) {
    LOG_INFO("BatchOperations initialized");
}

bool BatchOperations::deleteFiles(const std::set<long>& selectedIndices,
                                  const std::map<long, std::string>& itemToFileId,
                                  BatchProgressCallback progressCallback) {
    LOG_INFO("Starting batch delete for " + std::to_string(selectedIndices.size()) + " files");
    
    int totalFiles = selectedIndices.size();
    int successfulDeletes = 0;
    int failedDeletes = 0;
    int current = 0;
    
    for (long index : selectedIndices) {
        current++;
        
        auto it = itemToFileId.find(index);
        if (it == itemToFileId.end()) {
            LOG_ERROR("File ID not found for index: " + std::to_string(index));
            failedDeletes++;
            continue;
        }
        
        std::string fileId = it->second;
        
        // Obtener información del archivo para logging
        FileInfo fileInfo = m_database->getFileInfo(fileId);
        std::string fileName = fileInfo.fileName.empty() ? "Unknown" : fileInfo.fileName;
        
        if (progressCallback) {
            progressCallback(current, totalFiles, "Deleting", fileName);
        }
        
        LOG_INFO("Deleting file " + std::to_string(current) + "/" + std::to_string(totalFiles) + ": " + fileName);
        
        if (deleteSingleFile(fileId, fileName)) {
            successfulDeletes++;
            LOG_INFO("Successfully deleted: " + fileName);
        } else {
            failedDeletes++;
            LOG_ERROR("Failed to delete: " + fileName);
        }
    }
    
    LOG_INFO("Batch delete completed: " + std::to_string(successfulDeletes) + " successful, " + std::to_string(failedDeletes) + " failed");
    return failedDeletes == 0;
}

bool BatchOperations::downloadFiles(const std::set<long>& selectedIndices,
                                    const std::map<long, std::string>& itemToFileId,
                                    const std::string& destinationDir,
                                    const std::string& decryptionPassword,
                                    BatchProgressCallback progressCallback) {
    LOG_INFO("Starting batch download for " + std::to_string(selectedIndices.size()) + " files");
    
    // Verificar si hay archivos encriptados
    bool hasEncryptedFiles = false;
    std::vector<BatchFileInfo> batchFiles = getBatchFileInfo(selectedIndices, itemToFileId);
    
    for (const auto& file : batchFiles) {
        if (file.isEncrypted) {
            hasEncryptedFiles = true;
            break;
        }
    }
    
    // Si hay archivos encriptados pero no se proporcionó contraseña, solicitar
    std::string password = decryptionPassword;
    if (hasEncryptedFiles && password.empty()) {
#ifndef TELEGRAMCLOUD_ANDROID
        wxString wxPassword = wxGetPasswordFromUser(
            "Some files are encrypted. Enter the decryption password:",
            "Batch Download - Decryption",
            "",
            nullptr
        );
        
        if (wxPassword.IsEmpty()) {
            LOG_INFO("Batch download canceled - no password provided for encrypted files");
            return false;
        }
        
        password = std::string(wxPassword.mb_str());
#else
        // En Android, la contraseña debe proporcionarse via parametro
        LOG_ERROR("Encrypted files require password on Android - must be provided via parameter");
        return false;
#endif
    }
    
    int totalFiles = selectedIndices.size();
    int successfulDownloads = 0;
    int failedDownloads = 0;
    int current = 0;
    
    for (long index : selectedIndices) {
        current++;
        
        auto it = itemToFileId.find(index);
        if (it == itemToFileId.end()) {
            LOG_ERROR("File ID not found for index: " + std::to_string(index));
            failedDownloads++;
            continue;
        }
        
        std::string fileId = it->second;
        
        // Obtener información del archivo
        FileInfo fileInfo = m_database->getFileInfo(fileId);
        if (fileInfo.fileId.empty()) {
            LOG_ERROR("File not found in database: " + fileId);
            failedDownloads++;
            continue;
        }
        
        BatchFileInfo batchFileInfo;
        batchFileInfo.fileId = fileId;
        batchFileInfo.fileName = fileInfo.fileName;
        batchFileInfo.fileSize = formatFileSize(fileInfo.fileSize);
        batchFileInfo.mimeType = fileInfo.mimeType;
        batchFileInfo.uploadDate = fileInfo.uploadDate;
        batchFileInfo.isEncrypted = fileInfo.isEncrypted;
        batchFileInfo.category = fileInfo.category;
        
        if (progressCallback) {
            progressCallback(current, totalFiles, "Downloading", batchFileInfo.fileName);
        }
        
        LOG_INFO("Downloading file " + std::to_string(current) + "/" + std::to_string(totalFiles) + ": " + batchFileInfo.fileName);
        
        std::string filePassword = batchFileInfo.isEncrypted ? password : "";
        
        if (downloadSingleFile(batchFileInfo, destinationDir, filePassword)) {
            successfulDownloads++;
            LOG_INFO("Successfully downloaded: " + batchFileInfo.fileName);
        } else {
            failedDownloads++;
            LOG_ERROR("Failed to download: " + batchFileInfo.fileName);
        }
    }
    
    LOG_INFO("Batch download completed: " + std::to_string(successfulDownloads) + " successful, " + std::to_string(failedDownloads) + " failed");
    return failedDownloads == 0;
}

std::string BatchOperations::generateGlobalShareLink(const std::set<long>& selectedIndices,
                                                    const std::map<long, std::string>& itemToFileId,
                                                    const std::string& password) {
    LOG_INFO("Generating global share link for " + std::to_string(selectedIndices.size()) + " files");
    
    std::vector<BatchFileInfo> batchFiles = getBatchFileInfo(selectedIndices, itemToFileId);
    
    if (batchFiles.empty()) {
        LOG_ERROR("No files found for global share link");
        return "";
    }
    
    std::string shareData = generateGlobalShareData(batchFiles);
    std::string encryptedLink = encryptShareData(shareData, password);
    
    LOG_INFO("Global share link generated successfully");
    return encryptedLink;
}

std::vector<BatchFileInfo> BatchOperations::getBatchFileInfo(const std::set<long>& selectedIndices,
                                                            const std::map<long, std::string>& itemToFileId) {
    std::vector<BatchFileInfo> batchFiles;
    
    for (long index : selectedIndices) {
        auto it = itemToFileId.find(index);
        if (it == itemToFileId.end()) {
            continue;
        }
        
        std::string fileId = it->second;
        FileInfo fileInfo = m_database->getFileInfo(fileId);
        
        if (fileInfo.fileId.empty()) {
            continue;
        }
        
        BatchFileInfo batchFileInfo;
        batchFileInfo.fileId = fileInfo.fileId;
        batchFileInfo.fileName = fileInfo.fileName;
        batchFileInfo.fileSize = formatFileSize(fileInfo.fileSize);
        batchFileInfo.mimeType = fileInfo.mimeType;
        batchFileInfo.uploadDate = fileInfo.uploadDate;
        batchFileInfo.isEncrypted = fileInfo.isEncrypted;
        batchFileInfo.category = fileInfo.category;
        
        batchFiles.push_back(batchFileInfo);
    }
    
    return batchFiles;
}

std::string BatchOperations::formatFileSize(int64_t bytes) {
    double sizeMB = bytes / 1024.0 / 1024.0;
    if (sizeMB < 1.0) {
        return std::to_string(bytes / 1024.0) + " KB";
    } else if (sizeMB < 1024.0) {
        return std::to_string(sizeMB) + " MB";
    } else {
        return std::to_string(sizeMB / 1024.0) + " GB";
    }
}

std::string BatchOperations::generateGlobalShareData(const std::vector<BatchFileInfo>& files) {
    std::string jsonData = "{\"type\":\"batch\",\"files\":[";
    
    for (size_t i = 0; i < files.size(); ++i) {
        if (i > 0) jsonData += ",";
        
        jsonData += "{";
        jsonData += "\"id\":\"" + files[i].fileId + "\",";
        jsonData += "\"name\":\"" + files[i].fileName + "\",";
        jsonData += "\"size\":\"" + files[i].fileSize + "\",";
        jsonData += "\"type\":\"" + files[i].mimeType + "\",";
        jsonData += "\"category\":\"" + files[i].category + "\",";
        jsonData += "\"encrypted\":" + std::string(files[i].isEncrypted ? "true" : "false");
        jsonData += "}";
    }
    
    jsonData += "],\"count\":" + std::to_string(files.size()) + "}";
    
    return jsonData;
}

bool BatchOperations::deleteSingleFile(const std::string& fileId, const std::string& fileName) {
    try {
        // 1. Obtener mensajes a eliminar de Telegram
        auto messagesToDelete = m_database->getMessagesToDelete(fileId);
        
        // 2. Eliminar mensajes de Telegram
        for (const auto& msg : messagesToDelete) {
            int64_t messageId = msg.first;
            std::string botToken = msg.second;
            
            if (!m_telegramHandler->deleteMessage(messageId, botToken)) {
                LOG_WARNING("Failed to delete message " + std::to_string(messageId) + " from Telegram");
            }
        }
        
        // 3. Eliminar de la base de datos
        if (!m_database->deleteFile(fileId)) {
            LOG_ERROR("Failed to delete file from database: " + fileId);
            return false;
        }
        
        return true;
        
    } catch (const std::exception& e) {
        LOG_ERROR("Exception during file deletion: " + std::string(e.what()));
        return false;
    }
}

bool BatchOperations::downloadSingleFile(const BatchFileInfo& fileInfo, const std::string& destinationDir, const std::string& decryptionPassword) {
    try {
        std::string fileName = fileInfo.fileName;
        std::string fullPath = destinationDir + "/" + fileName;
        
        bool success = false;
        
        if (fileInfo.category == "chunked") {
            // Descargar archivo chunked
            auto chunks = m_database->getFileChunks(fileInfo.fileId);
            if (!chunks.empty()) {
                success = downloadChunkedFile(fileInfo, chunks, fullPath, decryptionPassword);
            }
        } else {
            // Descargar archivo directo
            success = downloadDirectFile(fileInfo, fullPath, decryptionPassword);
        }
        
        return success;
        
    } catch (const std::exception& e) {
        LOG_ERROR("Exception during file download: " + std::string(e.what()));
        return false;
    }
}

bool BatchOperations::downloadChunkedFile(const BatchFileInfo& fileInfo, const std::vector<ChunkInfo>& chunks, const std::string& fullPath, const std::string& decryptionPassword) {
    try {
        // Crear directorio temporal
        std::string tempDir = "temp_batch_download_" + fileInfo.fileId;
        std::filesystem::create_directories(tempDir);
        
        // Descargar chunks
        for (const auto& chunk : chunks) {
            std::string chunkPath = tempDir + "/chunk_" + std::to_string(chunk.chunkNumber) + ".tmp";
            
            if (!m_telegramHandler->downloadFile(chunk.telegramFileId, chunkPath)) {
                std::filesystem::remove_all(tempDir);
                return false;
            }
        }
        
        // Reconstruir archivo
        std::ofstream finalFile(fullPath, std::ios::binary);
        if (!finalFile.is_open()) {
            std::filesystem::remove_all(tempDir);
            return false;
        }
        
        for (const auto& chunk : chunks) {
            std::string chunkPath = tempDir + "/chunk_" + std::to_string(chunk.chunkNumber) + ".tmp";
            std::ifstream chunkFile(chunkPath, std::ios::binary);
            if (chunkFile.is_open()) {
                finalFile << chunkFile.rdbuf();
                chunkFile.close();
            }
        }
        
        finalFile.close();
        
        // Limpiar archivos temporales
        std::filesystem::remove_all(tempDir);
        
        // Desencriptar si es necesario
        if (!decryptionPassword.empty()) {
            std::string tempEncryptedPath = fullPath + ".tmp";
            std::filesystem::rename(fullPath, tempEncryptedPath);
            
            if (!decryptFile(tempEncryptedPath, fullPath, decryptionPassword)) {
                std::filesystem::rename(tempEncryptedPath, fullPath);
                return false;
            }
            
            std::filesystem::remove(tempEncryptedPath);
        }
        
        return true;
        
    } catch (const std::exception& e) {
        LOG_ERROR("Exception during chunked file download: " + std::string(e.what()));
        return false;
    }
}

bool BatchOperations::downloadDirectFile(const BatchFileInfo& fileInfo, const std::string& fullPath, const std::string& decryptionPassword) {
    try {
        FileInfo dbFileInfo = m_database->getFileInfo(fileInfo.fileId);
        bool success = m_telegramHandler->downloadFile(dbFileInfo.telegramFileId, fullPath);
        
        if (success && !decryptionPassword.empty()) {
            std::string tempEncryptedPath = fullPath + ".tmp";
            std::filesystem::rename(fullPath, tempEncryptedPath);
            
            if (!decryptFile(tempEncryptedPath, fullPath, decryptionPassword)) {
                std::filesystem::rename(tempEncryptedPath, fullPath);
                return false;
            }
            
            std::filesystem::remove(tempEncryptedPath);
        }
        
        return success;
        
    } catch (const std::exception& e) {
        LOG_ERROR("Exception during direct file download: " + std::string(e.what()));
        return false;
    }
}

bool BatchOperations::decryptFile(const std::string& inputPath, const std::string& outputPath, const std::string& password) {
    try {
        // Leer archivo encriptado
        std::ifstream inFile(inputPath, std::ios::binary);
        if (!inFile) {
            return false;
        }
        
        std::vector<char> fileData((std::istreambuf_iterator<char>(inFile)), std::istreambuf_iterator<char>());
        inFile.close();
        
        std::string ciphertext(fileData.begin(), fileData.end());
        
        // Desencriptar
        std::string decrypted = aesDecrypt(ciphertext, password);
        
        // Escribir archivo desencriptado
        std::ofstream outFile(outputPath, std::ios::binary);
        if (!outFile) {
            return false;
        }
        
        outFile.write(decrypted.c_str(), decrypted.size());
        outFile.close();
        
        return true;
        
    } catch (const std::exception& e) {
        LOG_ERROR("File decryption failed: " + std::string(e.what()));
        return false;
    }
}

std::string BatchOperations::aesDecrypt(const std::string& ciphertext, const std::string& password) {
    try {
        if (ciphertext.length() < 32) {
            throw std::runtime_error("Invalid ciphertext length");
        }
        
        // Extraer salt, IV y datos encriptados
        std::string salt = ciphertext.substr(0, 16);
        std::string iv = ciphertext.substr(16, 16);
        std::string encrypted_data = ciphertext.substr(32);
        
        // Derivar clave
        std::string key = deriveKey(password, salt);
        
        // Inicializar contexto de desencriptación
        EVP_CIPHER_CTX* ctx = EVP_CIPHER_CTX_new();
        if (!ctx) {
            throw std::runtime_error("Failed to create decryption context");
        }
        
        if (EVP_DecryptInit_ex(ctx, EVP_aes_256_cbc(), NULL,
                              reinterpret_cast<const unsigned char*>(key.c_str()),
                              reinterpret_cast<const unsigned char*>(iv.c_str())) != 1) {
            EVP_CIPHER_CTX_free(ctx);
            throw std::runtime_error("Failed to initialize decryption");
        }
        
        // Desencriptar datos
        int len;
        int plaintext_len;
        std::string plaintext(encrypted_data.length() + AES_BLOCK_SIZE, 0);
        
        if (EVP_DecryptUpdate(ctx, reinterpret_cast<unsigned char*>(&plaintext[0]), &len,
                             reinterpret_cast<const unsigned char*>(encrypted_data.c_str()), encrypted_data.length()) != 1) {
            EVP_CIPHER_CTX_free(ctx);
            throw std::runtime_error("Decryption update failed");
        }
        plaintext_len = len;
        
        if (EVP_DecryptFinal_ex(ctx, reinterpret_cast<unsigned char*>(&plaintext[0]) + len, &len) != 1) {
            EVP_CIPHER_CTX_free(ctx);
            throw std::runtime_error("Decryption finalization failed");
        }
        plaintext_len += len;
        
        EVP_CIPHER_CTX_free(ctx);
        plaintext.resize(plaintext_len);
        
        return plaintext;
        
    } catch (const std::exception& e) {
        LOG_ERROR("AES decryption error: " + std::string(e.what()));
        return "";
    }
}

std::string BatchOperations::deriveKey(const std::string& password, const std::string& salt) {
    std::string key(32, 0);
    
    if (PKCS5_PBKDF2_HMAC(
        password.c_str(), password.length(),
        reinterpret_cast<const unsigned char*>(salt.c_str()), salt.length(),
        10000,
        EVP_sha256(),
        32,
        reinterpret_cast<unsigned char*>(&key[0])
    ) != 1) {
        throw std::runtime_error("Key derivation failed");
    }
    
    return key;
}

std::string BatchOperations::encryptShareData(const std::string& data, const std::string& password) {
    try {
        // Generar salt aleatorio
        std::string salt(16, 0);
        if (RAND_bytes(reinterpret_cast<unsigned char*>(&salt[0]), 16) != 1) {
            throw std::runtime_error("Failed to generate salt");
        }
        
        // Derivar clave
        std::string key = deriveKey(password, salt);
        
        // Inicializar contexto de encriptación
        EVP_CIPHER_CTX* ctx = EVP_CIPHER_CTX_new();
        if (!ctx) {
            throw std::runtime_error("Failed to create encryption context");
        }
        
        // Generar IV aleatorio
        unsigned char iv[AES_BLOCK_SIZE];
        if (RAND_bytes(iv, AES_BLOCK_SIZE) != 1) {
            EVP_CIPHER_CTX_free(ctx);
            throw std::runtime_error("Failed to generate IV");
        }
        
        // Inicializar encriptación
        if (EVP_EncryptInit_ex(ctx, EVP_aes_256_cbc(), NULL,
                              reinterpret_cast<const unsigned char*>(key.c_str()), iv) != 1) {
            EVP_CIPHER_CTX_free(ctx);
            throw std::runtime_error("Failed to initialize encryption");
        }
        
        // Encriptar datos
        int len;
        int ciphertext_len;
        std::string ciphertext(data.length() + AES_BLOCK_SIZE, 0);
        
        if (EVP_EncryptUpdate(ctx, reinterpret_cast<unsigned char*>(&ciphertext[0]), &len,
                             reinterpret_cast<const unsigned char*>(data.c_str()), data.length()) != 1) {
            EVP_CIPHER_CTX_free(ctx);
            throw std::runtime_error("Encryption update failed");
        }
        ciphertext_len = len;
        
        if (EVP_EncryptFinal_ex(ctx, reinterpret_cast<unsigned char*>(&ciphertext[0]) + len, &len) != 1) {
            EVP_CIPHER_CTX_free(ctx);
            throw std::runtime_error("Encryption finalization failed");
        }
        ciphertext_len += len;
        
        EVP_CIPHER_CTX_free(ctx);
        ciphertext.resize(ciphertext_len);
        
        // Combinar: salt + iv + ciphertext
        std::string result;
        result.reserve(16 + 16 + ciphertext.length());
        result += salt;
        result += std::string(reinterpret_cast<char*>(iv), AES_BLOCK_SIZE);
        result += ciphertext;
        
        return result;
        
    } catch (const std::exception& e) {
        LOG_ERROR("Share data encryption error: " + std::string(e.what()));
        return "";
    }
}

} // namespace TelegramCloud
