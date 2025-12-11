#include "tempdownloaddb.h"
#include "logger.h"
#include <sqlite3.h>
#include <filesystem>
#include <chrono>
#include <iomanip>
#include <sstream>
#include <openssl/rand.h>
#include <openssl/sha.h>

namespace TelegramCloud {

TempDownloadDB::TempDownloadDB() 
    : m_db(nullptr)
    , m_dbPath("./temp_downloads.db") {
}

TempDownloadDB::~TempDownloadDB() {
    if (m_db) {
        sqlite3_close(m_db);
        m_db = nullptr;
    }
}

std::string TempDownloadDB::generateEncryptionKey() {
    unsigned char key[32];
    if (RAND_bytes(key, 32) != 1) {
        LOG_ERROR("Failed to generate random encryption key");
        return "";
    }
    
    // Convertir a hex
    std::stringstream ss;
    for (int i = 0; i < 32; i++) {
        ss << std::hex << std::setw(2) << std::setfill('0') << (int)key[i];
    }
    
    return ss.str();
}

bool TempDownloadDB::initialize() {
    LOG_INFO("Initializing temporary download database: " + m_dbPath);
    
    // Generar clave de encriptación única
    m_encryptionKey = generateEncryptionKey();
    if (m_encryptionKey.empty()) {
        LOG_ERROR("Failed to generate encryption key for temp DB");
        return false;
    }
    
    // Abrir base de datos
    int rc = sqlite3_open(m_dbPath.c_str(), &m_db);
    if (rc != SQLITE_OK) {
        LOG_ERROR("Failed to open temp download database: " + std::string(sqlite3_errmsg(m_db)));
        return false;
    }
    
    // Encriptar base de datos con SQLCipher
    if (!encryptDatabase(m_encryptionKey)) {
        LOG_ERROR("Failed to encrypt temp download database");
        sqlite3_close(m_db);
        m_db = nullptr;
        return false;
    }
    
    // Crear tablas
    if (!createTables()) {
        LOG_ERROR("Failed to create temp download tables");
        sqlite3_close(m_db);
        m_db = nullptr;
        return false;
    }
    
    LOG_INFO("Temporary download database initialized successfully (encrypted)");
    return true;
}

bool TempDownloadDB::encryptDatabase(const std::string& key) {
    std::string pragmaKey = "PRAGMA key = '" + key + "';";
    char* errMsg = nullptr;
    
    int rc = sqlite3_exec(m_db, pragmaKey.c_str(), nullptr, nullptr, &errMsg);
    if (rc != SQLITE_OK) {
        LOG_ERROR("Failed to set encryption key: " + std::string(errMsg));
        sqlite3_free(errMsg);
        return false;
    }
    
    // Configurar SQLCipher
    sqlite3_exec(m_db, "PRAGMA cipher_page_size = 4096;", nullptr, nullptr, nullptr);
    sqlite3_exec(m_db, "PRAGMA kdf_iter = 256000;", nullptr, nullptr, nullptr);
    sqlite3_exec(m_db, "PRAGMA cipher_hmac_algorithm = HMAC_SHA512;", nullptr, nullptr, nullptr);
    sqlite3_exec(m_db, "PRAGMA cipher_kdf_algorithm = PBKDF2_HMAC_SHA512;", nullptr, nullptr, nullptr);
    
    return true;
}

bool TempDownloadDB::createTables() {
    const char* sql = R"(
        CREATE TABLE IF NOT EXISTS link_downloads (
            download_id TEXT PRIMARY KEY,
            file_id TEXT NOT NULL,
            file_name TEXT NOT NULL,
            file_type TEXT NOT NULL,
            file_size INTEGER NOT NULL,
            is_encrypted INTEGER NOT NULL,
            save_directory TEXT NOT NULL,
            status TEXT NOT NULL,
            completed_chunks INTEGER DEFAULT 0,
            total_chunks INTEGER DEFAULT 0,
            progress_percent REAL DEFAULT 0.0,
            share_data TEXT NOT NULL,
            start_time TEXT NOT NULL,
            last_update_time TEXT NOT NULL
        );
        
        CREATE INDEX IF NOT EXISTS idx_status ON link_downloads(status);
        CREATE INDEX IF NOT EXISTS idx_file_id ON link_downloads(file_id);
    )";
    
