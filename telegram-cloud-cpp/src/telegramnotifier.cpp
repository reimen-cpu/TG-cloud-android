#include "telegramnotifier.h"
#include "database.h"
#include "telegramhandler.h"
#include "logger.h"
#include "envmanager.h"
#include <sstream>
#include <iomanip>
#include <chrono>
#include <thread>
#include <curl/curl.h>
#include <nlohmann/json.hpp>

using json = nlohmann::json;

namespace TelegramCloud {

// Callback para CURL
static size_t WriteCallback(void* contents, size_t size, size_t nmemb, std::string* userp) {
    userp->append((char*)contents, size * nmemb);
    return size * nmemb;
}

TelegramNotifier::TelegramNotifier(Database* database, TelegramHandler* telegramHandler)
    : m_database(database)
    , m_telegramHandler(telegramHandler)
    , m_isActive(false)
    , m_shouldStop(false)
    , m_lastUpdateId(0) {
}

TelegramNotifier::~TelegramNotifier() {
    stop();
}

void TelegramNotifier::start() {
    if (m_isActive.load()) {
        LOG_WARNING("TelegramNotifier already active");
        return;
    }
    
    m_shouldStop = false;
    m_isActive = true;
    
    // Iniciar thread de polling
    m_pollingThread = std::thread(&TelegramNotifier::pollingThread, this);
    
    LOG_INFO("TelegramNotifier started");
}

void TelegramNotifier::stop() {
    if (!m_isActive.load()) {
        return;
    }
    
    m_shouldStop = true;
    m_isActive = false;
    
    if (m_pollingThread.joinable()) {
        m_pollingThread.join();
    }
    
    LOG_INFO("TelegramNotifier stopped");
}

void TelegramNotifier::registerOperation(const std::string& operationId,
                                        OperationType type,
                                        const std::string& fileName,
                                        int64_t totalSize,
                                        int64_t totalChunks) {
    std::lock_guard<std::mutex> lock(m_operationsMutex);
    
    ActiveOperation op;
    op.operationId = operationId;
    op.type = type;
    op.fileName = fileName;
    op.totalSize = totalSize;
    op.totalChunks = totalChunks;
    op.completedChunks = 0;
    op.progressPercent = 0.0;
    op.status = (type == OperationType::UPLOAD) ? "uploading" : "downloading";
    
    m_activeOperations[operationId] = op;
    
    LOG_INFO("Registered operation: " + operationId + " (" + fileName + ")");
}

void TelegramNotifier::updateOperationProgress(const std::string& operationId,
                                              int64_t completedChunks,
                                              double progressPercent,
                                              const std::string& status) {
    std::lock_guard<std::mutex> lock(m_operationsMutex);
    
    auto it = m_activeOperations.find(operationId);
    if (it != m_activeOperations.end()) {
        it->second.completedChunks = completedChunks;
        it->second.progressPercent = progressPercent;
        if (!status.empty()) {
            it->second.status = status;
        }
    }
}

void TelegramNotifier::notifyOperationCompleted(const std::string& operationId,
                                               const std::string& destination) {
    std::lock_guard<std::mutex> lock(m_operationsMutex);
    
    auto it = m_activeOperations.find(operationId);
    if (it == m_activeOperations.end()) {
        LOG_WARNING("Operation not found for completion notification: " + operationId);
        return;
    }
    
    const auto& op = it->second;
    
    // Formatear mensaje
    std::ostringstream msg;
    
    if (op.type == OperationType::UPLOAD) {
        msg << "⬆️ Upload Completed\n\n";
    } else {
        msg << "⬇️ Download Completed\n\n";
    }
    
    msg << "📁 File: " << op.fileName << "\n\n";
    msg << "📊 Size: " << formatSize(op.totalSize) << "\n\n";
    msg << "📦 Chunks: " << op.totalChunks << "\n\n";
    
    if (op.type == OperationType::DOWNLOAD && !destination.empty()) {
        msg << "📥 Location: " << destination << "\n\n";
    }
    
    msg << "🆔 ID: " << operationId;
    
    // Enviar mensaje
    sendMessage(msg.str());
    
    // Remover operación
    m_activeOperations.erase(it);
    
    LOG_INFO("Sent completion notification for: " + operationId);
}

void TelegramNotifier::notifyOperationFailed(const std::string& operationId,
                                            const std::string& errorMessage) {
    std::lock_guard<std::mutex> lock(m_operationsMutex);
    
    auto it = m_activeOperations.find(operationId);
    if (it == m_activeOperations.end()) {
        LOG_WARNING("Operation not found for failure notification: " + operationId);
        return;
    }
    
    const auto& op = it->second;
    
    // Formatear mensaje
    std::ostringstream msg;
    
    if (op.type == OperationType::UPLOAD) {
        msg << "❌ Upload Failed\n\n";
    } else {
        msg << "❌ Download Failed\n\n";
    }
    
    msg << "📁 File: " << op.fileName << "\n\n";
    msg << "📊 Progress: " << std::fixed << std::setprecision(2) << op.progressPercent << "%\n\n";
    msg << "📦 Chunks: " << op.completedChunks << "/" << op.totalChunks << "\n\n";
    
    if (!errorMessage.empty()) {
        msg << "⚠️ Error: " << errorMessage << "\n\n";
    }
    
    msg << "🆔 ID: " << operationId;
    
    // Enviar mensaje
    sendMessage(msg.str());
    
    // Remover operación
    m_activeOperations.erase(it);
    
    LOG_INFO("Sent failure notification for: " + operationId);
}

void TelegramNotifier::removeOperation(const std::string& operationId) {
    std::lock_guard<std::mutex> lock(m_operationsMutex);
    m_activeOperations.erase(operationId);
}

void TelegramNotifier::pollingThread() {
    LOG_INFO("Polling thread started");
    
    while (!m_shouldStop.load()) {
        try {
            getUpdates();
        } catch (const std::exception& e) {
            LOG_ERROR("Error in polling thread: " + std::string(e.what()));
        }
        
        // Sleep mínimo (Long Polling mantiene la conexión abierta hasta 50s)
        std::this_thread::sleep_for(std::chrono::seconds(1));
    }
    
    LOG_INFO("Polling thread stopped");
}

void TelegramNotifier::getUpdates() {
    std::lock_guard<std::mutex> lock(m_pollingMutex);
    
    std::string botToken = EnvManager::instance().get("BOT_TOKEN");
    if (botToken.empty()) {
        LOG_DEBUG("Bot token not configured for polling");
        return;
    }
    
    // Construir URL correctamente con Long Polling (timeout reducido para cierre rápido)
    std::string url = "https://api.telegram.org/bot" + botToken + "/getUpdates";
    if (m_lastUpdateId > 0) {
        url += "?offset=" + std::to_string(m_lastUpdateId + 1) + "&timeout=10"; // 10s para permitir cierre rápido
    } else {
        url += "?timeout=10"; // 10s
    }
    
    CURL* curl = curl_easy_init();
    if (!curl) {
        LOG_ERROR("Failed to initialize CURL for polling");
        return;
    }
    
    std::string response;
    curl_easy_setopt(curl, CURLOPT_URL, url.c_str());
    curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, WriteCallback);
    curl_easy_setopt(curl, CURLOPT_WRITEDATA, &response);
    curl_easy_setopt(curl, CURLOPT_TIMEOUT, 12L); // Ligeramente mayor que timeout del servidor (10s)
    
