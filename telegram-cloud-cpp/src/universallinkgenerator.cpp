#include "universallinkgenerator.h"
#include "logger.h"
#include <fstream>
#include <sstream>
#include <iomanip>
#include <openssl/evp.h>
#include <openssl/rand.h>
#include <openssl/sha.h>

namespace TelegramCloud {

// Funciones auxiliares para base64
static std::string base64Encode(const std::string& data) {
    const std::string chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    std::string result;
    int val = 0, valb = -6;
    for (unsigned char c : data) {
        val = (val << 8) + c;
        valb += 8;
        while (valb >= 0) {
            result.push_back(chars[(val >> valb) & 0x3F]);
            valb -= 6;
        }
    }
    if (valb > -6) result.push_back(chars[((val << 8) >> (valb + 8)) & 0x3F]);
    while (result.size() % 4) result.push_back('=');
    return result;
}

// Función auxiliar para escapar JSON
static std::string jsonEscape(const std::string& str) {
    std::string result;
    for (char c : str) {
        switch (c) {
            case '\"': result += "\\\""; break;
            case '\\': result += "\\\\"; break;
            case '\b': result += "\\b"; break;
            case '\f': result += "\\f"; break;
            case '\n': result += "\\n"; break;
            case '\r': result += "\\r"; break;
            case '\t': result += "\\t"; break;
            default:
                if (c >= 0 && c < 32) {
                    result += "\\u";
                    result += std::to_string((int)c);
                } else {
                    result += c;
                }
        }
    }
    return result;
}

UniversalLinkGenerator::UniversalLinkGenerator(Database* database)
    : m_database(database) {
}

bool UniversalLinkGenerator::generateLinkFile(
    const std::string& fileId,
    const std::string& password,
    const std::string& outputPath) {
    
    try {
        LOG_INFO("Generating universal link file for: " + fileId);
        
        // Obtener información del archivo
        FileInfo fileInfo = m_database->getFileInfo(fileId);
        if (fileInfo.fileId.empty()) {
            LOG_ERROR("File not found in database: " + fileId);
            return false;
        }
        
        // Obtener chunks si existen
        std::vector<ChunkInfo> chunks = m_database->getFileChunks(fileId);
        
        // Serializar datos
        std::string jsonData = serializeFileData(fileInfo, chunks);
        
        // Encriptar datos
        std::string encrypted = encryptData(jsonData, password);
        
        // Escribir archivo .link
        std::ofstream outFile(outputPath, std::ios::binary);
        if (!outFile) {
            LOG_ERROR("Failed to create link file: " + outputPath);
            return false;
        }
        
        outFile.write(encrypted.c_str(), encrypted.size());
        outFile.close();
        
        LOG_INFO("Universal link file created: " + outputPath);
        return true;
        
    } catch (const std::exception& e) {
        LOG_ERROR("Failed to generate link file: " + std::string(e.what()));
        return false;
    }
}

bool UniversalLinkGenerator::generateBatchLinkFile(
    const std::vector<std::string>& fileIds,
    const std::string& password,
    const std::string& outputPath) {
    
    try {
        LOG_INFO("Generating batch link file for " + std::to_string(fileIds.size()) + " files");
        
        // Recopilar información de todos los archivos
        std::vector<std::pair<FileInfo, std::vector<ChunkInfo>>> filesData;
        
        for (const auto& fileId : fileIds) {
            FileInfo fileInfo = m_database->getFileInfo(fileId);
            if (fileInfo.fileId.empty()) {
                LOG_WARNING("Skipping file not found: " + fileId);
                continue;
            }
            
            std::vector<ChunkInfo> chunks = m_database->getFileChunks(fileId);
            filesData.push_back({fileInfo, chunks});
        }
        
        if (filesData.empty()) {
            LOG_ERROR("No valid files found for batch link");
            return false;
        }
        
        // Serializar datos
        std::string jsonData = serializeBatchData(filesData);
        
        // Encriptar datos
        std::string encrypted = encryptData(jsonData, password);
        
        // Escribir archivo .link
        std::ofstream outFile(outputPath, std::ios::binary);
        if (!outFile) {
            LOG_ERROR("Failed to create batch link file: " + outputPath);
            return false;
        }
        
        outFile.write(encrypted.c_str(), encrypted.size());
        outFile.close();
        
        LOG_INFO("Batch link file created: " + outputPath);
        return true;
        
    } catch (const std::exception& e) {
        LOG_ERROR("Failed to generate batch link file: " + std::string(e.what()));
        return false;
    }
}

std::string UniversalLinkGenerator::serializeFileData(
    const FileInfo& fileInfo,
    const std::vector<ChunkInfo>& chunks) {
    
    std::ostringstream json;
    json << "{";
    json << "\"version\":\"1.0\",";
    json << "\"type\":\"single\",";
    json << "\"file\":{";
    json << "\"fileId\":\"" << jsonEscape(fileInfo.fileId) << "\",";
    json << "\"fileName\":\"" << jsonEscape(fileInfo.fileName) << "\",";
    json << "\"fileSize\":" << fileInfo.fileSize << ",";
    json << "\"mimeType\":\"" << jsonEscape(fileInfo.mimeType) << "\",";
    json << "\"category\":\"" << jsonEscape(fileInfo.category) << "\",";
    json << "\"uploadDate\":\"" << jsonEscape(fileInfo.uploadDate) << "\",";
    json << "\"telegramFileId\":\"" << jsonEscape(fileInfo.telegramFileId) << "\",";
    json << "\"uploaderBotToken\":\"" << jsonEscape(fileInfo.uploaderBotToken) << "\",";
    json << "\"isEncrypted\":" << (fileInfo.isEncrypted ? "true" : "false");
    
    if (!chunks.empty()) {
        json << ",\"chunks\":[";
        for (size_t i = 0; i < chunks.size(); i++) {
            if (i > 0) json << ",";
            json << "{";
            json << "\"chunkNumber\":" << chunks[i].chunkNumber << ",";
            json << "\"totalChunks\":" << chunks[i].totalChunks << ",";
            json << "\"chunkSize\":" << chunks[i].chunkSize << ",";
            json << "\"chunkHash\":\"" << jsonEscape(chunks[i].chunkHash) << "\",";
            json << "\"telegramFileId\":\"" << jsonEscape(chunks[i].telegramFileId) << "\",";
            json << "\"uploaderBotToken\":\"" << jsonEscape(chunks[i].uploaderBotToken) << "\"";
            json << "}";
        }
        json << "]";
    }
    
    json << "}";
    json << "}";
    
    return json.str();
}

std::string UniversalLinkGenerator::serializeBatchData(
    const std::vector<std::pair<FileInfo, std::vector<ChunkInfo>>>& filesData) {
    
    std::ostringstream json;
    json << "{";
    json << "\"version\":\"1.0\",";
    json << "\"type\":\"batch\",";
    json << "\"files\":[";
    
    for (size_t f = 0; f < filesData.size(); f++) {
        if (f > 0) json << ",";
        
        const FileInfo& fileInfo = filesData[f].first;
        const std::vector<ChunkInfo>& chunks = filesData[f].second;
        
        json << "{";
        json << "\"fileId\":\"" << jsonEscape(fileInfo.fileId) << "\",";
        json << "\"fileName\":\"" << jsonEscape(fileInfo.fileName) << "\",";
        json << "\"fileSize\":" << fileInfo.fileSize << ",";
        json << "\"mimeType\":\"" << jsonEscape(fileInfo.mimeType) << "\",";
        json << "\"category\":\"" << jsonEscape(fileInfo.category) << "\",";
        json << "\"uploadDate\":\"" << jsonEscape(fileInfo.uploadDate) << "\",";
        json << "\"telegramFileId\":\"" << jsonEscape(fileInfo.telegramFileId) << "\",";
        json << "\"uploaderBotToken\":\"" << jsonEscape(fileInfo.uploaderBotToken) << "\",";
        json << "\"isEncrypted\":" << (fileInfo.isEncrypted ? "true" : "false");
        
        if (!chunks.empty()) {
            json << ",\"chunks\":[";
            for (size_t i = 0; i < chunks.size(); i++) {
                if (i > 0) json << ",";
                json << "{";
                json << "\"chunkNumber\":" << chunks[i].chunkNumber << ",";
                json << "\"totalChunks\":" << chunks[i].totalChunks << ",";
                json << "\"chunkSize\":" << chunks[i].chunkSize << ",";
                json << "\"chunkHash\":\"" << jsonEscape(chunks[i].chunkHash) << "\",";
                json << "\"telegramFileId\":\"" << jsonEscape(chunks[i].telegramFileId) << "\",";
                json << "\"uploaderBotToken\":\"" << jsonEscape(chunks[i].uploaderBotToken) << "\"";
                json << "}";
            }
            json << "]";
        }
        
        json << "}";
    }
    
    json << "]";
    json << "}";
    
    return json.str();
}

std::string UniversalLinkGenerator::encryptData(const std::string& data, const std::string& password) {
    // Generar salt aleatorio
    std::string salt = generateSalt();
    
    // Derivar clave de 256 bits
    std::string key = deriveKey(password, salt);
    
    // Inicializar contexto de encriptación
    EVP_CIPHER_CTX* ctx = EVP_CIPHER_CTX_new();
    if (!ctx) {
        throw std::runtime_error("Failed to create encryption context");
    }
    
    // Generar IV aleatorio
    unsigned char iv[16]; // AES block size
    if (RAND_bytes(iv, 16) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        throw std::runtime_error("Failed to generate IV");
    }
    
    // Inicializar encriptación AES-256-CBC
    if (EVP_EncryptInit_ex(ctx, EVP_aes_256_cbc(), NULL,
                          reinterpret_cast<const unsigned char*>(key.c_str()), iv) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        throw std::runtime_error("Failed to initialize encryption");
    }
    
    // Preparar buffer de salida
    int len;
    int ciphertext_len;
    std::string ciphertext(data.length() + 16, 0);
    
    // Encriptar datos
    if (EVP_EncryptUpdate(ctx, reinterpret_cast<unsigned char*>(&ciphertext[0]), &len,
                         reinterpret_cast<const unsigned char*>(data.c_str()), data.length()) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        throw std::runtime_error("Failed to encrypt data");
    }
    ciphertext_len = len;
    
    // Finalizar encriptación
    if (EVP_EncryptFinal_ex(ctx, reinterpret_cast<unsigned char*>(&ciphertext[ciphertext_len]), &len) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        throw std::runtime_error("Failed to finalize encryption");
    }
    ciphertext_len += len;
    ciphertext.resize(ciphertext_len);
    
    EVP_CIPHER_CTX_free(ctx);
    
    // Combinar: salt (16) + iv (16) + datos encriptados
    std::string result = salt + std::string(reinterpret_cast<char*>(iv), 16) + ciphertext;
    return result;
}

std::string UniversalLinkGenerator::generateSalt() {
    unsigned char salt[16];
    if (RAND_bytes(salt, 16) != 1) {
        throw std::runtime_error("Failed to generate salt");
    }
    return std::string(reinterpret_cast<char*>(salt), 16);
}

std::string UniversalLinkGenerator::deriveKey(const std::string& password, const std::string& salt) {
    unsigned char key[32]; // 256 bits
    if (PKCS5_PBKDF2_HMAC(password.c_str(), password.length(),
                         reinterpret_cast<const unsigned char*>(salt.c_str()), salt.length(),
                         10000, EVP_sha256(), 32, key) != 1) {
        throw std::runtime_error("Failed to derive key");
    }
    return std::string(reinterpret_cast<char*>(key), 32);
}

} // namespace TelegramCloud