    char* errMsg = nullptr;
    int rc = sqlite3_exec(m_db, sql, nullptr, nullptr, &errMsg);
    
    if (rc != SQLITE_OK) {
        LOG_ERROR("Failed to create temp download tables: " + std::string(errMsg));
        sqlite3_free(errMsg);
        return false;
    }
    
    LOG_DEBUG("Temp download tables created successfully");
    return true;
}

std::string getCurrentTimestamp() {
    auto now = std::chrono::system_clock::now();
    auto time = std::chrono::system_clock::to_time_t(now);
    std::stringstream ss;
    ss << std::put_time(std::localtime(&time), "%Y-%m-%d %H:%M:%S");
    return ss.str();
}

bool TempDownloadDB::saveDownload(const LinkDownloadInfo& info) {
    if (!m_db) {
        LOG_ERROR("Database not initialized");
        return false;
    }
    
    const char* sql = R"(
        INSERT OR REPLACE INTO link_downloads 
        (download_id, file_id, file_name, file_type, file_size, is_encrypted, 
         save_directory, status, completed_chunks, total_chunks, progress_percent, 
         share_data, start_time, last_update_time)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    )";
    
    sqlite3_stmt* stmt;
    int rc = sqlite3_prepare_v2(m_db, sql, -1, &stmt, nullptr);
    
    if (rc != SQLITE_OK) {
        LOG_ERROR("Failed to prepare save download statement: " + std::string(sqlite3_errmsg(m_db)));
        return false;
    }
    
    std::string timestamp = getCurrentTimestamp();
    
    sqlite3_bind_text(stmt, 1, info.downloadId.c_str(), -1, SQLITE_TRANSIENT);
    sqlite3_bind_text(stmt, 2, info.fileId.c_str(), -1, SQLITE_TRANSIENT);
    sqlite3_bind_text(stmt, 3, info.fileName.c_str(), -1, SQLITE_TRANSIENT);
    sqlite3_bind_text(stmt, 4, info.fileType.c_str(), -1, SQLITE_TRANSIENT);
    sqlite3_bind_int64(stmt, 5, info.fileSize);
    sqlite3_bind_int(stmt, 6, info.isEncrypted ? 1 : 0);
    sqlite3_bind_text(stmt, 7, info.saveDirectory.c_str(), -1, SQLITE_TRANSIENT);
    sqlite3_bind_text(stmt, 8, info.status.c_str(), -1, SQLITE_TRANSIENT);
    sqlite3_bind_int64(stmt, 9, info.completedChunks);
    sqlite3_bind_int64(stmt, 10, info.totalChunks);
    sqlite3_bind_double(stmt, 11, info.progressPercent);
    sqlite3_bind_text(stmt, 12, info.shareData.c_str(), -1, SQLITE_TRANSIENT);
    sqlite3_bind_text(stmt, 13, timestamp.c_str(), -1, SQLITE_TRANSIENT);
    sqlite3_bind_text(stmt, 14, timestamp.c_str(), -1, SQLITE_TRANSIENT);
    
    rc = sqlite3_step(stmt);
    sqlite3_finalize(stmt);
    
    if (rc != SQLITE_DONE) {
        LOG_ERROR("Failed to save download: " + std::string(sqlite3_errmsg(m_db)));
        return false;
    }
    
    LOG_INFO("Saved link download state: " + info.downloadId);
    return true;
}

