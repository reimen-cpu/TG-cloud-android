#include "chunkedupload.h"
#include "telegramhandler.h"
#include "database.h"
#include "logger.h"
#include "config.h"
#include "telegramnotifier.h"

// Inicializar miembros estáticos
namespace TelegramCloud {
    std::map<std::string, std::atomic<bool>> ChunkedUpload::s_pausedUploads;
    std::map<std::string, std::atomic<bool>> ChunkedUpload::s_canceledUploads;
    std::mutex ChunkedUpload::s_controlMutex;
}
#include <fstream>
#include <thread>
#include <future>
#include <algorithm>
#include <sstream>
#include <iomanip>
#include <random>

namespace TelegramCloud {

// ============================================================================
// ChunkedUpload Implementation
// ============================================================================

ChunkedUpload::ChunkedUpload(Database* database, TelegramHandler* telegramHandler, TelegramNotifier* notifier)
    : m_database(database)
    , m_telegramHandler(telegramHandler)
    , m_notifier(notifier)
    , m_fileSize(0)
    , m_isActive(false)
    , m_isCanceled(false)
    , m_isPaused(false)
    , m_currentChunkIndex(0)
    , m_completedChunks(0)
    , m_totalChunks(0)
{
}

ChunkedUpload::~ChunkedUpload() {
    cleanup();
}

std::string ChunkedUpload::startUpload(const std::string& filePath) {
    m_filePath = filePath;
    m_uploadId = generateUUID();
    
    LOG_INFO("Starting chunked upload for: " + filePath);
    
    // Verificar archivo
    std::ifstream file(filePath, std::ios::binary | std::ios::ate);
    if (!file.is_open()) {
        LOG_ERROR("Failed to open file: " + filePath);
        return "";
    }
    
    m_fileSize = file.tellg();
    file.close();
    
    Config& config = Config::instance();
    
    // Verificar si necesita chunking
    if (m_fileSize <= config.chunkThreshold()) {
        LOG_INFO("File size below threshold, no chunking needed");
        return "";
    }
    
    // Extraer nombre de archivo
    size_t lastSlash = filePath.find_last_of("/\\");
    m_fileName = (lastSlash != std::string::npos) ? filePath.substr(lastSlash + 1) : filePath;
    
    // Calcular número de chunks
    int64_t chunkSize = config.chunkSize();
    m_totalChunks = (m_fileSize + chunkSize - 1) / chunkSize;
    
    LOG_INFO("File size: " + std::to_string(m_fileSize) + " bytes (" + 
             std::to_string(m_fileSize / 1024.0 / 1024.0) + " MB)");
    LOG_INFO("Total chunks: " + std::to_string(m_totalChunks));
    LOG_INFO("Chunk size: " + std::to_string(chunkSize) + " bytes (" + 
             std::to_string(chunkSize / 1024.0 / 1024.0) + " MB)");
    LOG_INFO("Bot pool size: " + std::to_string(m_telegramHandler->getBotPoolSize()));
    
    // Calcular hash del archivo
    m_fileHash = calculateFileHash(filePath);
    if (m_fileHash.empty()) {
        LOG_ERROR("Failed to calculate file hash");
        return "";
    }
    
    // Registrar en chunked_files ANTES de subir chunks (para FK)
    if (m_database) {
        ChunkedFileInfo chunkedFileInfo;
        chunkedFileInfo.fileId = m_uploadId;
        chunkedFileInfo.originalFilename = m_fileName;
        chunkedFileInfo.mimeType = detectMimeType(m_fileName);
        chunkedFileInfo.totalSize = m_fileSize;
        chunkedFileInfo.totalChunks = m_totalChunks;
        chunkedFileInfo.completedChunks = 0;
        chunkedFileInfo.status = "uploading";
        chunkedFileInfo.originalFileHash = m_fileHash;
        
        if (!m_database->registerChunkedFile(chunkedFileInfo)) {
            LOG_ERROR("Failed to register chunked file in database");
            return "";
        }
        
        LOG_INFO("Chunked file registered in database, proceeding with chunk upload");
    }
    
    // Limpiar cualquier estado compartido previo
    {
        std::lock_guard<std::mutex> controlLock(s_controlMutex);
        s_pausedUploads.erase(m_uploadId);
        s_canceledUploads.erase(m_uploadId);
    }
    
    m_isActive = true;
    m_isCanceled = false;
    m_isPaused = false;
    m_completedChunks = 0;
    
    // Actualizar estado en BD
    if (m_database) {
        m_database->updateUploadState(m_uploadId, "uploading");
    }
    
    // Registrar operación en notificador
    if (m_notifier) {
        m_notifier->registerOperation(m_uploadId, OperationType::UPLOAD, 
                                     m_fileName, m_fileSize, m_totalChunks);
    }
    
    // Subir chunks en paralelo
    uploadChunksParallel();
    
    // Verificar resultado y notificar
    if (!m_isPaused && !m_isCanceled) {
        if (m_completedChunks == m_totalChunks) {
            LOG_INFO("Upload completed successfully: " + m_uploadId);
            if (m_notifier) {
                m_notifier->notifyOperationCompleted(m_uploadId);
            }
        } else {
            LOG_ERROR("Upload incomplete: " + std::to_string(m_completedChunks) + "/" + std::to_string(m_totalChunks));
            if (m_notifier) {
                m_notifier->notifyOperationFailed(m_uploadId, "Upload incomplete");
            }
        }
    }
    
    m_isActive = false;
    
    return m_uploadId;
}

std::string ChunkedUpload::resumeUpload(const std::string& uploadId, const std::string& filePath) {
    std::lock_guard<std::mutex> lock(m_stateMutex);
    
    LOG_INFO("Resuming upload: " + uploadId + " from file: " + filePath);
    
    if (!loadUploadState(uploadId)) {
        LOG_ERROR("Failed to load upload state for: " + uploadId);
        return "";
    }
    
    // Asignar filePath
    m_filePath = filePath;
    
    // Validar chunks existentes
    std::set<int64_t> validChunks;
    if (!validateExistingChunks(filePath, validChunks)) {
        LOG_ERROR("Failed to validate existing chunks");
        return "";
    }
    
    LOG_INFO("Found " + std::to_string(validChunks.size()) + " valid chunks, resuming upload");
    
    // CRÍTICO: Limpiar estado compartido para permitir reanudación
    {
        std::lock_guard<std::mutex> controlLock(s_controlMutex);
        s_pausedUploads.erase(uploadId);
        s_canceledUploads.erase(uploadId);
    }
    
    m_isActive = true;
    m_isCanceled = false;
    m_isPaused = false;
    
    // Actualizar estado en BD
    if (m_database) {
        m_database->updateUploadState(m_uploadId, "uploading");
    }
    
    // Registrar operación en notificador
    if (m_notifier) {
        m_notifier->registerOperation(m_uploadId, OperationType::UPLOAD, 
                                     m_fileName, m_fileSize, m_totalChunks);
    }
    
    // Continuar subida, omitiendo chunks válidos
    uploadChunksParallel(validChunks);
    
    // Verificar resultado y notificar
    if (!m_isPaused && !m_isCanceled) {
        if (m_completedChunks == m_totalChunks) {
            LOG_INFO("Upload completed successfully: " + m_uploadId);
            if (m_notifier) {
                m_notifier->notifyOperationCompleted(m_uploadId);
            }
        } else {
            LOG_ERROR("Upload incomplete: " + std::to_string(m_completedChunks) + "/" + std::to_string(m_totalChunks));
            if (m_notifier) {
                m_notifier->notifyOperationFailed(m_uploadId, "Upload incomplete");
            }
        }
    }
    
    m_isActive = false;
    
    return m_uploadId;
}

bool ChunkedUpload::pauseUpload(const std::string& uploadId) {
    LOG_INFO("Pausing upload: " + uploadId);
    
    // Marcar en el mapa compartido para que TODAS las instancias lo vean
    {
        std::lock_guard<std::mutex> lock(s_controlMutex);
        s_pausedUploads[uploadId] = true;
    }
    
    // Si es la misma instancia, marcar local también
    if (m_uploadId == uploadId) {
        m_isPaused = true;
    }
    
    // Actualizar estado en base de datos
    if (m_database) {
        m_database->updateUploadState(uploadId, "paused");
    }
    
    return true;
}

bool ChunkedUpload::stopUpload(const std::string& uploadId) {
    LOG_INFO("Stopping upload: " + uploadId);
    
    // Marcar en el mapa compartido
    {
        std::lock_guard<std::mutex> lock(s_controlMutex);
        s_pausedUploads[uploadId] = true;  // Stop = Pause efectivamente
    }
    
    // Si es la misma instancia, detener
    if (m_uploadId == uploadId) {
        m_isActive = false;
        m_isPaused = true;
    }
    
    // Actualizar estado en base de datos
    if (m_database) {
        m_database->updateUploadState(uploadId, "stopped");
    }
    
    return true;
}

bool ChunkedUpload::cancelUpload(const std::string& uploadId) {
    LOG_INFO("Canceling upload: " + uploadId);
    
    // Marcar en el mapa compartido
    {
        std::lock_guard<std::mutex> lock(s_controlMutex);
        s_canceledUploads[uploadId] = true;
    }
    
    // Si es la misma instancia, cancelar
    if (m_uploadId == uploadId) {
        m_isCanceled = true;
        m_isActive = false;
        m_isPaused = false;
    }
    
    // Eliminar progreso de base de datos
    if (m_database) {
        m_database->deleteUploadProgress(uploadId);
    }
    
    cleanup();
    return true;
}

void ChunkedUpload::uploadChunksParallel(const std::set<int64_t>& skipChunks) {
    LOG_INFO("Starting parallel chunk upload with " + 
             std::to_string(m_telegramHandler->getBotPoolSize()) + " bots");
    
    if (!skipChunks.empty()) {
        LOG_INFO("Skipping " + std::to_string(skipChunks.size()) + " already completed chunks");
    }
    
    Config& config = Config::instance();
    int64_t chunkSize = config.chunkSize();
    std::vector<std::string> botTokens = m_telegramHandler->getAllTokens();
    
    // Abrir archivo
    std::ifstream file(m_filePath, std::ios::binary);
    if (!file.is_open()) {
        LOG_ERROR("Failed to open file for chunking");
        return;
    }
    
    // Vector de futures para paralelización
    std::vector<std::future<bool>> futures;
    
    for (int64_t chunkIndex = 0; chunkIndex < m_totalChunks; ++chunkIndex) {
        if (m_isCanceled) {
            LOG_WARNING("Upload canceled, stopping chunk upload");
            break;
        }
        
        if (m_isPaused) {
            LOG_INFO("Upload paused, stopping chunk upload");
            break;
        }
        
        // Omitir chunks ya completados
        if (skipChunks.find(chunkIndex) != skipChunks.end()) {
            LOG_DEBUG("Skipping already completed chunk: " + std::to_string(chunkIndex));
            continue;
        }
        
        // Leer chunk
        file.seekg(chunkIndex * chunkSize);
        std::vector<char> chunkData(chunkSize);
        file.read(chunkData.data(), chunkSize);
        std::streamsize bytesRead = file.gcount();
        chunkData.resize(bytesRead);
        
        // Calcular hash del chunk
        std::string chunkHash = calculateChunkHash(chunkData);
        
        // Asignar bot token (round-robin)
        std::string botToken = botTokens[static_cast<size_t>(chunkIndex % botTokens.size())];
        
        LOG_DEBUG("Chunk " + std::to_string(chunkIndex + 1) + "/" + 
                 std::to_string(m_totalChunks) + " - Size: " + 
                 std::to_string(bytesRead) + " bytes, Bot: " + 
                 std::to_string(chunkIndex % botTokens.size()));
        
        // Upload chunk en thread separado
        auto future = std::async(std::launch::async, 
            [this, chunkIndex, chunkData, chunkHash, botToken, bytesRead]() {
                return uploadSingleChunk(chunkIndex, chunkData, chunkHash, botToken);
            }
        );
        
        futures.push_back(std::move(future));
        
        // Limitar número de threads simultáneos
        if (futures.size() >= botTokens.size()) {
            // Esperar a que termine al menos uno
            for (auto& f : futures) {
                if (f.valid()) {
                    f.wait();
                }
            }
            futures.clear();
            
            // Verificar si se pausó/canceló DESPUÉS de esperar este batch
            if (m_isCanceled) {
                LOG_INFO("Upload canceled after batch completion, stopping");
                break;
            }
            
            if (m_isPaused) {
                LOG_INFO("Upload paused after batch completion, stopping");
                break;
            }
        }
    }
    
    file.close();
    
    // Esperar a que terminen todos los chunks restantes
    for (auto& f : futures) {
        if (f.valid()) {
            f.wait();
        }
    }
    
    LOG_INFO("All chunks upload completed. Completed: " + 
             std::to_string(m_completedChunks) + "/" + std::to_string(m_totalChunks));
    
    if (m_completedChunks == m_totalChunks) {
        LOG_INFO("Upload successful!");
        
        // Finalizar archivo: actualizar status y crear entrada en tabla 'files'
        if (m_database) {
            if (m_database->finalizeChunkedFile(m_uploadId, m_uploadId)) {
                LOG_INFO("Chunked file finalized in database: " + m_uploadId);
            } else {
                LOG_WARNING("Failed to finalize chunked file in database");
            }
        }
    } else {
        LOG_ERROR("Upload incomplete: " + std::to_string(m_completedChunks) + 
                 "/" + std::to_string(m_totalChunks) + " chunks uploaded");
    }
}

bool ChunkedUpload::uploadSingleChunk(int64_t chunkIndex, const std::vector<char>& chunkData,
                                      const std::string& chunkHash, const std::string& botToken) {
    
    // Verificar estado compartido PRIMERO
    {
        std::lock_guard<std::mutex> lock(s_controlMutex);
        if (s_canceledUploads.count(m_uploadId) && s_canceledUploads[m_uploadId]) {
            LOG_INFO("Upload canceled (shared state), skipping chunk " + std::to_string(chunkIndex + 1));
            m_isCanceled = true;
            return false;
        }
        
        if (s_pausedUploads.count(m_uploadId) && s_pausedUploads[m_uploadId]) {
            LOG_INFO("Upload paused (shared state), skipping chunk " + std::to_string(chunkIndex + 1));
            m_isPaused = true;
            return false;
        }
    }
    
    // Verificar estado local también
    if (m_isCanceled) {
        LOG_INFO("Upload canceled, skipping chunk " + std::to_string(chunkIndex + 1));
        return false;
    }
    
    if (m_isPaused) {
        LOG_INFO("Upload paused, skipping chunk " + std::to_string(chunkIndex + 1));
        return false;
    }
    
    std::string chunkFileName = m_fileName + ".part" + 
                                std::to_string(chunkIndex + 1) + "of" + 
                                std::to_string(m_totalChunks);
    
    std::string caption = "Chunk " + std::to_string(chunkIndex + 1) + "/" + 
                         std::to_string(m_totalChunks) + " - " + m_fileName;
    
    LOG_DEBUG("Uploading chunk " + std::to_string(chunkIndex + 1) + ": " + chunkFileName);
    
    // Crear archivo temporal para el chunk
    std::string tempPath = "temp_chunk_" + std::to_string(chunkIndex) + ".tmp";
    std::ofstream tempFile(tempPath, std::ios::binary);
    if (!tempFile.is_open()) {
        LOG_ERROR("Failed to create temp file for chunk " + std::to_string(chunkIndex));
        return false;
    }
    
    tempFile.write(chunkData.data(), chunkData.size());
    tempFile.close();
    
    // Upload usando TelegramHandler con bot específico
    UploadResult result = m_telegramHandler->uploadDocumentWithToken(tempPath, botToken, caption);
    
    // Eliminar archivo temporal
    std::remove(tempPath.c_str());
    
    if (result.success) {
        m_completedChunks++;
        
        LOG_INFO("Chunk " + std::to_string(chunkIndex + 1) + "/" + 
                std::to_string(m_totalChunks) + " uploaded successfully. " +
                "File ID: " + result.fileId + ", Message ID: " + 
                std::to_string(result.messageId));
        
        // Guardar chunk en base de datos
        if (m_database) {
            ChunkInfo chunkInfo;
            chunkInfo.fileId = m_uploadId;
            chunkInfo.chunkNumber = chunkIndex;
            chunkInfo.totalChunks = m_totalChunks;
            chunkInfo.chunkSize = chunkData.size();
            chunkInfo.chunkHash = chunkHash;
            chunkInfo.telegramFileId = result.fileId;
            chunkInfo.messageId = result.messageId;
            chunkInfo.status = "completed";
            chunkInfo.uploaderBotToken = botToken;
            
            m_database->saveChunkInfo(chunkInfo);
            m_database->updateUploadProgress(m_uploadId, m_completedChunks);
        }
        
        // Notificar progreso
        if (m_progressCallback) {
            double percent = progress();
            m_progressCallback(m_completedChunks, m_totalChunks, percent);
        }
        
        // Actualizar progreso en TelegramNotifier
        if (m_notifier) {
            double percent = progress();
            m_notifier->updateOperationProgress(m_uploadId, m_completedChunks, percent, "uploading");
        }
        
        return true;
    } else {
        LOG_ERROR("Chunk " + std::to_string(chunkIndex + 1) + " upload failed: " + 
                 result.errorMessage);
        return false;
    }
}

std::string ChunkedUpload::calculateFileHash(const std::string& filePath) {
    LOG_DEBUG("Calculating SHA-256 hash for file...");
    
    std::ifstream file(filePath, std::ios::binary);
    if (!file.is_open()) {
        return "";
    }
    
    // Usar librería de hash SHA256 simple
    // Por ahora retornamos un hash placeholder
    // TODO: Implementar SHA256 real (o usar OpenSSL)
    
    file.close();
    return "placeholder_hash_" + std::to_string(std::hash<std::string>{}(filePath));
}

std::string ChunkedUpload::detectMimeType(const std::string& fileName) {
    // Encontrar la extensión
    size_t dotPos = fileName.find_last_of('.');
    if (dotPos == std::string::npos) {
        return "application/octet-stream";
    }
    
    std::string ext = fileName.substr(dotPos + 1);
    std::transform(ext.begin(), ext.end(), ext.begin(), ::tolower);
    
    // Mapeo de extensiones comunes a MIME types
    if (ext == "pdf") return "application/pdf";
    if (ext == "txt") return "text/plain";
    if (ext == "doc") return "application/msword";
    if (ext == "docx") return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    if (ext == "xls") return "application/vnd.ms-excel";
    if (ext == "xlsx") return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    if (ext == "ppt") return "application/vnd.ms-powerpoint";
    if (ext == "pptx") return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
    if (ext == "jpg" || ext == "jpeg") return "image/jpeg";
    if (ext == "png") return "image/png";
    if (ext == "gif") return "image/gif";
    if (ext == "bmp") return "image/bmp";
    if (ext == "svg") return "image/svg+xml";
    if (ext == "mp4") return "video/mp4";
    if (ext == "avi") return "video/x-msvideo";
    if (ext == "mov") return "video/quicktime";
    if (ext == "wmv") return "video/x-ms-wmv";
    if (ext == "mp3") return "audio/mpeg";
    if (ext == "wav") return "audio/wav";
    if (ext == "flac") return "audio/flac";
    if (ext == "zip") return "application/zip";
    if (ext == "rar") return "application/vnd.rar";
    if (ext == "7z") return "application/x-7z-compressed";
    if (ext == "tar") return "application/x-tar";
    if (ext == "gz") return "application/gzip";
    if (ext == "exe") return "application/x-msdownload";
    if (ext == "msi") return "application/x-msdownload";
    if (ext == "dll") return "application/x-msdownload";
    if (ext == "pyd") return "application/x-python-code";
    if (ext == "py") return "text/x-python";
    if (ext == "cpp" || ext == "c") return "text/x-c++";
    if (ext == "h" || ext == "hpp") return "text/x-c++";
    if (ext == "js") return "application/javascript";
    if (ext == "html" || ext == "htm") return "text/html";
    if (ext == "css") return "text/css";
    if (ext == "xml") return "application/xml";
    if (ext == "json") return "application/json";
    if (ext == "csv") return "text/csv";
    if (ext == "rtf") return "application/rtf";
    if (ext == "odt") return "application/vnd.oasis.opendocument.text";
    if (ext == "ods") return "application/vnd.oasis.opendocument.spreadsheet";
    if (ext == "odp") return "application/vnd.oasis.opendocument.presentation";
    
    // Por defecto
    return "application/octet-stream";
}

std::string ChunkedUpload::calculateChunkHash(const std::vector<char>& data) {
    // TODO: Implementar SHA256 real
    return "chunk_hash_" + std::to_string(data.size());
}

std::string ChunkedUpload::generateUUID() {
    // UUID simple basado en timestamp y random
    auto now = std::chrono::system_clock::now();
    auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(now.time_since_epoch()).count();
    
    std::stringstream ss;
    ss << std::hex << ms << "-" << std::rand();
    return ss.str();
}

void ChunkedUpload::cleanup() {
    m_isActive = false;
    m_completedChunks = 0;
}

double ChunkedUpload::progress() const {
    if (m_totalChunks == 0) return 0.0;
    return (double)m_completedChunks / m_totalChunks * 100.0;
}

// ============================================================================
// Upload Resume and Validation Implementation
// ============================================================================

bool ChunkedUpload::loadUploadState(const std::string& uploadId) {
    if (!m_database) {
        LOG_ERROR("Database not available");
        return false;
    }
    
    // Obtener info de chunked_files
    std::vector<ChunkedFileInfo> incompleteUploads = m_database->getIncompleteUploads();
    
    for (const auto& upload : incompleteUploads) {
        if (upload.fileId == uploadId) {
            m_uploadId = upload.fileId;
            m_fileName = upload.originalFilename;
            m_mimeType = upload.mimeType;
            m_fileSize = upload.totalSize;
            m_totalChunks = upload.totalChunks;
            m_completedChunks = upload.completedChunks;
            m_fileHash = upload.originalFileHash;
            
            LOG_INFO("Loaded upload state: " + m_fileName + 
                    " (" + std::to_string(m_completedChunks) + "/" + 
                    std::to_string(m_totalChunks) + " chunks)");
            return true;
        }
    }
    
    LOG_ERROR("Upload not found: " + uploadId);
    return false;
}

bool ChunkedUpload::validateExistingChunks(const std::string& filePath, std::set<int64_t>& validChunks) {
    if (!m_database) {
        LOG_ERROR("Database not available");
        return false;
    }
    
    // Guardar filePath para reanudación
    m_filePath = filePath;
    
    // Verificar que el archivo existe
    std::ifstream file(filePath, std::ios::binary | std::ios::ate);
    if (!file.is_open()) {
        LOG_ERROR("Cannot open file for validation: " + filePath);
        return false;
    }
    
    int64_t currentFileSize = file.tellg();
    if (currentFileSize != m_fileSize) {
        LOG_ERROR("File size mismatch: expected " + std::to_string(m_fileSize) + 
                 ", got " + std::to_string(currentFileSize));
        file.close();
        return false;
    }
    
    Config& config = Config::instance();
    int64_t chunkSize = config.chunkSize();
    
    // Obtener chunks completados de la BD
    std::vector<int64_t> completedChunks = m_database->getCompletedChunks(m_uploadId);
    
    LOG_INFO("Validating " + std::to_string(completedChunks.size()) + " completed chunks");
    
    // Validar integridad de cada chunk
    for (int64_t chunkNumber : completedChunks) {
        // Leer chunk del archivo
        file.seekg(chunkNumber * chunkSize);
        std::vector<char> chunkData(chunkSize);
        file.read(chunkData.data(), chunkSize);
        std::streamsize bytesRead = file.gcount();
        chunkData.resize(bytesRead);
        
        // Calcular hash
        std::string currentHash = calculateChunkHash(chunkData);
        
        // Validar contra BD
        if (m_database->validateChunkIntegrity(m_uploadId, chunkNumber, currentHash)) {
            validChunks.insert(chunkNumber);
            LOG_DEBUG("Chunk " + std::to_string(chunkNumber) + " validated successfully");
        } else {
            LOG_WARNING("Chunk " + std::to_string(chunkNumber) + 
                       " failed validation, will re-upload");
            // Marcar chunk como pending para re-subida
            m_database->updateChunkState(m_uploadId, chunkNumber, "pending");
        }
    }
    
    file.close();
    
    LOG_INFO("Validated " + std::to_string(validChunks.size()) + "/" + 
             std::to_string(completedChunks.size()) + " chunks successfully");
    
    // Actualizar completedChunks con la cantidad validada
    m_completedChunks = validChunks.size();
    
    return true;
}

std::vector<ChunkedFileInfo> ChunkedUpload::getIncompleteUploads() {
    if (!m_database) {
        LOG_ERROR("Database not available");
        return {};
    }
    
    return m_database->getIncompleteUploads();
}

} // namespace TelegramCloud