    CURLcode res = curl_easy_perform(curl);
    curl_easy_cleanup(curl);
    
    if (res != CURLE_OK) {
        LOG_DEBUG("Failed to get updates: " + std::string(curl_easy_strerror(res)));
        return;
    }
    
    try {
        json j = json::parse(response);
        
        if (j["ok"].get<bool>() && j.contains("result")) {
            auto results = j["result"];
            
            if (!results.empty()) {
                LOG_DEBUG("Received " + std::to_string(results.size()) + " update(s)");
            }
            
            for (const auto& update : results) {
                int64_t updateId = update["update_id"].get<int64_t>();
                if (updateId > m_lastUpdateId) {
                    m_lastUpdateId = updateId;
                }
                
                // Log del contenido del update para debug
                LOG_DEBUG("Update ID: " + std::to_string(updateId) + ", contains message: " + 
                         std::string(update.contains("message") ? "yes" : "no"));
                
                // Procesar mensaje
                if (update.contains("message")) {
                    const auto& message = update["message"];
                    
                    LOG_DEBUG("Message from chat: " + 
                             (message.contains("chat") && message["chat"].contains("id") ? 
                              std::to_string(message["chat"]["id"].get<int64_t>()) : "unknown"));
                    
                    if (message.contains("text")) {
                        std::string text = message["text"].get<std::string>();
                        LOG_INFO("Received command: " + text);
                        processCommand(text);
                    } else {
                        LOG_DEBUG("Message does not contain text");
                    }
                } else if (update.contains("channel_post")) {
                    const auto& channelPost = update["channel_post"];
                    
                    LOG_DEBUG("Channel post from: " + 
                             (channelPost.contains("chat") && channelPost["chat"].contains("id") ? 
                              std::to_string(channelPost["chat"]["id"].get<int64_t>()) : "unknown"));
                    
                    if (channelPost.contains("text")) {
                        std::string text = channelPost["text"].get<std::string>();
                        LOG_INFO("Received command from channel: " + text);
                        processCommand(text);
                    }
                }
            }
        } else if (j.contains("description")) {
            LOG_ERROR("Telegram API error: " + j["description"].get<std::string>());
        }
    } catch (const std::exception& e) {
        LOG_ERROR("Error parsing updates: " + std::string(e.what()));
        LOG_DEBUG("Response: " + response);
    }
}