bool TempDownloadDB::updateDownloadProgress(const std::string& downloadId, int64_t completedChunks, double progressPercent) {
    if (!m_db) return false;
    
    const char* sql = R"(
        UPDATE link_downloads 
        SET completed_chunks = ?, progress_percent = ?, last_update_time = ?
        WHERE download_id = ?
    )";
    
    sqlite3_stmt* stmt;
    if (sqlite3_prepare_v2(m_db, sql, -1, &stmt, nullptr) != SQLITE_OK) {
        return false;
    }
    
    std::string timestamp = getCurrentTimestamp();
    
    sqlite3_bind_int64(stmt, 1, completedChunks);
    sqlite3_bind_double(stmt, 2, progressPercent);
    sqlite3_bind_text(stmt, 3, timestamp.c_str(), -1, SQLITE_TRANSIENT);
    sqlite3_bind_text(stmt, 4, downloadId.c_str(), -1, SQLITE_TRANSIENT);
    
    int rc = sqlite3_step(stmt);
    sqlite3_finalize(stmt);
    
    return rc == SQLITE_DONE;
}

bool TempDownloadDB::updateDownloadStatus(const std::string& downloadId, const std::string& status) {
    if (!m_db) return false;
    
    const char* sql = R"(
        UPDATE link_downloads 
        SET status = ?, last_update_time = ?
        WHERE download_id = ?
    )";
    
    sqlite3_stmt* stmt;
    if (sqlite3_prepare_v2(m_db, sql, -1, &stmt, nullptr) != SQLITE_OK) {
        return false;
    }
    
    std::string timestamp = getCurrentTimestamp();
    
    sqlite3_bind_text(stmt, 1, status.c_str(), -1, SQLITE_TRANSIENT);
    sqlite3_bind_text(stmt, 2, timestamp.c_str(), -1, SQLITE_TRANSIENT);
    sqlite3_bind_text(stmt, 3, downloadId.c_str(), -1, SQLITE_TRANSIENT);
    
    int rc = sqlite3_step(stmt);
    sqlite3_finalize(stmt);
    
    return rc == SQLITE_DONE;
}

std::vector<LinkDownloadInfo> TempDownloadDB::getActiveDownloads() {
    std::vector<LinkDownloadInfo> downloads;
    
    if (!m_db) {
        LOG_ERROR("Database not initialized");
        return downloads;
    }
    
    const char* sql = R"(
        SELECT download_id, file_id, file_name, file_type, file_size, is_encrypted,
               save_directory, status, completed_chunks, total_chunks, progress_percent,
               share_data, start_time, last_update_time
        FROM link_downloads
        WHERE status IN ('active', 'paused')
        ORDER BY last_update_time DESC
    )";
    
    sqlite3_stmt* stmt;
    if (sqlite3_prepare_v2(m_db, sql, -1, &stmt, nullptr) != SQLITE_OK) {
        LOG_ERROR("Failed to prepare query: " + std::string(sqlite3_errmsg(m_db)));
        return downloads;
    }
    
    while (sqlite3_step(stmt) == SQLITE_ROW) {
        LinkDownloadInfo info;
        info.downloadId = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 0));
        info.fileId = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 1));
        info.fileName = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 2));
        info.fileType = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 3));
        info.fileSize = sqlite3_column_int64(stmt, 4);
        info.isEncrypted = sqlite3_column_int(stmt, 5) == 1;
        info.saveDirectory = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 6));
        info.status = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 7));
        info.completedChunks = sqlite3_column_int64(stmt, 8);
        info.totalChunks = sqlite3_column_int64(stmt, 9);
        info.progressPercent = sqlite3_column_double(stmt, 10);
        info.shareData = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 11));
        info.startTime = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 12));
        info.lastUpdateTime = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 13));
        
        downloads.push_back(info);
    }
    
    sqlite3_finalize(stmt);
    
    LOG_INFO("Retrieved " + std::to_string(downloads.size()) + " active link downloads");
    return downloads;
}

