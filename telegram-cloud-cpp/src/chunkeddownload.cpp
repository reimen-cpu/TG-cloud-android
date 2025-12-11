#include "chunkeddownload.h"
#include "telegramhandler.h"
#include "database.h"
#include "logger.h"
#include "config.h"
#include "telegramnotifier.h"

// Inicializar miembros estáticos
namespace TelegramCloud {
    std::map<std::string, std::atomic<bool>> ChunkedDownload::s_pausedDownloads;
    std::map<std::string, std::atomic<bool>> ChunkedDownload::s_canceledDownloads;
    std::mutex ChunkedDownload::s_controlMutex;
}

#include <fstream>
#include <thread>
#include <future>
#include <algorithm>
#include <sstream>
#include <iomanip>
#include <random>
#include <filesystem>

namespace TelegramCloud {

// ============================================================================
// ChunkedDownload Implementation
// ============================================================================

ChunkedDownload::ChunkedDownload(Database* database, TelegramHandler* telegramHandler, TelegramNotifier* notifier)
    : m_database(database)
    , m_telegramHandler(telegramHandler)
    , m_notifier(notifier)
    , m_fileSize(0)
    , m_isActive(false)
    , m_isCanceled(false)
    , m_isPaused(false)
    , m_completedChunks(0)
    , m_totalChunks(0)
{
}

ChunkedDownload::~ChunkedDownload() {
    cleanup();
}

std::string ChunkedDownload::startDownload(const std::string& fileId, const std::string& destPath) {
    m_fileId = fileId;
    m_destPath = destPath;
    m_downloadId = generateUUID();
    
    LOG_INFO("Starting chunked download for file: " + fileId);
    
    // Obtener chunks del archivo desde la base de datos
    m_chunks = m_database->getFileChunks(fileId);
    if (m_chunks.empty()) {
        LOG_ERROR("No chunks found for file: " + fileId);
        return "";
    }
    
    m_totalChunks = m_chunks.size();
    
    // Obtener info del archivo
    FileInfo fileInfo = m_database->getFileInfo(fileId);
    if (fileInfo.fileId.empty()) {
        LOG_ERROR("File info not found: " + fileId);
        return "";
    }
    
    m_fileName = fileInfo.fileName;
    m_fileSize = fileInfo.fileSize;
    
    LOG_INFO("File name: " + m_fileName);
    LOG_INFO("File size: " + std::to_string(m_fileSize) + " bytes");
    LOG_INFO("Total chunks to download: " + std::to_string(m_totalChunks));
    
    // Crear directorio temporal para chunks
    std::string tempDir = "temp_download_" + m_downloadId;
    try {
        std::filesystem::create_directories(tempDir);
    } catch (const std::exception& e) {
        LOG_ERROR("Failed to create temp directory: " + std::string(e.what()));
        return "";
    }
    
    // Registrar descarga en base de datos
    if (m_database) {
        DownloadInfo downloadInfo;
        downloadInfo.downloadId = m_downloadId;
        downloadInfo.fileId = fileId;
        downloadInfo.fileName = m_fileName;
        downloadInfo.destPath = destPath;
        downloadInfo.totalSize = m_fileSize;
        downloadInfo.totalChunks = m_totalChunks;
        downloadInfo.completedChunks = 0;
        downloadInfo.status = "downloading";
        downloadInfo.tempDir = tempDir;
        
        if (!m_database->registerDownload(downloadInfo)) {
            LOG_ERROR("Failed to register download in database");
            return "";
        }
        
        LOG_INFO("Download registered in database");
    }
    
    // Limpiar cualquier estado compartido previo
    {
        std::lock_guard<std::mutex> controlLock(s_controlMutex);
        s_pausedDownloads.erase(m_downloadId);
        s_canceledDownloads.erase(m_downloadId);
    }
    
    m_isActive = true;
    m_isCanceled = false;
    m_isPaused = false;
    m_completedChunks = 0;
    
    // Registrar operación en notificador
    if (m_notifier) {
        m_notifier->registerOperation(m_downloadId, OperationType::DOWNLOAD, 
                                     m_fileName, m_fileSize, m_totalChunks);
    }
    
    // Descargar chunks en paralelo
    downloadChunksParallel();
    
    // Si se completó exitosamente, reconstruir archivo
    if (!m_isPaused && !m_isCanceled) {
        if (m_completedChunks == m_totalChunks) {
            LOG_INFO("All chunks downloaded, reconstructing file...");
            
            if (reconstructFile(tempDir, m_destPath)) {
                LOG_INFO("File reconstructed successfully: " + m_destPath);
                
                // Actualizar estado en BD
                if (m_database) {
                    m_database->updateDownloadState(m_downloadId, "completed");
                }
                
                // Notificar completado
                if (m_notifier) {
                    m_notifier->notifyOperationCompleted(m_downloadId, m_destPath);
                }
                
                // Eliminar directorio temporal
                try {
                    std::filesystem::remove_all(tempDir);
                    LOG_INFO("Temp directory removed: " + tempDir);
                } catch (const std::exception& e) {
                    LOG_WARNING("Failed to remove temp directory: " + std::string(e.what()));
                }
            } else {
                LOG_ERROR("Failed to reconstruct file");
                if (m_database) {
                    m_database->updateDownloadState(m_downloadId, "failed");
                }
                
                // Notificar fallido
                if (m_notifier) {
                    m_notifier->notifyOperationFailed(m_downloadId, "Failed to reconstruct file");
                }
            }
        } else {
            LOG_ERROR("Download incomplete: " + std::to_string(m_completedChunks) + "/" + std::to_string(m_totalChunks));
            if (m_notifier) {
                m_notifier->notifyOperationFailed(m_downloadId, "Download incomplete");
            }
        }
    }
    
    m_isActive = false;
    
    return m_downloadId;
}

std::string ChunkedDownload::resumeDownload(const std::string& downloadId, const std::string& destPath) {
    std::lock_guard<std::mutex> lock(m_stateMutex);
    
    LOG_INFO("Resuming download: " + downloadId);
    
    if (!loadDownloadState(downloadId)) {
        LOG_ERROR("Failed to load download state for: " + downloadId);
        return "";
    }
    
    // Asignar destPath
    m_destPath = destPath;
    
    // Validar chunks existentes
    std::set<int64_t> validChunks;
    std::string tempDir = "temp_download_" + downloadId;
    if (!validateExistingChunks(tempDir, validChunks)) {
        LOG_WARNING("Failed to validate existing chunks, restarting from scratch");
        validChunks.clear();
    }
    
    LOG_INFO("Found " + std::to_string(validChunks.size()) + " valid chunks, resuming download");
    
    // Limpiar estado compartido para permitir reanudación
    {
        std::lock_guard<std::mutex> controlLock(s_controlMutex);
        s_pausedDownloads.erase(downloadId);
        s_canceledDownloads.erase(downloadId);
    }
    
    m_isActive = true;
    m_isCanceled = false;
    m_isPaused = false;
    
    // Actualizar estado en BD
    if (m_database) {
        m_database->updateDownloadState(m_downloadId, "downloading");
    }
    
    // Registrar operación en notificador
    if (m_notifier) {
        m_notifier->registerOperation(m_downloadId, OperationType::DOWNLOAD, 
                                     m_fileName, m_fileSize, m_totalChunks);
    }
    
    // Continuar descarga, omitiendo chunks válidos
    downloadChunksParallel(validChunks);
    
    // Si se completó exitosamente, reconstruir archivo
    if (!m_isPaused && !m_isCanceled) {
        if (m_completedChunks == m_totalChunks) {
            LOG_INFO("All chunks downloaded, reconstructing file...");
            
            if (reconstructFile(tempDir, m_destPath)) {
                LOG_INFO("File reconstructed successfully: " + m_destPath);
                
                // Actualizar estado en BD
                if (m_database) {
                    m_database->updateDownloadState(m_downloadId, "completed");
                }
                
                // Notificar completado
                if (m_notifier) {
                    m_notifier->notifyOperationCompleted(m_downloadId, m_destPath);
                }
                
                // Eliminar directorio temporal
                try {
                    std::filesystem::remove_all(tempDir);
                    LOG_INFO("Temp directory removed: " + tempDir);
                } catch (const std::exception& e) {
                    LOG_WARNING("Failed to remove temp directory: " + std::string(e.what()));
                }
            } else {
                LOG_ERROR("Failed to reconstruct file");
                if (m_database) {
                    m_database->updateDownloadState(m_downloadId, "failed");
                }
                
                // Notificar fallido
                if (m_notifier) {
                    m_notifier->notifyOperationFailed(m_downloadId, "Failed to reconstruct file");
                }
            }
        } else {
            LOG_ERROR("Download incomplete: " + std::to_string(m_completedChunks) + "/" + std::to_string(m_totalChunks));
            if (m_notifier) {
                m_notifier->notifyOperationFailed(m_downloadId, "Download incomplete");
            }
        }
    }
    
    m_isActive = false;
    
    return m_downloadId;
}

bool ChunkedDownload::pauseDownload(const std::string& downloadId) {
    LOG_INFO("Pausing download: " + downloadId);
    
    // Marcar en el mapa compartido
    {
        std::lock_guard<std::mutex> lock(s_controlMutex);
        s_pausedDownloads[downloadId] = true;
    }
    
    // Si es la misma instancia, marcar local también
    if (m_downloadId == downloadId) {
        m_isPaused = true;
    }
    
    // Actualizar estado en base de datos
    if (m_database) {
        m_database->updateDownloadState(downloadId, "paused");
    }
    
    return true;
}

bool ChunkedDownload::stopDownload(const std::string& downloadId) {
    LOG_INFO("Stopping download: " + downloadId);
    
    // Marcar en el mapa compartido
    {
        std::lock_guard<std::mutex> lock(s_controlMutex);
        s_pausedDownloads[downloadId] = true;
    }
    
    // Si es la misma instancia, detener
    if (m_downloadId == downloadId) {
        m_isActive = false;
        m_isPaused = true;
    }
    
    // Actualizar estado en base de datos
    if (m_database) {
        m_database->updateDownloadState(downloadId, "stopped");
    }
    
    return true;
}

bool ChunkedDownload::cancelDownload(const std::string& downloadId) {
    LOG_INFO("Canceling download: " + downloadId);
    
    // Marcar en el mapa compartido
    {
        std::lock_guard<std::mutex> lock(s_controlMutex);
        s_canceledDownloads[downloadId] = true;
    }
    
    // Si es la misma instancia, cancelar
    if (m_downloadId == downloadId) {
        m_isCanceled = true;
        m_isActive = false;
        m_isPaused = false;
    }
    
    // Eliminar progreso de base de datos
    if (m_database) {
        m_database->deleteDownloadProgress(downloadId);
    }
    
    // Eliminar directorio temporal
    std::string tempDir = "temp_download_" + downloadId;
    try {
        std::filesystem::remove_all(tempDir);
        LOG_INFO("Temp directory removed: " + tempDir);
    } catch (const std::exception& e) {
        LOG_WARNING("Failed to remove temp directory: " + std::string(e.what()));
    }
    
    cleanup();
    return true;
}

void ChunkedDownload::downloadChunksParallel(const std::set<int64_t>& skipChunks) {
    LOG_INFO("Starting parallel chunk download");
    
    if (!skipChunks.empty()) {
        LOG_INFO("Skipping " + std::to_string(skipChunks.size()) + " already completed chunks");
    }
    
    std::string tempDir = "temp_download_" + m_downloadId;
    
    // Crear directorio temporal si no existe
    try {
        std::filesystem::create_directories(tempDir);
    } catch (const std::exception& e) {
        LOG_ERROR("Failed to create temp directory: " + std::string(e.what()));
        return;
    }
    
    // Vector de futures para paralelización
    std::vector<std::future<bool>> futures;
    Config& config = Config::instance();
    const int MAX_PARALLEL = 5; // Descargas paralelas simultáneas
    
    for (size_t i = 0; i < m_chunks.size(); ++i) {
        // Verificar si fue cancelada o pausada
        {
            std::lock_guard<std::mutex> lock(s_controlMutex);
            if (s_canceledDownloads[m_downloadId]) {
                LOG_WARNING("Download canceled, stopping chunk download");
                m_isCanceled = true;
                break;
            }
            if (s_pausedDownloads[m_downloadId]) {
                LOG_INFO("Download paused, stopping chunk download");
                m_isPaused = true;
                break;
            }
        }
        
        if (m_isCanceled || m_isPaused) {
            break;
        }
        
        const ChunkInfo& chunk = m_chunks[i];
        
        // Omitir chunks ya completados
        if (skipChunks.find(chunk.chunkNumber) != skipChunks.end()) {
            LOG_DEBUG("Skipping already completed chunk: " + std::to_string(chunk.chunkNumber));
            continue;
        }
        
        // Lanzar descarga asíncrona
        auto future = std::async(std::launch::async, [this, chunk, tempDir]() {
            return downloadSingleChunk(chunk, tempDir);
        });
        
        futures.push_back(std::move(future));
        
        // Limitar descargas paralelas
        if (futures.size() >= MAX_PARALLEL) {
            // Esperar al menos uno
            for (auto& f : futures) {
                f.get();
            }
            futures.clear();
        }
    }
    
    // Esperar a que terminen todos los futuros restantes
    for (auto& f : futures) {
        f.get();
    }
    
    LOG_INFO("All chunks download completed. Completed: " + 
             std::to_string(m_completedChunks) + "/" + std::to_string(m_totalChunks));
}

bool ChunkedDownload::downloadSingleChunk(const ChunkInfo& chunk, const std::string& tempDir) {
    std::string chunkPath = tempDir + "/chunk_" + std::to_string(chunk.chunkNumber) + ".tmp";
    
    LOG_INFO("Starting download: " + chunk.telegramFileId + " to " + chunkPath);
    
    // Reintentar hasta 3 veces
    bool success = false;
    for (int retry = 0; retry < 3 && !success; retry++) {
        if (retry > 0) {
            LOG_WARNING("Retrying chunk " + std::to_string(chunk.chunkNumber) + " (attempt " + std::to_string(retry + 1) + "/3)");
            std::this_thread::sleep_for(std::chrono::seconds(1));
        }
        success = m_telegramHandler->downloadFile(chunk.telegramFileId, chunkPath);
    }
    
    if (success) {
        m_completedChunks++;
        
        // Guardar progreso en BD
        if (m_database) {
            m_database->updateDownloadChunkState(m_downloadId, chunk.chunkNumber, "completed");
            m_database->updateDownloadProgress(m_downloadId, m_completedChunks);
        }
        
        // Notificar progreso
        if (m_progressCallback) {
            double percent = progress();
            m_progressCallback(m_completedChunks, m_totalChunks, percent);
        }
        
        // Actualizar progreso en TelegramNotifier
        if (m_notifier) {
            double percent = progress();
            m_notifier->updateOperationProgress(m_downloadId, m_completedChunks, percent, "downloading");
        }
        
        LOG_INFO("Chunk " + std::to_string(chunk.chunkNumber + 1) + "/" + 
                std::to_string(m_totalChunks) + " downloaded successfully");
        
        return true;
    } else {
        LOG_ERROR("Failed to download chunk " + std::to_string(chunk.chunkNumber));
        return false;
    }
}

bool ChunkedDownload::reconstructFile(const std::string& tempDir, const std::string& destPath) {
    LOG_INFO("Reconstructing file from chunks: " + destPath);
    
    std::ofstream outputFile(destPath, std::ios::binary);
    if (!outputFile.is_open()) {
        LOG_ERROR("Failed to open output file: " + destPath);
        return false;
    }
    
    // Reconstruir en orden con reporte de progreso
    int64_t processedChunks = 0;
    int64_t totalChunksToReconstruct = static_cast<int64_t>(m_chunks.size());
    
    for (const auto& chunk : m_chunks) {
        std::string chunkPath = tempDir + "/chunk_" + std::to_string(chunk.chunkNumber) + ".tmp";
        
        std::ifstream chunkFile(chunkPath, std::ios::binary);
        if (!chunkFile.is_open()) {
            LOG_ERROR("Failed to open chunk file: " + chunkPath);
            outputFile.close();
            return false;
        }
        
        outputFile << chunkFile.rdbuf();
        chunkFile.close();
        
        processedChunks++;
        
        // Reportar progreso de reconstrucción (usando valores negativos para distinguir de descarga)
        if (m_progressCallback) {
            double reconstructPercent = (static_cast<double>(processedChunks) / static_cast<double>(totalChunksToReconstruct)) * 100.0;
            m_progressCallback(-processedChunks, -totalChunksToReconstruct, reconstructPercent);
        }
        
        LOG_DEBUG("Reconstructed chunk " + std::to_string(processedChunks) + "/" + std::to_string(totalChunksToReconstruct));
    }
    
    outputFile.close();
    LOG_INFO("File reconstruction completed");
    
    return true;
}

double ChunkedDownload::progress() const {
    if (m_totalChunks == 0) return 0.0;
    return (double)m_completedChunks / m_totalChunks * 100.0;
}

std::vector<DownloadInfo> ChunkedDownload::getIncompleteDownloads() {
    if (!m_database) {
        LOG_ERROR("Database not initialized");
        return {};
    }
    
    return m_database->getIncompleteDownloads();
}

std::string ChunkedDownload::generateUUID() {
    std::random_device rd;
    std::mt19937_64 gen(rd());
    std::uniform_int_distribution<uint64_t> dis;
    
    std::stringstream ss;
    ss << std::hex << std::setfill('0');
    ss << std::setw(8) << (dis(gen) & 0xFFFFFFFF);
    ss << std::setw(4) << (dis(gen) & 0xFFFF);
    
    return ss.str();
}

void ChunkedDownload::cleanup() {
    m_isActive = false;
}

bool ChunkedDownload::validateExistingChunks(const std::string& tempDir, std::set<int64_t>& validChunks) {
    if (!m_database) {
        LOG_ERROR("Database not initialized");
        return false;
    }
    
    // Obtener chunks completados de la BD
    std::vector<int64_t> completedChunks = m_database->getCompletedDownloadChunks(m_downloadId);
    
    LOG_INFO("Validating " + std::to_string(completedChunks.size()) + " completed chunks");
    
    int validCount = 0;
    for (int64_t chunkNum : completedChunks) {
        std::string chunkPath = tempDir + "/chunk_" + std::to_string(chunkNum) + ".tmp";
        
        // Verificar si el archivo existe
        if (std::filesystem::exists(chunkPath)) {
            validChunks.insert(chunkNum);
            validCount++;
        } else {
            LOG_WARNING("Chunk file missing: " + chunkPath);
        }
    }
    
    LOG_INFO("Validated " + std::to_string(validCount) + "/" + 
             std::to_string(completedChunks.size()) + " chunks successfully");
    
    m_completedChunks = validCount;
    
    return true;
}

bool ChunkedDownload::loadDownloadState(const std::string& downloadId) {
    if (!m_database) {
        LOG_ERROR("Database not initialized");
        return false;
    }
    
    // Obtener info de descarga
    std::vector<DownloadInfo> downloads = m_database->getIncompleteDownloads();
    
    for (const auto& download : downloads) {
        if (download.downloadId == downloadId) {
            m_downloadId = download.downloadId;
            m_fileId = download.fileId;
            m_fileName = download.fileName;
            m_destPath = download.destPath;
            m_fileSize = download.totalSize;
            m_totalChunks = download.totalChunks;
            m_completedChunks = download.completedChunks;
            
            // Obtener chunks del archivo
            m_chunks = m_database->getFileChunks(m_fileId);
            
            LOG_INFO("Loaded download state: " + m_fileName + " (" + 
                    std::to_string(m_completedChunks) + "/" + 
                    std::to_string(m_totalChunks) + " chunks)");
            
            return true;
        }
    }
    
    LOG_ERROR("Download not found: " + downloadId);
    return false;
}

} // namespace TelegramCloud