void TelegramNotifier::processCommand(const std::string& command) {
    LOG_INFO("Processing command: '" + command + "'");
    
    // Eliminar espacios en blanco al inicio y final
    std::string trimmedCommand = command;
    trimmedCommand.erase(0, trimmedCommand.find_first_not_of(" \t\n\r"));
    trimmedCommand.erase(trimmedCommand.find_last_not_of(" \t\n\r") + 1);
    
    if (trimmedCommand == "%") {
        LOG_INFO("Sending progress report...");
        sendProgressReport();
    } else {
        LOG_DEBUG("Unknown command: '" + trimmedCommand + "'");
    }
}

void TelegramNotifier::sendProgressReport() {
    std::lock_guard<std::mutex> lock(m_operationsMutex);
    
    LOG_INFO("Generating progress report (" + std::to_string(m_activeOperations.size()) + " active operations)");
    
    if (m_activeOperations.empty()) {
        LOG_INFO("No active operations, sending empty report");
        sendMessage("📊 No active operations");
        return;
    }
    
    std::ostringstream msg;
    msg << "📊 Active Operations Report\n\n";
    
    int index = 1;
    for (const auto& pair : m_activeOperations) {
        const auto& op = pair.second;
        
        if (op.type == OperationType::UPLOAD) {
            msg << "⬆️ Upload #" << index << "\n";
        } else {
            msg << "⬇️ Download #" << index << "\n";
        }
        
        msg << "📁 " << op.fileName << "\n";
        msg << "📊 Progress: " << std::fixed << std::setprecision(2) << op.progressPercent << "%\n";
        msg << "📦 Chunks: " << op.completedChunks << "/" << op.totalChunks << "\n";
        msg << "📏 Size: " << formatSize(op.totalSize) << "\n";
        msg << "🔄 Status: " << op.status << "\n";
        msg << "🆔 " << op.operationId << "\n\n";
        
        index++;
    }
    
    LOG_INFO("Sending progress report message");
    sendMessage(msg.str());
}

bool TelegramNotifier::sendMessage(const std::string& message) {
    std::string botToken = EnvManager::instance().get("BOT_TOKEN");
    std::string chatId = EnvManager::instance().get("CHAT_ID");
    
    // Si CHAT_ID está vacío, usar CHANNEL_ID
    if (chatId.empty()) {
        chatId = EnvManager::instance().get("CHANNEL_ID");
        LOG_DEBUG("CHAT_ID not found, using CHANNEL_ID: " + chatId);
    }
    
    if (botToken.empty() || chatId.empty()) {
        LOG_ERROR("Bot token or chat/channel ID not configured for sending message");
        LOG_DEBUG("Bot token empty: " + std::string(botToken.empty() ? "yes" : "no") + 
                 ", Chat/Channel ID empty: " + std::string(chatId.empty() ? "yes" : "no"));
        return false;
    }
    
    LOG_DEBUG("Sending message to chat: " + chatId);
    
    std::string url = "https://api.telegram.org/bot" + botToken + "/sendMessage";
    
    // Crear payload JSON
    json payload;
    payload["chat_id"] = chatId;
    payload["text"] = message;
    payload["parse_mode"] = "HTML";
    
    std::string jsonData = payload.dump();
    
    CURL* curl = curl_easy_init();
    if (!curl) {
        LOG_ERROR("Failed to initialize CURL for sending message");
        return false;
    }
    
    struct curl_slist* headers = nullptr;
    headers = curl_slist_append(headers, "Content-Type: application/json");
    
    std::string response;
    curl_easy_setopt(curl, CURLOPT_URL, url.c_str());
    curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headers);
    curl_easy_setopt(curl, CURLOPT_POSTFIELDS, jsonData.c_str());
    curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, WriteCallback);
    curl_easy_setopt(curl, CURLOPT_WRITEDATA, &response);
    curl_easy_setopt(curl, CURLOPT_TIMEOUT, 10L);
    
    CURLcode res = curl_easy_perform(curl);
    
    curl_slist_free_all(headers);
    curl_easy_cleanup(curl);
    
    if (res != CURLE_OK) {
        LOG_ERROR("Failed to send message: " + std::string(curl_easy_strerror(res)));
        return false;
    }
    
    // Parsear respuesta para verificar éxito
    try {
        json j = json::parse(response);
        if (j["ok"].get<bool>()) {
            LOG_INFO("Message sent successfully");
            return true;
        } else {
            std::string error = j.contains("description") ? j["description"].get<std::string>() : "Unknown error";
            LOG_ERROR("Telegram API error when sending message: " + error);
            return false;
        }
    } catch (const std::exception& e) {
        LOG_ERROR("Failed to parse sendMessage response: " + std::string(e.what()));
        LOG_DEBUG("Response: " + response);
        return false;
    }
}

std::string TelegramNotifier::formatSize(int64_t bytes) {
    double sizeMB = bytes / 1024.0 / 1024.0;
    
    std::ostringstream oss;
    oss << std::fixed << std::setprecision(2);
    
    if (sizeMB >= 1024.0) {
        oss << (sizeMB / 1024.0) << " GB";
    } else {
        oss << sizeMB << " MB";
    }
    
    return oss.str();
}

} // namespace TelegramCloud

