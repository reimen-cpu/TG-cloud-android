#include "universallinkdownloader.h"
#include "telegramnotifier.h"
#include "logger.h"
#include <fstream>
#include <sstream>
#include <filesystem>
#include <algorithm>
#include <thread>
#include <future>
#include <random>
#include <openssl/evp.h>
#include <openssl/sha.h>

namespace TelegramCloud {

// Funciones auxiliares
static std::string base64Decode(const std::string& data) {
    const std::string chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    std::string result;
    std::vector<int> T(256, -1);
    for (int i = 0; i < 64; i++) T[chars[i]] = i;
    
    int val = 0, valb = -8;
    for (unsigned char c : data) {
        if (T[c] == -1) break;
        val = (val << 6) + T[c];
        valb += 6;
        if (valb >= 0) {
            result.push_back(char((val >> valb) & 0xFF));
            valb -= 8;
        }
    }
    return result;
}

// Parser JSON simple
static std::string extractJsonString(const std::string& json, const std::string& key) {
    std::string searchKey = "\"" + key + "\":\"";
    size_t pos = json.find(searchKey);
    if (pos == std::string::npos) return "";
    
    pos += searchKey.length();
    size_t end = json.find("\"", pos);
    if (end == std::string::npos) return "";
    
    return json.substr(pos, end - pos);
}

static int64_t extractJsonInt(const std::string& json, const std::string& key) {
    std::string searchKey = "\"" + key + "\":";
    size_t pos = json.find(searchKey);
    if (pos == std::string::npos) return 0;
    
    pos += searchKey.length();
    size_t end = json.find_first_of(",}", pos);
    if (end == std::string::npos) return 0;
    
    std::string numStr = json.substr(pos, end - pos);
    try {
        return std::stoll(numStr);
    } catch (...) {
        return 0;
    }
}

static bool extractJsonBool(const std::string& json, const std::string& key) {
    std::string searchKey = "\"" + key + "\":";
    size_t pos = json.find(searchKey);
    if (pos == std::string::npos) return false;
    
    pos += searchKey.length();
    return json.substr(pos, 4) == "true";
}

UniversalLinkDownloader::UniversalLinkDownloader(TelegramHandler* telegramHandler, Database* database, TelegramNotifier* notifier)
    : m_telegramHandler(telegramHandler)
    , m_database(database)
    , m_notifier(notifier) {
}

bool UniversalLinkDownloader::downloadFromLinkFile(
    const std::string& linkFilePath,
    const std::string& password,
    const std::string& destinationDir,
    const std::string& filePassword,
    UniversalLinkProgressCallback progressCallback) {
    
    try {
        LOG_INFO("Starting download from link file: " + linkFilePath);
        
        // Leer y desencriptar archivo .link
        std::string jsonData = readAndDecryptLinkFile(linkFilePath, password);
        if (jsonData.empty()) {
            LOG_ERROR("Failed to decrypt link file");
            return false;
        }
        
        // Parsear datos
        std::vector<FileInfo> filesInfo;
        std::vector<std::vector<ChunkInfo>> filesChunks;
        
        if (!parseLinkData(jsonData, filesInfo, filesChunks)) {
            LOG_ERROR("Failed to parse link data");
            return false;
        }
        
        LOG_INFO("Found " + std::to_string(filesInfo.size()) + " file(s) in link");
        
        // Descargar cada archivo
        for (size_t i = 0; i < filesInfo.size(); i++) {
            if (!downloadSingleFile(filesInfo[i], filesChunks[i], destinationDir, 
                                   filePassword, progressCallback, i + 1, filesInfo.size())) {
                LOG_ERROR("Failed to download file: " + filesInfo[i].fileName);
                return false;
            }
        }
        
        LOG_INFO("All files downloaded successfully from link");
        return true;
        
    } catch (const std::exception& e) {
        LOG_ERROR("Exception in downloadFromLinkFile: " + std::string(e.what()));
        return false;
    }
}

std::vector<FileInfo> UniversalLinkDownloader::getLinkFileInfo(
    const std::string& linkFilePath,
    const std::string& password) {
    
    std::vector<FileInfo> result;
    
    try {
        std::string jsonData = readAndDecryptLinkFile(linkFilePath, password);
        if (jsonData.empty()) {
            return result;
        }
        
        std::vector<std::vector<ChunkInfo>> filesChunks;
        parseLinkData(jsonData, result, filesChunks);
        
    } catch (const std::exception& e) {
        LOG_ERROR("Exception in getLinkFileInfo: " + std::string(e.what()));
    }
    
    return result;
}

std::string UniversalLinkDownloader::readAndDecryptLinkFile(
    const std::string& linkFilePath,
    const std::string& password) {
    
    try {
        // Leer archivo
        std::ifstream inFile(linkFilePath, std::ios::binary);
        if (!inFile) {
            LOG_ERROR("Failed to open link file: " + linkFilePath);
            return "";
        }
        
        std::string encrypted((std::istreambuf_iterator<char>(inFile)),
                             std::istreambuf_iterator<char>());
        inFile.close();
        
        // Desencriptar
        return decryptData(encrypted, password);
        
    } catch (const std::exception& e) {
        LOG_ERROR("Failed to read link file: " + std::string(e.what()));
        return "";
    }
}

bool UniversalLinkDownloader::parseLinkData(
    const std::string& jsonData,
    std::vector<FileInfo>& filesInfo,
    std::vector<std::vector<ChunkInfo>>& filesChunks) {
    
    try {
        // Detectar tipo
        std::string type = extractJsonString(jsonData, "type");
        
        if (type == "single") {
            // Archivo individual
            size_t filePos = jsonData.find("\"file\":{");
            if (filePos == std::string::npos) return false;
            
            std::string fileJson = jsonData.substr(filePos);
            
            FileInfo fileInfo;
            fileInfo.fileId = extractJsonString(fileJson, "fileId");
            fileInfo.fileName = extractJsonString(fileJson, "fileName");
            fileInfo.fileSize = extractJsonInt(fileJson, "fileSize");
            fileInfo.mimeType = extractJsonString(fileJson, "mimeType");
            fileInfo.category = extractJsonString(fileJson, "category");
            fileInfo.uploadDate = extractJsonString(fileJson, "uploadDate");
            fileInfo.telegramFileId = extractJsonString(fileJson, "telegramFileId");
            fileInfo.uploaderBotToken = extractJsonString(fileJson, "uploaderBotToken");
            fileInfo.isEncrypted = extractJsonBool(fileJson, "isEncrypted");
            
            filesInfo.push_back(fileInfo);
            
            // Parsear chunks si existen
            std::vector<ChunkInfo> chunks;
            size_t chunksPos = fileJson.find("\"chunks\":[");
            if (chunksPos != std::string::npos) {
                size_t chunkStart = chunksPos;
                while (true) {
                    chunkStart = fileJson.find("{", chunkStart + 1);
                    if (chunkStart == std::string::npos) break;
                    
                    size_t chunkEnd = fileJson.find("}", chunkStart);
                    if (chunkEnd == std::string::npos) break;
                    
                    std::string chunkJson = fileJson.substr(chunkStart, chunkEnd - chunkStart + 1);
                    
                    ChunkInfo chunk;
                    chunk.fileId = fileInfo.fileId;
                    chunk.chunkNumber = extractJsonInt(chunkJson, "chunkNumber");
                    chunk.totalChunks = extractJsonInt(chunkJson, "totalChunks");
                    chunk.chunkSize = extractJsonInt(chunkJson, "chunkSize");
                    chunk.chunkHash = extractJsonString(chunkJson, "chunkHash");
                    chunk.telegramFileId = extractJsonString(chunkJson, "telegramFileId");
                    chunk.uploaderBotToken = extractJsonString(chunkJson, "uploaderBotToken");
                    
                    chunks.push_back(chunk);
                    
                    chunkStart = chunkEnd;
                    if (fileJson[chunkStart + 1] != ',') break;
                }
            }
            filesChunks.push_back(chunks);
            
        } else if (type == "batch") {
            // Múltiples archivos
            size_t filesArrayPos = jsonData.find("\"files\":[");
            if (filesArrayPos == std::string::npos) return false;
            
            size_t fileStart = filesArrayPos;
            while (true) {
                fileStart = jsonData.find("{\"fileId\"", fileStart + 1);
                if (fileStart == std::string::npos) break;
                
                // Encontrar el cierre del objeto
                int braceCount = 0;
                size_t fileEnd = fileStart;
                bool inString = false;
                for (size_t i = fileStart; i < jsonData.length(); i++) {
                    if (jsonData[i] == '\"' && (i == 0 || jsonData[i-1] != '\\')) {
                        inString = !inString;
                    }
                    if (!inString) {
                        if (jsonData[i] == '{') braceCount++;
                        if (jsonData[i] == '}') {
                            braceCount--;
                            if (braceCount == 0) {
                                fileEnd = i;
                                break;
                            }
                        }
                    }
                }
                
                std::string fileJson = jsonData.substr(fileStart, fileEnd - fileStart + 1);
                
                FileInfo fileInfo;
                fileInfo.fileId = extractJsonString(fileJson, "fileId");
                fileInfo.fileName = extractJsonString(fileJson, "fileName");
                fileInfo.fileSize = extractJsonInt(fileJson, "fileSize");
                fileInfo.mimeType = extractJsonString(fileJson, "mimeType");
                fileInfo.category = extractJsonString(fileJson, "category");
                fileInfo.uploadDate = extractJsonString(fileJson, "uploadDate");
                fileInfo.telegramFileId = extractJsonString(fileJson, "telegramFileId");
                fileInfo.uploaderBotToken = extractJsonString(fileJson, "uploaderBotToken");
                fileInfo.isEncrypted = extractJsonBool(fileJson, "isEncrypted");
                
                filesInfo.push_back(fileInfo);
                
                // Parsear chunks
                std::vector<ChunkInfo> chunks;
                size_t chunksPos = fileJson.find("\"chunks\":[");
                if (chunksPos != std::string::npos) {
                    size_t chunkStart = chunksPos;
                    while (true) {
                        chunkStart = fileJson.find("{", chunkStart + 1);
                        if (chunkStart == std::string::npos || chunkStart > fileEnd) break;
                        
                        size_t chunkEnd = fileJson.find("}", chunkStart);
                        if (chunkEnd == std::string::npos || chunkEnd > fileEnd) break;
                        
                        std::string chunkJson = fileJson.substr(chunkStart, chunkEnd - chunkStart + 1);
                        
                        ChunkInfo chunk;
                        chunk.fileId = fileInfo.fileId;
                        chunk.chunkNumber = extractJsonInt(chunkJson, "chunkNumber");
                        chunk.totalChunks = extractJsonInt(chunkJson, "totalChunks");
                        chunk.chunkSize = extractJsonInt(chunkJson, "chunkSize");
                        chunk.chunkHash = extractJsonString(chunkJson, "chunkHash");
                        chunk.telegramFileId = extractJsonString(chunkJson, "telegramFileId");
                        chunk.uploaderBotToken = extractJsonString(chunkJson, "uploaderBotToken");
                        
                        chunks.push_back(chunk);
                        
                        chunkStart = chunkEnd;
                        if (chunkStart + 1 >= fileJson.length() || fileJson[chunkStart + 1] != ',') break;
                    }
                }
                filesChunks.push_back(chunks);
                
                fileStart = fileEnd;
            }
        }
        
        return !filesInfo.empty();
        
    } catch (const std::exception& e) {
        LOG_ERROR("Failed to parse link data: " + std::string(e.what()));
        return false;
    }
}

bool UniversalLinkDownloader::downloadSingleFile(
    const FileInfo& fileInfo,
    const std::vector<ChunkInfo>& chunks,
    const std::string& destinationDir,
    const std::string& filePassword,
    UniversalLinkProgressCallback progressCallback,
    int currentIndex,
    int totalFiles) {
    
    try {
        std::string destPath = destinationDir + "/" + fileInfo.fileName;
        LOG_INFO("Downloading file " + std::to_string(currentIndex) + "/" + 
                std::to_string(totalFiles) + ": " + fileInfo.fileName);
        
        bool success = false;
        
        if (fileInfo.category == "chunked" && !chunks.empty()) {
            // Crear callback específico para chunks que actualice el progreso
            auto chunkProgressCallback = [&, progressCallback](int64_t completed, int64_t total) {
                if (progressCallback) {
                    double percent = (static_cast<double>(completed) / static_cast<double>(total)) * 100.0;
                    // Pasar completed y total como los parámetros de progreso (chunks, no archivos)
                    progressCallback(static_cast<int>(completed), static_cast<int>(total), fileInfo.fileName, percent);
                }
            };
            
            success = downloadChunkedFromLinkWithProgress(fileInfo, chunks, destPath, filePassword, chunkProgressCallback);
        } else {
            success = downloadDirectFromLink(fileInfo, destPath, filePassword);
            
            if (progressCallback) {
                progressCallback(1, 1, fileInfo.fileName, 100.0);
            }
        }
        
        return success;
        
    } catch (const std::exception& e) {
        LOG_ERROR("Failed to download single file: " + std::string(e.what()));
        return false;
    }
}

bool UniversalLinkDownloader::downloadChunkedFromLink(
    const FileInfo& fileInfo,
    const std::vector<ChunkInfo>& chunks,
    const std::string& destPath,
    const std::string& filePassword) {
    
    return downloadChunkedFromLinkWithProgress(fileInfo, chunks, destPath, filePassword, nullptr);
}

bool UniversalLinkDownloader::downloadChunkedFromLinkWithProgress(
    const FileInfo& fileInfo,
    const std::vector<ChunkInfo>& chunks,
    const std::string& destPath,
    const std::string& filePassword,
    std::function<void(int64_t completed, int64_t total)> progressCallback) {
    
    // Generar download ID fuera del try para poder usarlo en el catch
    std::string downloadId = generateDownloadId();
    
    try {
        LOG_INFO("Downloading chunked file: " + fileInfo.fileName + 
                " (" + std::to_string(chunks.size()) + " chunks)");
        
        // Crear directorio temporal
        std::string tempDir = "temp_link_download_" + fileInfo.fileId;
        std::filesystem::create_directories(tempDir);
        
        // Registrar descarga en base de datos
        if (m_database) {
            DownloadInfo downloadInfo;
            downloadInfo.downloadId = downloadId;
            downloadInfo.fileId = fileInfo.fileId;
            downloadInfo.fileName = fileInfo.fileName;
            downloadInfo.destPath = destPath;
            downloadInfo.totalSize = fileInfo.fileSize;
            downloadInfo.totalChunks = static_cast<int64_t>(chunks.size());
            downloadInfo.completedChunks = 0;
            downloadInfo.status = "downloading";
            downloadInfo.tempDir = tempDir;
            
            if (!m_database->registerDownload(downloadInfo)) {
                LOG_WARNING("Failed to register download in database");
            } else {
                LOG_INFO("Download registered in database with ID: " + downloadId);
            }
        }
        
        // Registrar operación en TelegramNotifier
        if (m_notifier) {
            m_notifier->registerOperation(downloadId, OperationType::DOWNLOAD,
                                         fileInfo.fileName, fileInfo.fileSize,
                                         static_cast<int64_t>(chunks.size()));
        }
        
        // Descargar chunks en paralelo controlado (5 simultáneos)
        std::vector<std::future<bool>> futures;
        std::atomic<int64_t> completedCount(0);
        const int MAX_PARALLEL = 5;
        int64_t totalChunks = static_cast<int64_t>(chunks.size());
        
        for (size_t i = 0; i < chunks.size(); ++i) {
            const auto& chunk = chunks[i];
            
            // Lanzar descarga asíncrona
            auto future = std::async(std::launch::async, [&, chunk, progressCallback, downloadId]() {
                std::string chunkPath = tempDir + "/chunk_" + 
                                      std::to_string(chunk.chunkNumber) + ".dat";
                
                // Usar uploaderBotToken del chunk si está disponible, sino usar el token por defecto
                std::string tokenToUse = chunk.uploaderBotToken.empty() ? "" : chunk.uploaderBotToken;
                
                // Reintentar hasta 3 veces
                bool success = false;
                for (int retry = 0; retry < 3 && !success; retry++) {
                    if (retry > 0) {
                        LOG_WARNING("Retrying chunk " + std::to_string(chunk.chunkNumber) + 
                                  " (attempt " + std::to_string(retry + 1) + "/3)");
                        std::this_thread::sleep_for(std::chrono::seconds(1));
                    }
                    success = m_telegramHandler->downloadFile(chunk.telegramFileId, chunkPath, tokenToUse);
                }
                
                if (success) {
                    int64_t completed = ++completedCount;
                    LOG_DEBUG("Downloaded chunk " + std::to_string(chunk.chunkNumber + 1) + 
                            "/" + std::to_string(chunks.size()));
                    
                    // Actualizar progreso en base de datos
                    if (m_database) {
                        m_database->updateDownloadChunkState(downloadId, chunk.chunkNumber, "completed");
                        m_database->updateDownloadProgress(downloadId, completed);
                    }
                    
                    // Notificar progreso
                    if (progressCallback) {
                        progressCallback(completed, totalChunks);
                    }
                    
                    // Actualizar progreso en TelegramNotifier
                    if (m_notifier) {
                        double progressPercent = (static_cast<double>(completed) / static_cast<double>(totalChunks)) * 100.0;
                        m_notifier->updateOperationProgress(downloadId, completed, progressPercent, "downloading");
                    }
                }
                
                return success;
            });
            
            futures.push_back(std::move(future));
            
            // Limitar descargas paralelas
            if (futures.size() >= MAX_PARALLEL) {
                // Esperar a que terminen todos los actuales
                for (auto& f : futures) {
                    if (!f.get()) {
                        LOG_ERROR("Chunk download failed");
                        std::filesystem::remove_all(tempDir);
                        return false;
                    }
                }
                futures.clear();
            }
        }
        
        // Esperar a que terminen los futuros restantes
        for (auto& future : futures) {
            if (!future.get()) {
                LOG_ERROR("Chunk download failed");
                std::filesystem::remove_all(tempDir);
                return false;
            }
        }
        
        LOG_INFO("All chunks downloaded, reconstructing file...");
        
        // Reconstruir archivo con reporte de progreso
        std::ofstream outFile(destPath, std::ios::binary);
        if (!outFile) {
            LOG_ERROR("Failed to create output file: " + destPath);
            std::filesystem::remove_all(tempDir);
            return false;
        }
        
        int64_t processedChunks = 0;
        int64_t totalChunksToReconstruct = static_cast<int64_t>(chunks.size());
        
        for (const auto& chunk : chunks) {
            std::string chunkPath = tempDir + "/chunk_" + 
                                  std::to_string(chunk.chunkNumber) + ".dat";
            
            std::ifstream chunkFile(chunkPath, std::ios::binary);
            if (!chunkFile) {
                LOG_ERROR("Failed to read chunk: " + chunkPath);
                outFile.close();
                std::filesystem::remove_all(tempDir);
                std::filesystem::remove(destPath);
                return false;
            }
            
            outFile << chunkFile.rdbuf();
            chunkFile.close();
            
            processedChunks++;
            
            // Reportar progreso de reconstrucción
            if (progressCallback) {
                progressCallback(-processedChunks, -totalChunksToReconstruct);
            }
            
            LOG_DEBUG("Reconstructed chunk " + std::to_string(processedChunks) + "/" + std::to_string(totalChunksToReconstruct));
        }
        
        outFile.close();
        
        // Limpiar chunks temporales
        std::filesystem::remove_all(tempDir);
        
        // Desencriptar si es necesario
        if (fileInfo.isEncrypted && !filePassword.empty()) {
            LOG_INFO("Decrypting file...");
            std::string tempEncrypted = destPath + ".encrypted";
            std::filesystem::rename(destPath, tempEncrypted);
            
            if (!decryptFile(tempEncrypted, destPath, filePassword)) {
                LOG_ERROR("Failed to decrypt file");
                std::filesystem::rename(tempEncrypted, destPath);
                
                // Marcar como fallida en la base de datos
                if (m_database) {
                    m_database->updateDownloadState(downloadId, "failed");
                }
                
                // Notificar fallo a TelegramNotifier
                if (m_notifier) {
                    m_notifier->notifyOperationFailed(downloadId, "Failed to decrypt file");
                }
                
                return false;
            }
            
            std::filesystem::remove(tempEncrypted);
            LOG_INFO("File decrypted successfully");
        }
        
        // Marcar descarga como completada en la base de datos
        if (m_database) {
            m_database->updateDownloadState(downloadId, "completed");
            LOG_INFO("Download marked as completed in database");
        }
        
        // Notificar completado a TelegramNotifier
        if (m_notifier) {
            m_notifier->notifyOperationCompleted(downloadId, destPath);
        }
        
        LOG_INFO("Chunked file downloaded successfully: " + destPath);
        return true;
        
    } catch (const std::exception& e) {
        LOG_ERROR("Failed to download chunked file: " + std::string(e.what()));
        
        // Notificar fallo a TelegramNotifier
        if (m_notifier) {
            m_notifier->notifyOperationFailed(downloadId, std::string(e.what()));
        }
        
        return false;
    }
}

bool UniversalLinkDownloader::downloadDirectFromLink(
    const FileInfo& fileInfo,
    const std::string& destPath,
    const std::string& filePassword) {
    
    try {
        LOG_INFO("Downloading direct file: " + fileInfo.fileName);
        
        // Usar uploaderBotToken del archivo si está disponible
        std::string tokenToUse = fileInfo.uploaderBotToken.empty() ? "" : fileInfo.uploaderBotToken;
        bool success = m_telegramHandler->downloadFile(fileInfo.telegramFileId, destPath, tokenToUse);
        
        if (!success) {
            LOG_ERROR("Failed to download file from Telegram");
            return false;
        }
        
        // Desencriptar si es necesario
        if (fileInfo.isEncrypted && !filePassword.empty()) {
            LOG_INFO("Decrypting file...");
            std::string tempEncrypted = destPath + ".encrypted";
            std::filesystem::rename(destPath, tempEncrypted);
            
            if (!decryptFile(tempEncrypted, destPath, filePassword)) {
                LOG_ERROR("Failed to decrypt file");
                std::filesystem::rename(tempEncrypted, destPath);
                return false;
            }
            
            std::filesystem::remove(tempEncrypted);
            LOG_INFO("File decrypted successfully");
        }
        
        LOG_INFO("Direct file downloaded successfully: " + destPath);
        return true;
        
    } catch (const std::exception& e) {
        LOG_ERROR("Failed to download direct file: " + std::string(e.what()));
        return false;
    }
}

bool UniversalLinkDownloader::decryptFile(
    const std::string& inputPath,
    const std::string& outputPath,
    const std::string& password) {
    
    try {
        // Leer archivo encriptado
        std::ifstream inFile(inputPath, std::ios::binary);
        if (!inFile) return false;
        
        std::string encrypted((std::istreambuf_iterator<char>(inFile)),
                             std::istreambuf_iterator<char>());
        inFile.close();
        
        // Desencriptar
        std::string decrypted = decryptData(encrypted, password);
        
        // Escribir archivo desencriptado
        std::ofstream outFile(outputPath, std::ios::binary);
        if (!outFile) return false;
        
        outFile.write(decrypted.c_str(), decrypted.size());
        outFile.close();
        
        return true;
        
    } catch (const std::exception& e) {
        LOG_ERROR("File decryption failed: " + std::string(e.what()));
        return false;
    }
}

std::string UniversalLinkDownloader::decryptData(
    const std::string& encrypted,
    const std::string& password) {
    
    if (encrypted.length() < 32) {
        throw std::runtime_error("Invalid link file format (file too short)");
    }
    
    // Extraer salt, IV y datos encriptados
    std::string salt = encrypted.substr(0, 16);
    std::string iv = encrypted.substr(16, 16);
    std::string encrypted_data = encrypted.substr(32);
    
    if (encrypted_data.empty()) {
        throw std::runtime_error("Invalid link file format (no encrypted data)");
    }
    
    // Derivar clave
    std::string key = deriveKey(password, salt);
    
    // Inicializar contexto de desencriptación
    EVP_CIPHER_CTX* ctx = EVP_CIPHER_CTX_new();
    if (!ctx) {
        throw std::runtime_error("Failed to create decryption context");
    }
    
    // Inicializar desencriptación AES-256-CBC
    if (EVP_DecryptInit_ex(ctx, EVP_aes_256_cbc(), NULL,
                          reinterpret_cast<const unsigned char*>(key.c_str()),
                          reinterpret_cast<const unsigned char*>(iv.c_str())) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        throw std::runtime_error("Failed to initialize decryption");
    }
    
    // Preparar buffer de salida
    int len;
    int plaintext_len;
    std::string plaintext(encrypted_data.length() + 16, 0);
    
    // Desencriptar datos
    if (EVP_DecryptUpdate(ctx, reinterpret_cast<unsigned char*>(&plaintext[0]), &len,
                         reinterpret_cast<const unsigned char*>(encrypted_data.c_str()),
                         encrypted_data.length()) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        throw std::runtime_error("Decryption failed (corrupted data)");
    }
    plaintext_len = len;
    
    // Finalizar desencriptación
    if (EVP_DecryptFinal_ex(ctx, reinterpret_cast<unsigned char*>(&plaintext[plaintext_len]), &len) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        throw std::runtime_error("Wrong password or corrupted link file");
    }
    plaintext_len += len;
    plaintext.resize(plaintext_len);
    
    EVP_CIPHER_CTX_free(ctx);
    
    return plaintext;
}

std::string UniversalLinkDownloader::deriveKey(const std::string& password, const std::string& salt) {
    unsigned char key[32]; // 256 bits
    if (PKCS5_PBKDF2_HMAC(password.c_str(), password.length(),
                         reinterpret_cast<const unsigned char*>(salt.c_str()), salt.length(),
                         10000, EVP_sha256(), 32, key) != 1) {
        throw std::runtime_error("Failed to derive key");
    }
    return std::string(reinterpret_cast<char*>(key), 32);
}

std::string UniversalLinkDownloader::generateDownloadId() {
    // Generar UUID simple
    std::random_device rd;
    std::mt19937_64 gen(rd());
    std::uniform_int_distribution<uint64_t> dist;
    
    uint64_t part1 = dist(gen);
    uint64_t part2 = dist(gen);
    
    std::ostringstream oss;
    oss << "link_" << std::hex << part1 << "_" << part2;
    return oss.str();
}

} // namespace TelegramCloud

