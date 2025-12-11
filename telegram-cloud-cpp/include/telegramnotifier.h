#ifndef TELEGRAMNOTIFIER_H
#define TELEGRAMNOTIFIER_H

#include <string>
#include <thread>
#include <atomic>
#include <mutex>
#include <map>
#include <memory>

namespace TelegramCloud {

class Database;
class TelegramHandler;

/**
 * @brief Tipos de operaciones
 */
enum class OperationType {
    UPLOAD,
    DOWNLOAD
};

/**
 * @brief Estado de operación activa
 */
struct ActiveOperation {
    std::string operationId;
    OperationType type;
    std::string fileName;
    int64_t totalSize;
    int64_t completedChunks;
    int64_t totalChunks;
    double progressPercent;
    std::string status; // "downloading", "uploading", "reconstructing"
    std::string destination; // Para downloads
};

/**
 * @brief Notificador de progreso via Telegram
 * 
 * Envía notificaciones automáticas cuando se completan operaciones
 * y responde al símbolo "%" con el progreso actual.
 */
class TelegramNotifier {
public:
    TelegramNotifier(Database* database, TelegramHandler* telegramHandler);
    ~TelegramNotifier();
    
    /**
     * @brief Inicia el servicio de notificaciones
     */
    void start();
    
    /**
     * @brief Detiene el servicio de notificaciones
     */
    void stop();
    
    /**
     * @brief Registra una operación activa
     */
    void registerOperation(const std::string& operationId, 
                          OperationType type,
                          const std::string& fileName,
                          int64_t totalSize,
                          int64_t totalChunks);
    
    /**
     * @brief Actualiza el progreso de una operación
     */
    void updateOperationProgress(const std::string& operationId,
                                 int64_t completedChunks,
                                 double progressPercent,
                                 const std::string& status = "");
    
    /**
     * @brief Notifica operación completada
     */
    void notifyOperationCompleted(const std::string& operationId,
                                  const std::string& destination = "");
    
    /**
     * @brief Notifica operación fallida
     */
    void notifyOperationFailed(const std::string& operationId,
                              const std::string& errorMessage = "");
    
    /**
     * @brief Elimina operación del tracking
     */
    void removeOperation(const std::string& operationId);
    
    /**
     * @brief Verifica si el notificador está activo
     */
    bool isActive() const { return m_isActive; }

private:
    /**
     * @brief Thread principal de polling de mensajes
     */
    void pollingThread();
    
    /**
     * @brief Procesa comandos del usuario
     */
    void processCommand(const std::string& command);
    
    /**
     * @brief Envía mensaje de progreso actual
     */
    void sendProgressReport();
    
    /**
     * @brief Envía mensaje formateado a Telegram
     */
    bool sendMessage(const std::string& message);
    
    /**
     * @brief Formatea tamaño en MB/GB
     */
    std::string formatSize(int64_t bytes);
    
    /**
     * @brief Obtiene actualizaciones de Telegram
     */
    void getUpdates();
    
    Database* m_database;
    TelegramHandler* m_telegramHandler;
    
    std::atomic<bool> m_isActive;
    std::atomic<bool> m_shouldStop;
    std::thread m_pollingThread;
    
    // Tracking de operaciones activas
    std::map<std::string, ActiveOperation> m_activeOperations;
    std::mutex m_operationsMutex;
    
    // Control de polling
    int64_t m_lastUpdateId;
    std::mutex m_pollingMutex;
};

} // namespace TelegramCloud

#endif // TELEGRAMNOTIFIER_H


