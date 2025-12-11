#ifndef CONFIG_H
#define CONFIG_H

#include <string>
#include <vector>
#include <map>
#include <fstream>
#include <sstream>

namespace TelegramCloud {

/**
 * @brief Configuración global de la aplicación
 */
class Config {
public:
    static Config& instance();
    
    // Telegram Configuration
    std::string botToken() const { return m_botToken; }
    std::string channelId() const { return m_channelId; }
    std::string apiId() const { return m_apiId; }
    std::string apiHash() const { return m_apiHash; }
    std::vector<std::string> additionalTokens() const { return m_additionalTokens; }
    std::vector<std::string> allTokens() const;
    
    // Application Configuration
    int chunkSize() const { return m_chunkSize; }
    int chunkThreshold() const { return m_chunkThreshold; }
    int maxRetries() const { return m_maxRetries; }
    int apiPort() const { return m_apiPort; }
    std::string apiHost() const { return m_apiHost; }
    
    // Database Configuration
    std::string databasePath() const { return m_databasePath; }
    
    // Logging Configuration
    std::string logLevel() const { return m_logLevel; }
    std::string logPath() const { return m_logPath; }
    
    // Telegram API URLs
    std::string telegramApiBase() const { return m_telegramApiBase; }
    std::string telegramFileApiBase() const { return m_telegramFileApiBase; }
    
    // Validation
    bool isValid() const;
    std::string validationError() const { return m_validationError; }
    
    // Constants
    static constexpr int DEFAULT_CHUNK_SIZE = 4 * 1024 * 1024; // 4MB
    static constexpr int DEFAULT_CHUNK_THRESHOLD = 4 * 1024 * 1024;
    static constexpr int DEFAULT_MAX_RETRIES = 3;
    static constexpr int DEFAULT_API_PORT = 5000;
    
private:
    Config();
    ~Config() = default;
    Config(const Config&) = delete;
    Config& operator=(const Config&) = delete;
    
    void loadConfiguration();
    void loadFromFile();
    void loadFromEnvironment();
    void validateConfiguration();
    std::string parseEnvValue(const std::string& value) const;
    std::string trim(const std::string& str) const;
    std::vector<std::string> split(const std::string& str, char delimiter) const;
    
    // Telegram
    std::string m_botToken;
    std::string m_channelId;
    std::string m_apiId;
    std::string m_apiHash;
    std::vector<std::string> m_additionalTokens;
    
    // Application
    int m_chunkSize;
    int m_chunkThreshold;
    int m_maxRetries;
    int m_apiPort;
    std::string m_apiHost;
    
    // Database
    std::string m_databasePath;
    
    // Logging
    std::string m_logLevel;
    std::string m_logPath;
    
    // API URLs
    std::string m_telegramApiBase;
    std::string m_telegramFileApiBase;
    
    // Validation
    std::string m_validationError;
};

// Upload/Download States
enum class UploadState {
    Pending,
    Uploading,
    Completed,
    Error,
    Canceled
};

// Helper functions
std::string uploadStateToString(UploadState state);
UploadState stringToUploadState(const std::string& str);

} // namespace TelegramCloud

#endif // CONFIG_H
