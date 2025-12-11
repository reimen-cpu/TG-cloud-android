#include "logger.h"
#include <filesystem>
#include <iomanip>

#ifdef _WIN32
#include <windows.h>
#else
#include <sys/stat.h>
#endif

#ifdef TELEGRAMCLOUD_ANDROID
#include <android/log.h>
#define ANDROID_LOG_TAG "TelegramCloudCore"
#endif

namespace TelegramCloud {

Logger& Logger::instance() {
    static Logger instance;
    return instance;
}

Logger::Logger() : m_minLevel(LogLevel::LOG_DEBUG) {
    // Crear directorio de logs si no existe
    std::filesystem::create_directories("logs");
    
    // Generar nombre de archivo con fecha
    auto now = std::time(nullptr);
    auto tm = *std::localtime(&now);
    
    std::ostringstream oss;
    oss << "logs/telegram_cloud_"
        << std::put_time(&tm, "%Y%m%d_%H%M%S")
        << ".txt";
    
    m_logFilename = oss.str();
    
    m_logFile.open(m_logFilename, std::ios::out | std::ios::app);
    
    if (m_logFile.is_open()) {
        info("=======================================================");
        info("Telegram Cloud C++/wxWidgets - Log Session Started");
        info("=======================================================");
    }
}

Logger::~Logger() {
    if (m_logFile.is_open()) {
        info("=======================================================");
        info("Log Session Ended");
        info("=======================================================");
        m_logFile.close();
    }
}

void Logger::setLogFile(const std::string& filename) {
    std::lock_guard<std::mutex> lock(m_mutex);
    
    if (m_logFile.is_open()) {
        m_logFile.close();
    }
    
    m_logFilename = filename;
    m_logFile.open(filename, std::ios::out | std::ios::app);
}

void Logger::setLogLevel(LogLevel level) {
    m_minLevel = level;
}

std::string Logger::getCurrentTimestamp() const {
    auto now = std::time(nullptr);
    auto tm = *std::localtime(&now);
    
    std::ostringstream oss;
    oss << std::put_time(&tm, "%Y-%m-%d %H:%M:%S");
    return oss.str();
}

std::string Logger::levelToString(LogLevel level) const {
    switch (level) {
        case LogLevel::LOG_DEBUG: return "DEBUG";
        case LogLevel::LOG_INFO: return "INFO";
        case LogLevel::LOG_WARNING: return "WARNING";
        case LogLevel::LOG_ERROR: return "ERROR";
        case LogLevel::LOG_CRITICAL: return "CRITICAL";
    }
    return "UNKNOWN";
}

void Logger::log(LogLevel level, const std::string& message) {
    if (level < m_minLevel) {
        return;
    }
    
    std::lock_guard<std::mutex> lock(m_mutex);
    
    std::string timestamp = getCurrentTimestamp();
    std::string levelStr = levelToString(level);
    
    std::string logLine = "[" + timestamp + "] [" + levelStr + "] " + message;
    
#ifdef TELEGRAMCLOUD_ANDROID
    // En Android, usar __android_log_print para que aparezca en logcat
    android_LogPriority prio = ANDROID_LOG_DEBUG;
    switch (level) {
        case LogLevel::LOG_DEBUG: prio = ANDROID_LOG_DEBUG; break;
        case LogLevel::LOG_INFO: prio = ANDROID_LOG_INFO; break;
        case LogLevel::LOG_WARNING: prio = ANDROID_LOG_WARN; break;
        case LogLevel::LOG_ERROR: prio = ANDROID_LOG_ERROR; break;
        case LogLevel::LOG_CRITICAL: prio = ANDROID_LOG_FATAL; break;
    }
    __android_log_print(prio, ANDROID_LOG_TAG, "%s", logLine.c_str());
#endif
    
    // Escribir a archivo
    if (m_logFile.is_open()) {
        m_logFile << logLine << std::endl;
        m_logFile.flush(); // Flush inmediato para debugging
    }
    
#ifndef TELEGRAMCLOUD_ANDROID
    // Escribir también a consola (solo en desktop)
    if (level >= LogLevel::LOG_WARNING) {
        std::cerr << logLine << std::endl;
    } else {
        std::cout << logLine << std::endl;
    }
#endif
}

void Logger::debug(const std::string& message) {
    log(LogLevel::LOG_DEBUG, message);
}

void Logger::info(const std::string& message) {
    log(LogLevel::LOG_INFO, message);
}

void Logger::warning(const std::string& message) {
    log(LogLevel::LOG_WARNING, message);
}

void Logger::error(const std::string& message) {
    log(LogLevel::LOG_ERROR, message);
}

void Logger::critical(const std::string& message) {
    log(LogLevel::LOG_CRITICAL, message);
}

} // namespace TelegramCloud

