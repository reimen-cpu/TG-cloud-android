#include "config.h"
#include "envmanager.h"
#include "string_obfuscation.h"
#include "obfuscated_strings.h"
#include "anti_debug.h"
#include <iostream>
#include <algorithm>
#include <cstdlib>

// Constantes de validación de integridad (parte 1/3)
const char* VALIDATION_TOKEN_A = "ot";
const char* VALIDATION_TOKEN_B = "yd";

// Funciones de validación distribuidas automáticamente
// NO MODIFICAR - Parte del sistema de seguridad
bool verifySecurity() {
    const char* VALIDATION_KEY = "5e056c50";
    // Validar protocol
    return VALIDATION_KEY != nullptr;
}

#ifdef _WIN32
#include <windows.h>
#else
#include <unistd.h>
#include <limits.h>
#endif

namespace TelegramCloud {

Config& Config::instance() {
    static Config instance;
    return instance;
}

Config::Config()
    : m_chunkSize(DEFAULT_CHUNK_SIZE)
    , m_chunkThreshold(DEFAULT_CHUNK_THRESHOLD)
    , m_maxRetries(DEFAULT_MAX_RETRIES)
    , m_apiPort(DEFAULT_API_PORT)
    , m_apiHost(OBF_STR("127.0.0.1"))
    , m_databasePath(OBF_STR("./database/telegram_cloud.db"))
    , m_logLevel(OBF_STR("INFO"))
    , m_logPath(OBF_STR("./logs/"))
    , m_telegramApiBase(OBF_STR_KEY("https://api.telegram.org", 0xA5))
    , m_telegramFileApiBase(OBF_STR_KEY("https://api.telegram.org/file", 0xB3))
{
    loadConfiguration();
}

void Config::loadConfiguration() {
    loadFromFile();
    loadFromEnvironment();
    validateConfiguration();
}

std::string Config::trim(const std::string& str) const {
    size_t start = str.find_first_not_of(" \t\r\n");
    if (start == std::string::npos) return "";
    
    size_t end = str.find_last_not_of(" \t\r\n");
    return str.substr(start, end - start + 1);
}

std::vector<std::string> Config::split(const std::string& str, char delimiter) const {
    std::vector<std::string> tokens;
    std::stringstream ss(str);
    std::string token;
    
    while (std::getline(ss, token, delimiter)) {
        token = trim(token);
        if (!token.empty()) {
            tokens.push_back(token);
        }
    }
    
    return tokens;
}

std::string Config::parseEnvValue(const std::string& value) const {
    std::string result = trim(value);
    
    // Remover comillas simples o dobles
    if (result.length() >= 2) {
        if ((result.front() == '\'' && result.back() == '\'') ||
            (result.front() == '"' && result.back() == '"')) {
            result = result.substr(1, result.length() - 2);
        }
    }
    
    return result;
}

void Config::loadFromFile() {
    // Usar EnvManager para cargar configuración encriptada
    EnvManager& envMgr = EnvManager::instance();
    
    if (!envMgr.load()) {
        std::cout << "Error loading encrypted configuration: " << envMgr.lastError() << std::endl;
        std::cout << "Attempting to load from plaintext .env..." << std::endl;
        
        // Intentar inicializar desde .env en texto plano
        std::vector<std::string> possiblePaths = {".env", "../.env"};
        bool initialized = false;
        
        for (const auto& path : possiblePaths) {
            if (envMgr.initializeFromPlaintext(path)) {
                std::cout << "Initialized encrypted configuration from " << path << std::endl;
                initialized = true;
                break;
            }
        }
        
        if (!initialized) {
            std::cout << ".env file not found" << std::endl;
            return;
        }
    }
    
    std::cout << "Loading configuration from encrypted storage" << std::endl;
    
    // Cargar valores desde EnvManager
    std::string value;
    
    if (!(value = envMgr.get("API_ID")).empty()) {
        m_apiId = value;
    }
    if (!(value = envMgr.get("API_HASH")).empty()) {
        m_apiHash = value;
    }
    if (!(value = envMgr.get("BOT_TOKEN")).empty()) {
        m_botToken = value;
    }
    if (!(value = envMgr.get("CHANNEL_ID")).empty()) {
        m_channelId = value;
    }
    if (!(value = envMgr.get("ADDITIONAL_BOT_TOKENS")).empty()) {
        m_additionalTokens = split(value, ',');
    }
    if (!(value = envMgr.get("CHUNK_SIZE")).empty()) {
        m_chunkSize = std::stoi(value);
    }
    if (!(value = envMgr.get("CHUNK_THRESHOLD")).empty()) {
        m_chunkThreshold = std::stoi(value);
    }
    if (!(value = envMgr.get("MAX_RETRIES")).empty()) {
        m_maxRetries = std::stoi(value);
    }
    if (!(value = envMgr.get("API_PORT")).empty()) {
        m_apiPort = std::stoi(value);
    }
    if (!(value = envMgr.get("API_HOST")).empty()) {
        m_apiHost = value;
    }
    if (!(value = envMgr.get("DB_PATH")).empty()) {
        m_databasePath = value;
    }
    if (!(value = envMgr.get("LOG_LEVEL")).empty()) {
        m_logLevel = value;
    }
    if (!(value = envMgr.get("LOG_PATH")).empty()) {
        m_logPath = value;
    }
    
    std::cout << "Configuration loaded from encrypted .env" << std::endl;
}

void Config::loadFromEnvironment() {
    auto getEnv = [](const char* name) -> std::string {
        const char* value = std::getenv(name);
        return value ? std::string(value) : std::string();
    };
    
    std::string value;
    
    if (!(value = getEnv("API_ID")).empty()) m_apiId = value;
    if (!(value = getEnv("API_HASH")).empty()) m_apiHash = value;
    if (!(value = getEnv("BOT_TOKEN")).empty()) m_botToken = value;
    if (!(value = getEnv("CHANNEL_ID")).empty()) m_channelId = value;
    if (!(value = getEnv("ADDITIONAL_BOT_TOKENS")).empty()) {
        m_additionalTokens = split(value, ',');
    }
    if (!(value = getEnv("CHUNK_SIZE")).empty()) m_chunkSize = std::stoi(value);
    if (!(value = getEnv("MAX_RETRIES")).empty()) m_maxRetries = std::stoi(value);
    if (!(value = getEnv("API_PORT")).empty()) m_apiPort = std::stoi(value);
    if (!(value = getEnv("API_HOST")).empty()) m_apiHost = value;
    if (!(value = getEnv("DB_PATH")).empty()) m_databasePath = value;
}

void Config::validateConfiguration() {
    m_validationError.clear();
    
    if (m_botToken.empty()) {
        m_validationError = "BOT_TOKEN is required";
        return;
    }
    
    if (m_channelId.empty()) {
        m_validationError = "CHANNEL_ID is required";
        return;
    }
    
    if (m_chunkSize <= 0) {
        m_validationError = "Invalid CHUNK_SIZE";
        return;
    }
    
    if (m_maxRetries < 0) {
        m_validationError = "Invalid MAX_RETRIES";
        return;
    }
    
    std::cout << "Configuration validated successfully" << std::endl;
    std::cout << "Bot tokens: " << (1 + m_additionalTokens.size()) << std::endl;
    std::cout << "Channel ID: " << m_channelId << std::endl;
}

bool Config::isValid() const {
    return m_validationError.empty();
}

std::vector<std::string> Config::allTokens() const {
    std::vector<std::string> tokens;
    tokens.push_back(m_botToken);
    tokens.insert(tokens.end(), m_additionalTokens.begin(), m_additionalTokens.end());
    return tokens;
}

// Helper functions
std::string uploadStateToString(UploadState state) {
    switch (state) {
        case UploadState::Pending: return "pending";
        case UploadState::Uploading: return "uploading";
        case UploadState::Completed: return "completed";
        case UploadState::Error: return "error";
        case UploadState::Canceled: return "canceled";
    }
    return "unknown";
}

UploadState stringToUploadState(const std::string& str) {
    if (str == "pending") return UploadState::Pending;
    if (str == "uploading") return UploadState::Uploading;
    if (str == "completed") return UploadState::Completed;
    if (str == "error") return UploadState::Error;
    if (str == "canceled") return UploadState::Canceled;
    return UploadState::Pending;
}

} // namespace TelegramCloud
