#ifndef LOGGER_H
#define LOGGER_H

#include <string>
#include <fstream>
#include <mutex>
#include <ctime>
#include <sstream>
#include <iostream>

namespace TelegramCloud {

enum class LogLevel {
    LOG_DEBUG,
    LOG_INFO,
    LOG_WARNING,
    LOG_ERROR,
    LOG_CRITICAL
};

class Logger {
public:
    static Logger& instance();
    
    void log(LogLevel level, const std::string& message);
    void debug(const std::string& message);
    void info(const std::string& message);
    void warning(const std::string& message);
    void error(const std::string& message);
    void critical(const std::string& message);
    
    void setLogFile(const std::string& filename);
    void setLogLevel(LogLevel level);
    
private:
    Logger();
    ~Logger();
    Logger(const Logger&) = delete;
    Logger& operator=(const Logger&) = delete;
    
    std::string getCurrentTimestamp() const;
    std::string levelToString(LogLevel level) const;
    
    std::ofstream m_logFile;
    LogLevel m_minLevel;
    std::mutex m_mutex;
    std::string m_logFilename;
};

// Conmutador de logs en código fuente
// Cambia TELEGRAM_CLOUD_LOGS de TELEGRAM_CLOUD_LOGS_OFF a TELEGRAM_CLOUD_LOGS_ON para activar
#ifndef TELEGRAM_CLOUD_LOGS
#define TELEGRAM_CLOUD_LOGS_ON 1
#define TELEGRAM_CLOUD_LOGS_OFF 0
#define TELEGRAM_CLOUD_LOGS TELEGRAM_CLOUD_LOGS_OFF
#endif

// Macros para facilitar el uso (no-op si los logs están OFF)
#if TELEGRAM_CLOUD_LOGS
#define LOG_DEBUG(msg) TelegramCloud::Logger::instance().debug(msg)
#define LOG_INFO(msg) TelegramCloud::Logger::instance().info(msg)
#define LOG_WARNING(msg) TelegramCloud::Logger::instance().warning(msg)
#define LOG_ERROR(msg) TelegramCloud::Logger::instance().error(msg)
#define LOG_CRITICAL(msg) TelegramCloud::Logger::instance().critical(msg)
#else
#define LOG_DEBUG(msg) do { (void)0; } while(0)
#define LOG_INFO(msg) do { (void)0; } while(0)
#define LOG_WARNING(msg) do { (void)0; } while(0)
#define LOG_ERROR(msg) do { (void)0; } while(0)
#define LOG_CRITICAL(msg) do { (void)0; } while(0)
#endif

} // namespace TelegramCloud

#endif // LOGGER_H

