// Archivo único de strings ofuscados con ADVobfuscator
// IMPORTANTE: Solo este archivo debe incluir advobf_helper.h para evitar conflictos LNK2005

#include "obfuscated_strings.h"
#include "advobf_helper.h"

namespace ObfuscatedStrings {

// ============================================================================
// DATABASE - SQL Queries Críticas
// ============================================================================

const char* SQL_INSERT_FILE() {
    static auto obf = "INSERT INTO files (file_id, file_name, file_size, mime_type, category, message_id, telegram_file_id, uploader_bot_token, is_encrypted) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"_obf;
    static std::string str(obf);
    return str.c_str();
}

const char* SQL_ALTER_FILES() {
    static auto obf = "ALTER TABLE files ADD COLUMN is_encrypted INTEGER DEFAULT 0"_obf;
    static std::string str(obf);
    return str.c_str();
}

const char* SQL_ALTER_CHUNKED() {
    static auto obf = "ALTER TABLE chunked_files ADD COLUMN is_encrypted INTEGER DEFAULT 0"_obf;
    static std::string str(obf);
    return str.c_str();
}

const char* SQL_UPDATE_FILE() {
    static auto obf = "UPDATE files SET file_name = ?, file_size = ?, mime_type = ? WHERE file_id = ?"_obf;
    static std::string str(obf);
    return str.c_str();
}

const char* SQL_DELETE_FILE() {
    static auto obf = "DELETE FROM files WHERE file_id = ?"_obf;
    static std::string str(obf);
    return str.c_str();
}

const char* SQL_SELECT_ALL_FILES() {
    static auto obf = "SELECT * FROM files ORDER BY upload_date DESC"_obf;
    static std::string str(obf);
    return str.c_str();
}

const char* SQL_SELECT_FILE_BY_ID() {
    static auto obf = "SELECT * FROM files WHERE file_id = ?"_obf;
    static std::string str(obf);
    return str.c_str();
}

// ============================================================================
// CONFIG - Paths y Configuración
// ============================================================================

const char* DEFAULT_DB_PATH() {
    static auto obf = "./database/telegram_cloud.db"_obf;
    static std::string str(obf);
    return str.c_str();
}

const char* DEFAULT_LOG_PATH() {
    static auto obf = "./logs/"_obf;
    static std::string str(obf);
    return str.c_str();
}

const char* DEFAULT_API_HOST() {
    static auto obf = "127.0.0.1"_obf;
    static std::string str(obf);
    return str.c_str();
}

const char* ENV_FILE_NAME() {
    static auto obf = ".env"_obf;
    static std::string str(obf);
    return str.c_str();
}

const char* ENV_FILE_PARENT() {
    static auto obf = "../.env"_obf;
    static std::string str(obf);
    return str.c_str();
}

// ============================================================================
// ENVMANAGER - Mensajes de Error
// ============================================================================

const char* ERR_NO_CONFIG_FILE() {
    static auto obf = "No se encontró archivo de configuración encriptado"_obf;
    static std::string str(obf);
    return str.c_str();
}

const char* ERR_EMPTY_CONFIG() {
    static auto obf = "Archivo de configuración vacío"_obf;
    static std::string str(obf);
    return str.c_str();
}

const char* ERR_INVALID_FORMAT() {
    static auto obf = "Formato de archivo encriptado inválido"_obf;
    static std::string str(obf);
    return str.c_str();
}

const char* ERR_DECRYPTION_FAILED() {
    static auto obf = "Falló la desencriptación del archivo"_obf;
    static std::string str(obf);
    return str.c_str();
}

// ============================================================================
// TELEGRAM - Nombres de Campos Sensibles
// ============================================================================

const char* FIELD_BOT_TOKEN() {
    static auto obf = "bot_token"_obf;
    static std::string str(obf);
    return str.c_str();
}

const char* FIELD_TELEGRAM_FILE_ID() {
    static auto obf = "telegram_file_id"_obf;
    static std::string str(obf);
    return str.c_str();
}

const char* FIELD_UPLOADER_BOT_TOKEN() {
    static auto obf = "uploader_bot_token"_obf;
    static std::string str(obf);
    return str.c_str();
}

const char* FIELD_API_KEY() {
    static auto obf = "api_key"_obf;
    static std::string str(obf);
    return str.c_str();
}

// ============================================================================
// LOGGER - Mensajes de Log Críticos
// ============================================================================

const char* LOG_DB_INITIALIZED() {
    static auto obf = "Database initialized successfully"_obf;
    static std::string str(obf);
    return str.c_str();
}

const char* LOG_DB_ERROR() {
    static auto obf = "Database error occurred"_obf;
    static std::string str(obf);
    return str.c_str();
}

const char* LOG_AUTH_SUCCESS() {
    static auto obf = "Authentication successful"_obf;
    static std::string str(obf);
    return str.c_str();
}

const char* LOG_AUTH_FAILED() {
    static auto obf = "Authentication failed"_obf;
    static std::string str(obf);
    return str.c_str();
}

// ============================================================================
// Embedded secrets (highly obfuscated at build time by ADVobfuscator)
// ============================================================================

const char* LINK_SECRET() {
    // Clave embebida usada para endurecer la derivación de claves de archivos .link
    // Mantenerla solo aquí para que ADVobfuscator la fragmente y oculte en binario.
    static auto obf = "f4b1c9a2-7d54-4b0f-9f31-3c7a1e29c6d8::L1NK-K3Y-v1"_obf;
    static std::string str(obf);
    return str.c_str();
}

} // namespace ObfuscatedStrings





