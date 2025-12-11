#ifndef TELEGRAMHANDLER_H
#define TELEGRAMHANDLER_H

#include <string>
#include <vector>
#include <memory>

namespace TelegramCloud {

struct UploadResult {
    bool success;
    std::string fileId;
    int64_t messageId;
    std::string errorMessage;
    int statusCode;
};

/**
 * @brief Manejo de operaciones con Telegram Bot API usando libcurl
 */
class TelegramHandler {
public:
    TelegramHandler();
    ~TelegramHandler();
    
    // Bot pool management
    std::string getNextBotToken();
    std::string getMainBotToken() const;
    std::vector<std::string> getAllTokens() const;
    int getBotPoolSize() const;
    
    // File operations
    UploadResult uploadDocument(const std::string& filePath, const std::string& caption = "");
    UploadResult uploadDocumentWithToken(
        const std::string& filePath,
        const std::string& botToken,
        const std::string& caption = "",
        const std::string& chatIdOverride = ""
    );
    
    // Download operations
    bool downloadFile(const std::string& fileId, const std::string& savePath, const std::string& botToken = "");
    std::string getFilePath(const std::string& fileId, const std::string& botToken = "");
    
    // Delete operations
    bool deleteMessage(int64_t messageId, const std::string& botToken = "");
    
    bool testConnection();
    
private:
    std::vector<std::string> m_botTokens;
    int m_currentBotIndex;
};

} // namespace TelegramCloud

#endif // TELEGRAMHANDLER_H