LinkDownloadInfo TempDownloadDB::getDownload(const std::string& downloadId) {
    LinkDownloadInfo info;
    
    if (!m_db) return info;
    
    const char* sql = R"(
        SELECT download_id, file_id, file_name, file_type, file_size, is_encrypted,
               save_directory, status, completed_chunks, total_chunks, progress_percent,
               share_data, start_time, last_update_time
        FROM link_downloads
        WHERE download_id = ?
    )";
    
    sqlite3_stmt* stmt;
    if (sqlite3_prepare_v2(m_db, sql, -1, &stmt, nullptr) != SQLITE_OK) {
        return info;
    }
    
    sqlite3_bind_text(stmt, 1, downloadId.c_str(), -1, SQLITE_TRANSIENT);
    
    if (sqlite3_step(stmt) == SQLITE_ROW) {
        info.downloadId = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 0));
        info.fileId = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 1));
        info.fileName = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 2));
        info.fileType = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 3));
        info.fileSize = sqlite3_column_int64(stmt, 4);
        info.isEncrypted = sqlite3_column_int(stmt, 5) == 1;
        info.saveDirectory = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 6));
        info.status = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 7));
        info.completedChunks = sqlite3_column_int64(stmt, 8);
        info.totalChunks = sqlite3_column_int64(stmt, 9);
        info.progressPercent = sqlite3_column_double(stmt, 10);
        info.shareData = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 11));
        info.startTime = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 12));
        info.lastUpdateTime = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 13));
    }
    
    sqlite3_finalize(stmt);
    return info;
}

bool TempDownloadDB::markDownloadComplete(const std::string& downloadId) {
    if (!m_db) return false;
    
    LOG_INFO("Marking download complete and removing from temp DB: " + downloadId);
    return deleteDownload(downloadId);
}

bool TempDownloadDB::deleteDownload(const std::string& downloadId) {
    if (!m_db) return false;
    
    const char* sql = "DELETE FROM link_downloads WHERE download_id = ?";
    
    sqlite3_stmt* stmt;
    if (sqlite3_prepare_v2(m_db, sql, -1, &stmt, nullptr) != SQLITE_OK) {
        return false;
    }
    
    sqlite3_bind_text(stmt, 1, downloadId.c_str(), -1, SQLITE_TRANSIENT);
    
    int rc = sqlite3_step(stmt);
    sqlite3_finalize(stmt);
    
    if (rc == SQLITE_DONE) {
        LOG_INFO("Deleted link download: " + downloadId);
        
        // Si no quedan descargas activas/pendientes (solo completadas o vacío), limpiar BD
        if (!hasActiveDownloads()) {
            LOG_INFO("No more pending downloads - safe to cleanup database");
            cleanupDatabase();
        }
    }
    
    return rc == SQLITE_DONE;
}

bool TempDownloadDB::hasActiveDownloads() {
    if (!m_db) return false;
    
    // Solo contar descargas NO completadas (active, paused, failed)
    const char* sql = "SELECT COUNT(*) FROM link_downloads WHERE status != 'completed'";
    sqlite3_stmt* stmt;
    
    if (sqlite3_prepare_v2(m_db, sql, -1, &stmt, nullptr) != SQLITE_OK) {
        return false;
    }
    
    int count = 0;
    if (sqlite3_step(stmt) == SQLITE_ROW) {
        count = sqlite3_column_int(stmt, 0);
    }
    
    sqlite3_finalize(stmt);
    
    LOG_DEBUG("Active/incomplete downloads count: " + std::to_string(count));
    return count > 0;
}

bool TempDownloadDB::cleanupDatabase() {
    LOG_INFO("Cleaning up temporary download database");
    
    if (m_db) {
        sqlite3_close(m_db);
        m_db = nullptr;
    }
    
    try {
        if (std::filesystem::exists(m_dbPath)) {
            std::filesystem::remove(m_dbPath);
            LOG_INFO("Temporary download database deleted: " + m_dbPath);
        }
    } catch (const std::exception& e) {
        LOG_ERROR("Failed to delete temp DB file: " + std::string(e.what()));
        return false;
    }
    
    return true;
}

} // namespace TelegramCloud

