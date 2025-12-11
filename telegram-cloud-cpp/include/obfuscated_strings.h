#ifndef OBFUSCATED_STRINGS_H
#define OBFUSCATED_STRINGS_H

#include <string>

// Strings ofuscados centralizados
// IMPORTANTE: Solo obfuscated_strings.cpp incluye advobf_helper.h

namespace ObfuscatedStrings {

// Database SQL Queries
const char* SQL_INSERT_FILE();
const char* SQL_ALTER_FILES();
const char* SQL_ALTER_CHUNKED();
const char* SQL_UPDATE_FILE();
const char* SQL_DELETE_FILE();
const char* SQL_SELECT_ALL_FILES();
const char* SQL_SELECT_FILE_BY_ID();

// Config Paths
const char* DEFAULT_DB_PATH();
const char* DEFAULT_LOG_PATH();
const char* DEFAULT_API_HOST();
const char* ENV_FILE_NAME();
const char* ENV_FILE_PARENT();

// EnvManager Errors
const char* ERR_NO_CONFIG_FILE();
const char* ERR_EMPTY_CONFIG();
const char* ERR_INVALID_FORMAT();
const char* ERR_DECRYPTION_FAILED();

// Telegram Fields
const char* FIELD_BOT_TOKEN();
const char* FIELD_TELEGRAM_FILE_ID();
const char* FIELD_UPLOADER_BOT_TOKEN();
const char* FIELD_API_KEY();

// Logger Messages
const char* LOG_DB_INITIALIZED();
const char* LOG_DB_ERROR();
const char* LOG_AUTH_SUCCESS();
const char* LOG_AUTH_FAILED();

// Embedded secrets
const char* LINK_SECRET();

} // namespace ObfuscatedStrings

#endif // OBFUSCATED_STRINGS_H





