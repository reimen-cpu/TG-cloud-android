// Version Android sin ofuscacion (advobfuscator no disponible)
// Este archivo reemplaza obfuscated_strings.cpp en builds Android

#ifdef TELEGRAMCLOUD_ANDROID

#include "obfuscated_strings.h"

namespace ObfuscatedStrings {

// ============================================================================
// DATABASE - SQL Queries
// ============================================================================

const char* SQL_INSERT_FILE() {
    return "INSERT INTO files (file_id, file_name, file_size, mime_type, category, message_id, telegram_file_id, uploader_bot_token, is_encrypted) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
}

const char* SQL_ALTER_FILES() {
    return "ALTER TABLE files ADD COLUMN is_encrypted INTEGER DEFAULT 0";
}

const char* SQL_ALTER_CHUNKED() {
    return "ALTER TABLE chunked_files ADD COLUMN is_encrypted INTEGER DEFAULT 0";
}

const char* SQL_UPDATE_FILE() {
    return "UPDATE files SET file_name = ?, file_size = ?, mime_type = ? WHERE file_id = ?";
}

const char* SQL_DELETE_FILE() {
    return "DELETE FROM files WHERE file_id = ?";
}

const char* SQL_SELECT_ALL_FILES() {
    return "SELECT * FROM files ORDER BY upload_date DESC";
}

const char* SQL_SELECT_FILE_BY_ID() {
    return "SELECT * FROM files WHERE file_id = ?";
}

// ============================================================================
// CONFIG - Paths
// ============================================================================

const char* DEFAULT_DB_PATH() {
    return "./database/telegram_cloud.db";
}

const char* DEFAULT_LOG_PATH() {
    return "./logs/";
}

const char* DEFAULT_API_HOST() {
    return "127.0.0.1";
}

const char* ENV_FILE_NAME() {
    return ".env";
}

const char* ENV_FILE_PARENT() {
    return "telegram-cloud-cpp";
}

// ============================================================================
// ENVMANAGER - Errors
// ============================================================================

const char* ERR_NO_CONFIG_FILE() {
    return "Configuration file not found";
}

const char* ERR_EMPTY_CONFIG() {
    return "Configuration file is empty";
}

const char* ERR_INVALID_FORMAT() {
    return "Invalid configuration format";
}

const char* ERR_DECRYPTION_FAILED() {
    return "Decryption failed";
}

// ============================================================================
// TELEGRAM - Fields
// ============================================================================

const char* FIELD_BOT_TOKEN() {
    return "bot_token";
}

const char* FIELD_TELEGRAM_FILE_ID() {
    return "telegram_file_id";
}

const char* FIELD_UPLOADER_BOT_TOKEN() {
    return "uploader_bot_token";
}

const char* FIELD_API_KEY() {
    return "api_key";
}

// ============================================================================
// LOGGER - Messages
// ============================================================================

const char* LOG_DB_INITIALIZED() {
    return "Database initialized successfully";
}

const char* LOG_DB_ERROR() {
    return "Database error";
}

const char* LOG_AUTH_SUCCESS() {
    return "Authentication successful";
}

const char* LOG_AUTH_FAILED() {
    return "Authentication failed";
}

// ============================================================================
// SECRETS
// ============================================================================

const char* LINK_SECRET() {
    return "TelegramCloudSecretKey2024";
}

} // namespace ObfuscatedStrings

#endif // TELEGRAMCLOUD_ANDROID


