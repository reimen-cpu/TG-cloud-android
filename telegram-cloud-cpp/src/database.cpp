#include "database.h"
#include "envmanager.h"
#include "logger.h"
#include "string_obfuscation.h"
#include "obfuscated_strings.h"
#include "anti_debug.h"
#include <iostream>
#include <filesystem>

namespace TelegramCloud {

Database::Database() : m_db(nullptr), m_isEncrypted(false) {
}

Database::~Database() {
    close();
}

bool Database::initialize(const std::string& dbPath) {
    ANTI_DEBUG_CHECK(); // Verificar debugger al iniciar DB
    
    m_dbPath = dbPath;
    
    LOG_INFO("Initializing database at: " + dbPath);
    
    // Crear directorio si no existe
    try {
        std::filesystem::path dbFilePath(dbPath);
        std::filesystem::path dbDir = dbFilePath.parent_path();
        
        if (!dbDir.empty() && !std::filesystem::exists(dbDir)) {
            LOG_INFO("Creating database directory: " + dbDir.string());
            std::filesystem::create_directories(dbDir);
        }
    } catch (const std::exception& e) {
        LOG_ERROR("Failed to create database directory: " + std::string(e.what()));
        return false;
    }
    
    int rc = sqlite3_open(dbPath.c_str(), &m_db);
    if (rc != SQLITE_OK) {
        std::string error = "Failed to open database: " + getLastError();
        LOG_ERROR(error);
        std::cerr << error << std::endl;
        return false;
    }
    
    LOG_INFO("Database opened successfully: " + dbPath);
    
    // Configurar encriptación SQLCipher
    if (!configureEncryption()) {
        LOG_ERROR("Failed to configure database encryption");
        close();
        return false;
    }
    
    // Habilitar foreign keys
    const char* pragmaSQL = "PRAGMA foreign_keys = ON";
    char* errMsg = nullptr;
    rc = sqlite3_exec(m_db, pragmaSQL, nullptr, nullptr, &errMsg);
    if (rc != SQLITE_OK) {
        LOG_WARNING("Failed to enable foreign keys: " + std::string(errMsg));
        sqlite3_free(errMsg);
    } else {
        LOG_DEBUG("Foreign keys enabled");
    }
    
    bool tablesCreated = setupTables();
    if (tablesCreated) {
        LOG_INFO("Database tables created successfully");
    } else {
        LOG_ERROR("Failed to create database tables");
    }
    
    return tablesCreated;
}

void Database::close() {
    if (m_db) {
        sqlite3_close(m_db);
        m_db = nullptr;
    }
}

bool Database::setupTables() {
    LOG_INFO(OBF_STR("Creating database tables..."));
    
    std::string createFilesTable = 
        OBF_STR_KEY("CREATE TABLE IF NOT EXISTS ", 0x45) +
        OBF_STR_KEY("files", 0x67) +
        OBF_STR_KEY(" (id INTEGER PRIMARY KEY AUTOINCREMENT,", 0x89) +
        OBF_STR_KEY("file_id TEXT NOT NULL UNIQUE,", 0x3C) +
        OBF_STR_KEY("file_name TEXT NOT NULL,", 0x5D) +
        OBF_STR_KEY("file_size INTEGER NOT NULL,", 0x7E) +
        OBF_STR_KEY("mime_type TEXT,category TEXT,", 0x2F) +
        OBF_STR_KEY("upload_date TEXT DEFAULT CURRENT_TIMESTAMP,", 0xA1) +
        OBF_STR_KEY("message_id INTEGER,telegram_file_id TEXT,", 0xB2) +
        OBF_STR_KEY("uploader_bot_token TEXT,", 0xC3) +
        OBF_STR_KEY("is_encrypted INTEGER DEFAULT 0);", 0xD4);
    
    std::string createChunkedFilesTable = 
        OBF_STR_KEY("CREATE TABLE IF NOT EXISTS ", 0x55) +
        OBF_STR_KEY("chunked_files", 0x77) +
        OBF_STR_KEY(" (file_id TEXT PRIMARY KEY,", 0x99) +
        OBF_STR_KEY("original_filename TEXT NOT NULL,mime_type TEXT,", 0x4C) +
        OBF_STR_KEY("total_size INTEGER NOT NULL,total_chunks INTEGER NOT NULL,", 0x6D) +
        OBF_STR_KEY("completed_chunks INTEGER DEFAULT 0,", 0x8E) +
        OBF_STR_KEY("upload_started TEXT DEFAULT CURRENT_TIMESTAMP,", 0x3F) +
        OBF_STR_KEY("last_update TEXT DEFAULT CURRENT_TIMESTAMP,", 0xA2) +
        OBF_STR_KEY("status TEXT DEFAULT 'pending',", 0xB3) +
        OBF_STR_KEY("final_telegram_file_id TEXT,error_message TEXT,", 0xC4) +
        OBF_STR_KEY("original_file_hash TEXT,is_encrypted INTEGER DEFAULT 0);", 0xD5);
    
    std::string createFileChunksTable = 
        OBF_STR_KEY("CREATE TABLE IF NOT EXISTS ", 0x65) +
        OBF_STR_KEY("file_chunks", 0x87) +
        OBF_STR_KEY(" (id INTEGER PRIMARY KEY AUTOINCREMENT,", 0xA9) +
        OBF_STR_KEY("file_id TEXT NOT NULL,", 0x5C) +
        OBF_STR_KEY("chunk_number INTEGER NOT NULL,", 0x7D) +
        OBF_STR_KEY("total_chunks INTEGER NOT NULL,", 0x9E) +
        OBF_STR_KEY("chunk_size INTEGER NOT NULL,", 0x4F) +
        OBF_STR_KEY("chunk_hash TEXT,", 0xA0) +
        OBF_STR_KEY("telegram_file_id TEXT,", 0xB1) +
        OBF_STR_KEY("message_id INTEGER,", 0xC2) +
        OBF_STR_KEY("upload_date TEXT DEFAULT CURRENT_TIMESTAMP,", 0xD3) +
        OBF_STR_KEY("status TEXT DEFAULT 'pending',", 0xE4) +
        OBF_STR_KEY("retry_count INTEGER DEFAULT 0,", 0xF5) +
        OBF_STR_KEY("error_message TEXT,", 0x06) +
        OBF_STR_KEY("uploader_bot_token TEXT,", 0x17) +
        OBF_STR_KEY("last_updated TEXT DEFAULT CURRENT_TIMESTAMP,", 0x18) +
        OBF_STR_KEY("UNIQUE (file_id, chunk_number),", 0x28) +
        OBF_STR_KEY("FOREIGN KEY (file_id) REFERENCES chunked_files(file_id) ON DELETE CASCADE", 0x39) +
        OBF_STR_KEY(");", 0x4A);
    
    if (!executeQuery(createFilesTable)) {
        LOG_ERROR("Failed to create files table");
        return false;
    }
    LOG_DEBUG("Files table created");
    
    if (!executeQuery(createChunkedFilesTable)) {
        LOG_ERROR("Failed to create chunked_files table");
        return false;
    }
    LOG_DEBUG("Chunked files table created");
    
    if (!executeQuery(createFileChunksTable)) {
        LOG_ERROR("Failed to create file_chunks table");
        return false;
    }
    LOG_DEBUG("File chunks table created");
    
    // Tabla de descargas
    std::string createDownloadsTable = 
        "CREATE TABLE IF NOT EXISTS downloads ("
        "download_id TEXT PRIMARY KEY,"
        "file_id TEXT NOT NULL,"
        "file_name TEXT NOT NULL,"
        "dest_path TEXT NOT NULL,"
        "total_size INTEGER NOT NULL,"
        "total_chunks INTEGER NOT NULL,"
        "completed_chunks INTEGER DEFAULT 0,"
        "download_started TEXT DEFAULT CURRENT_TIMESTAMP,"
        "last_update TEXT DEFAULT CURRENT_TIMESTAMP,"
        "status TEXT DEFAULT 'pending',"
        "temp_dir TEXT,"
        "error_message TEXT);";
    
    if (!executeQuery(createDownloadsTable)) {
        LOG_ERROR("Failed to create downloads table");
        return false;
    }
    LOG_DEBUG("Downloads table created");
    
    // Tabla de chunks de descarga
    std::string createDownloadChunksTable = 
        "CREATE TABLE IF NOT EXISTS download_chunks ("
        "id INTEGER PRIMARY KEY AUTOINCREMENT,"
        "download_id TEXT NOT NULL,"
        "chunk_number INTEGER NOT NULL,"
        "status TEXT DEFAULT 'pending',"
        "last_updated TEXT DEFAULT CURRENT_TIMESTAMP,"
        "UNIQUE (download_id, chunk_number),"
        "FOREIGN KEY (download_id) REFERENCES downloads(download_id) ON DELETE CASCADE);";
    
    if (!executeQuery(createDownloadChunksTable)) {
        LOG_ERROR("Failed to create download_chunks table");
        return false;
    }
    LOG_DEBUG("Download chunks table created");
    
    // Agregar columna is_encrypted si no existe (para bases de datos existentes)
    const char* addEncryptedColumnFiles = ObfuscatedStrings::SQL_ALTER_FILES();
    const char* addEncryptedColumnChunked = ObfuscatedStrings::SQL_ALTER_CHUNKED();
    
    // Ignorar errores si la columna ya existe (ejecutar sin logging de errores)
    char* errMsg = nullptr;
    sqlite3_exec(m_db, addEncryptedColumnFiles, nullptr, nullptr, &errMsg);
    if (errMsg) sqlite3_free(errMsg);
    
    errMsg = nullptr;
    sqlite3_exec(m_db, addEncryptedColumnChunked, nullptr, nullptr, &errMsg);
    if (errMsg) sqlite3_free(errMsg);
    
    LOG_INFO("All database tables created successfully");
    return true;
}

bool Database::executeQuery(const std::string& query) {
    if (!m_db) {
        LOG_ERROR("Database not initialized");
        return false;
    }
    
    char* errMsg = nullptr;
    int rc = sqlite3_exec(m_db, query.c_str(), nullptr, nullptr, &errMsg);
    
    if (rc != SQLITE_OK) {
        std::string error = "SQL error: " + std::string(errMsg);
        LOG_ERROR(error);
        std::cerr << error << std::endl;
        sqlite3_free(errMsg);
        return false;
    }
    
    return true;
}

bool Database::saveFileInfo(const FileInfo& fileInfo) {
    if (!m_db) {
        LOG_ERROR("Database not initialized");
        return false;
    }
    
    const char* insertSQL = ObfuscatedStrings::SQL_INSERT_FILE();
    
    sqlite3_stmt* stmt;
    int rc = sqlite3_prepare_v2(m_db, insertSQL, -1, &stmt, nullptr);
    
    if (rc != SQLITE_OK) {
        LOG_ERROR("Failed to prepare insert statement: " + getLastError());
        return false;
    }
    
    sqlite3_bind_text(stmt, 1, fileInfo.fileId.c_str(), -1, SQLITE_STATIC);
    sqlite3_bind_text(stmt, 2, fileInfo.fileName.c_str(), -1, SQLITE_STATIC);
    sqlite3_bind_int64(stmt, 3, fileInfo.fileSize);
    sqlite3_bind_text(stmt, 4, fileInfo.mimeType.c_str(), -1, SQLITE_STATIC);
    sqlite3_bind_text(stmt, 5, fileInfo.category.c_str(), -1, SQLITE_STATIC);
    sqlite3_bind_int64(stmt, 6, fileInfo.messageId);
    sqlite3_bind_text(stmt, 7, fileInfo.telegramFileId.c_str(), -1, SQLITE_STATIC);
    sqlite3_bind_text(stmt, 8, fileInfo.uploaderBotToken.c_str(), -1, SQLITE_STATIC);
    sqlite3_bind_int(stmt, 9, fileInfo.isEncrypted ? 1 : 0);
    
    rc = sqlite3_step(stmt);
    sqlite3_finalize(stmt);
    
    if (rc != SQLITE_DONE) {
        LOG_ERROR("Failed to insert file info: " + getLastError());
        return false;
    }
    
    LOG_INFO("File saved to database: " + fileInfo.fileName + " (ID: " + fileInfo.fileId + ")");
    return true;
}

std::vector<FileInfo> Database::getFiles() {
    std::vector<FileInfo> files;
    
    if (!m_db) {
        LOG_ERROR("Database not initialized");
        return files;
    }
    
    const char* selectSQL = "SELECT * FROM files ORDER BY upload_date DESC";
    
    sqlite3_stmt* stmt;
    int rc = sqlite3_prepare_v2(m_db, selectSQL, -1, &stmt, nullptr);
    
    if (rc != SQLITE_OK) {
        LOG_ERROR("Failed to prepare select statement: " + getLastError());
        return files;
    }
    
    while ((rc = sqlite3_step(stmt)) == SQLITE_ROW) {
        FileInfo info;
        info.id = sqlite3_column_int64(stmt, 0);
        
        const char* fileId = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 1));
        info.fileId = fileId ? fileId : "";
        
        const char* fileName = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 2));
        info.fileName = fileName ? fileName : "";
        
        info.fileSize = sqlite3_column_int64(stmt, 3);
        
        const char* mimeType = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 4));
        info.mimeType = mimeType ? mimeType : "";
        
        const char* category = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 5));
        info.category = category ? category : "";
        
        const char* uploadDate = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 6));
        info.uploadDate = uploadDate ? uploadDate : "";
        
        info.messageId = sqlite3_column_int64(stmt, 7);
        
        const char* telegramFileId = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 8));
        info.telegramFileId = telegramFileId ? telegramFileId : "";
        
        const char* uploaderBotToken = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 9));
        info.uploaderBotToken = uploaderBotToken ? uploaderBotToken : "";
        
        info.isEncrypted = sqlite3_column_int(stmt, 10) == 1;
        
        files.push_back(info);
    }
    
    sqlite3_finalize(stmt);
    
    LOG_DEBUG("Retrieved " + std::to_string(files.size()) + " files from database");
    return files;
}

FileInfo Database::getFileInfo(const std::string& fileId) {
    FileInfo info;
    
    if (!m_db) {
        LOG_ERROR("Database not initialized");
        return info;
    }
    
    const char* selectSQL = "SELECT * FROM files WHERE file_id = ?";
    sqlite3_stmt* stmt;
    
    int rc = sqlite3_prepare_v2(m_db, selectSQL, -1, &stmt, nullptr);
    if (rc != SQLITE_OK) {
        LOG_ERROR("Failed to prepare select statement: " + getLastError());
        return info;
    }
    
    sqlite3_bind_text(stmt, 1, fileId.c_str(), -1, SQLITE_STATIC);
    
    if (sqlite3_step(stmt) == SQLITE_ROW) {
        info.id = sqlite3_column_int64(stmt, 0);
        
        const char* fid = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 1));
        info.fileId = fid ? fid : "";
        
        const char* fname = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 2));
        info.fileName = fname ? fname : "";
        
        info.fileSize = sqlite3_column_int64(stmt, 3);
        
        const char* mime = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 4));
        info.mimeType = mime ? mime : "";
        
        const char* cat = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 5));
        info.category = cat ? cat : "";
        
        const char* date = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 6));
        info.uploadDate = date ? date : "";
        
        info.messageId = sqlite3_column_int64(stmt, 7);
        
        const char* tgFileId = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 8));
        info.telegramFileId = tgFileId ? tgFileId : "";
        
        const char* botToken = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 9));
        info.uploaderBotToken = botToken ? botToken : "";
        
        info.isEncrypted = sqlite3_column_int(stmt, 10) != 0;
    }
    
    sqlite3_finalize(stmt);
    
    LOG_DEBUG("Retrieved file info for: " + fileId);
    return info;
}

bool Database::registerChunkedFile(const ChunkedFileInfo& fileInfo) {
    if (!m_db) {
        LOG_ERROR("Database not initialized");
        return false;
    }
    
    const char* insertSQL = R"(
        INSERT INTO chunked_files (file_id, original_filename, mime_type, total_size,
                                   total_chunks, completed_chunks, status, original_file_hash)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
    )";
    
    sqlite3_stmt* stmt;
    int rc = sqlite3_prepare_v2(m_db, insertSQL, -1, &stmt, nullptr);
    
    if (rc != SQLITE_OK) {
        LOG_ERROR("Failed to prepare chunked file insert: " + getLastError());
        return false;
    }
    
    sqlite3_bind_text(stmt, 1, fileInfo.fileId.c_str(), -1, SQLITE_STATIC);
    sqlite3_bind_text(stmt, 2, fileInfo.originalFilename.c_str(), -1, SQLITE_STATIC);
    sqlite3_bind_text(stmt, 3, fileInfo.mimeType.c_str(), -1, SQLITE_STATIC);
    sqlite3_bind_int64(stmt, 4, fileInfo.totalSize);
    sqlite3_bind_int(stmt, 5, fileInfo.totalChunks);
    sqlite3_bind_int(stmt, 6, fileInfo.completedChunks);
    sqlite3_bind_text(stmt, 7, fileInfo.status.c_str(), -1, SQLITE_STATIC);
    sqlite3_bind_text(stmt, 8, fileInfo.originalFileHash.c_str(), -1, SQLITE_STATIC);
    
    rc = sqlite3_step(stmt);
    sqlite3_finalize(stmt);
    
    if (rc != SQLITE_DONE) {
        LOG_ERROR("Failed to register chunked file: " + getLastError());
        return false;
    }
    
    LOG_INFO("Chunked file registered in DB: " + fileInfo.originalFilename + 
             " (ID: " + fileInfo.fileId + ", " + std::to_string(fileInfo.totalChunks) + " chunks)");
    return true;
}

bool Database::saveChunkInfo(const ChunkInfo& chunkInfo) {
    if (!m_db) {
        LOG_ERROR("Database not initialized");
        return false;
    }
    
    const char* insertSQL = R"(
        INSERT INTO file_chunks (file_id, chunk_number, total_chunks, chunk_size,
                                chunk_hash, telegram_file_id, message_id, status, uploader_bot_token)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
    )";
    
    sqlite3_stmt* stmt;
    int rc = sqlite3_prepare_v2(m_db, insertSQL, -1, &stmt, nullptr);
    
    if (rc != SQLITE_OK) {
        LOG_ERROR("Failed to prepare chunk insert: " + getLastError());
        return false;
    }
    
    sqlite3_bind_text(stmt, 1, chunkInfo.fileId.c_str(), -1, SQLITE_STATIC);
    sqlite3_bind_int(stmt, 2, chunkInfo.chunkNumber);
    sqlite3_bind_int(stmt, 3, chunkInfo.totalChunks);
    sqlite3_bind_int64(stmt, 4, chunkInfo.chunkSize);
    sqlite3_bind_text(stmt, 5, chunkInfo.chunkHash.c_str(), -1, SQLITE_STATIC);
    sqlite3_bind_text(stmt, 6, chunkInfo.telegramFileId.c_str(), -1, SQLITE_STATIC);
    sqlite3_bind_int64(stmt, 7, chunkInfo.messageId);
    sqlite3_bind_text(stmt, 8, chunkInfo.status.c_str(), -1, SQLITE_STATIC);
    sqlite3_bind_text(stmt, 9, chunkInfo.uploaderBotToken.c_str(), -1, SQLITE_STATIC);
    
    rc = sqlite3_step(stmt);
    sqlite3_finalize(stmt);
    
    if (rc != SQLITE_DONE) {
        LOG_ERROR("Failed to save chunk info: " + getLastError());
        return false;
    }
    
    LOG_DEBUG("Chunk " + std::to_string(chunkInfo.chunkNumber) + " saved to database");
    return true;
}

std::vector<ChunkInfo> Database::getFileChunks(const std::string& fileId) {
    std::vector<ChunkInfo> chunks;
    
    if (!m_db) {
        LOG_ERROR("Database not initialized");
        return chunks;
    }
    
    const char* selectSQL = "SELECT * FROM file_chunks WHERE file_id = ? ORDER BY chunk_number";
    sqlite3_stmt* stmt;
    
    int rc = sqlite3_prepare_v2(m_db, selectSQL, -1, &stmt, nullptr);
    if (rc != SQLITE_OK) {
        LOG_ERROR("Failed to prepare chunk select: " + getLastError());
        return chunks;
    }
    
    sqlite3_bind_text(stmt, 1, fileId.c_str(), -1, SQLITE_STATIC);
    
    while (sqlite3_step(stmt) == SQLITE_ROW) {
        ChunkInfo info;
        info.id = sqlite3_column_int64(stmt, 0);
        
        const char* fid = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 1));
        info.fileId = fid ? fid : "";
        
        info.chunkNumber = sqlite3_column_int(stmt, 2);
        info.totalChunks = sqlite3_column_int(stmt, 3);
        info.chunkSize = sqlite3_column_int64(stmt, 4);
        
        const char* hash = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 5));
        info.chunkHash = hash ? hash : "";
        
        const char* tgFileId = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 6));
        info.telegramFileId = tgFileId ? tgFileId : "";
        
        info.messageId = sqlite3_column_int64(stmt, 7);
        
        const char* status = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 9));
        info.status = status ? status : "";
        
        const char* botToken = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 12));
        info.uploaderBotToken = botToken ? botToken : "";
        
        chunks.push_back(info);
    }
    
    sqlite3_finalize(stmt);
    
    LOG_INFO("Retrieved " + std::to_string(chunks.size()) + " chunks for file: " + fileId);
    return chunks;
}

bool Database::deleteFile(const std::string& fileId) {
    if (!m_db) {
        LOG_ERROR("Database not initialized");
        return false;
    }
    
    LOG_INFO("Deleting file from database: " + fileId);
    
    sqlite3_stmt* stmt = nullptr;
    bool success = false;
    
    // Iniciar transacción
    int rc = sqlite3_exec(m_db, "BEGIN TRANSACTION", nullptr, nullptr, nullptr);
    if (rc != SQLITE_OK) {
        LOG_ERROR("Failed to begin transaction: " + std::string(sqlite3_errmsg(m_db)));
        return false;
    }
    
    try {
        // Primero obtener información de los chunks para eliminarlos de Telegram
        const char* chunksSQL = "SELECT message_id, uploader_bot_token FROM file_chunks WHERE file_id = ? AND message_id IS NOT NULL AND uploader_bot_token IS NOT NULL";
        
        rc = sqlite3_prepare_v2(m_db, chunksSQL, -1, &stmt, nullptr);
        if (rc != SQLITE_OK) {
            LOG_ERROR("Failed to prepare chunks query: " + std::string(sqlite3_errmsg(m_db)));
            sqlite3_exec(m_db, "ROLLBACK", nullptr, nullptr, nullptr);
            return false;
        }
        
        sqlite3_bind_text(stmt, 1, fileId.c_str(), -1, SQLITE_STATIC);
        
        std::vector<std::pair<int64_t, std::string>> messagesToDelete;
        
        while (sqlite3_step(stmt) == SQLITE_ROW) {
            int64_t messageId = sqlite3_column_int64(stmt, 0);
            const char* tokenPtr = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 1));
            std::string token = tokenPtr ? tokenPtr : "";
            
            if (messageId > 0 && !token.empty()) {
                messagesToDelete.push_back({messageId, token});
            }
        }
        
        sqlite3_finalize(stmt);
        stmt = nullptr;
        
        LOG_INFO("Found " + std::to_string(messagesToDelete.size()) + " chunks to delete from Telegram for file: " + fileId);
        
        // Eliminar de chunked_files (esto también eliminará file_chunks por CASCADE)
        const char* deleteChunkedSQL = "DELETE FROM chunked_files WHERE file_id = ?";
        rc = sqlite3_prepare_v2(m_db, deleteChunkedSQL, -1, &stmt, nullptr);
        if (rc != SQLITE_OK) {
            LOG_ERROR("Failed to prepare delete chunked files query: " + std::string(sqlite3_errmsg(m_db)));
            sqlite3_exec(m_db, "ROLLBACK", nullptr, nullptr, nullptr);
            return false;
        }
        
        sqlite3_bind_text(stmt, 1, fileId.c_str(), -1, SQLITE_STATIC);
        rc = sqlite3_step(stmt);
        sqlite3_finalize(stmt);
        stmt = nullptr;
        
        if (rc != SQLITE_DONE && rc != SQLITE_ROW) {
            LOG_ERROR("Failed to delete chunked files: " + std::string(sqlite3_errmsg(m_db)));
            sqlite3_exec(m_db, "ROLLBACK", nullptr, nullptr, nullptr);
            return false;
        }
        
        // Eliminar de files
        const char* deleteFileSQL = "DELETE FROM files WHERE file_id = ?";
        rc = sqlite3_prepare_v2(m_db, deleteFileSQL, -1, &stmt, nullptr);
        if (rc != SQLITE_OK) {
            LOG_ERROR("Failed to prepare delete file query: " + std::string(sqlite3_errmsg(m_db)));
            sqlite3_exec(m_db, "ROLLBACK", nullptr, nullptr, nullptr);
            return false;
        }
        
        sqlite3_bind_text(stmt, 1, fileId.c_str(), -1, SQLITE_STATIC);
        rc = sqlite3_step(stmt);
        sqlite3_finalize(stmt);
        stmt = nullptr;
        
        if (rc != SQLITE_DONE && rc != SQLITE_ROW) {
            LOG_ERROR("Failed to delete file: " + std::string(sqlite3_errmsg(m_db)));
            sqlite3_exec(m_db, "ROLLBACK", nullptr, nullptr, nullptr);
            return false;
        }
        
        // Commit la transacción
        rc = sqlite3_exec(m_db, "COMMIT", nullptr, nullptr, nullptr);
        if (rc != SQLITE_OK) {
            LOG_ERROR("Failed to commit delete transaction: " + std::string(sqlite3_errmsg(m_db)));
            sqlite3_exec(m_db, "ROLLBACK", nullptr, nullptr, nullptr);
            return false;
        }
        
        success = true;
        LOG_INFO("Successfully deleted file from database: " + fileId);
        
        // Retornar información de mensajes a eliminar de Telegram
        // (Esto se manejará en MainWindow)
        
    } catch (const std::exception& e) {
        LOG_ERROR("Exception in deleteFile: " + std::string(e.what()));
        if (stmt) {
            sqlite3_finalize(stmt);
        }
        sqlite3_exec(m_db, "ROLLBACK", nullptr, nullptr, nullptr);
        return false;
    }
    
    return success;
}

std::vector<std::pair<int64_t, std::string>> Database::getMessagesToDelete(const std::string& fileId) {
    std::vector<std::pair<int64_t, std::string>> messagesToDelete;
    
    if (!m_db) {
        LOG_ERROR("Database not initialized");
        return messagesToDelete;
    }
    
    LOG_INFO("Getting messages to delete for file: " + fileId);
    
    sqlite3_stmt* stmt = nullptr;
    
    try {
        // Obtener mensajes de chunks
        const char* chunksSQL = "SELECT message_id, uploader_bot_token FROM file_chunks WHERE file_id = ? AND message_id IS NOT NULL AND uploader_bot_token IS NOT NULL";
        
        int rc = sqlite3_prepare_v2(m_db, chunksSQL, -1, &stmt, nullptr);
        if (rc != SQLITE_OK) {
            LOG_ERROR("Failed to prepare chunks query: " + std::string(sqlite3_errmsg(m_db)));
            return messagesToDelete;
        }
        
        sqlite3_bind_text(stmt, 1, fileId.c_str(), -1, SQLITE_STATIC);
        
        while (sqlite3_step(stmt) == SQLITE_ROW) {
            int64_t messageId = sqlite3_column_int64(stmt, 0);
            const char* tokenPtr = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 1));
            std::string token = tokenPtr ? tokenPtr : "";
            
            if (messageId > 0 && !token.empty()) {
                messagesToDelete.push_back({messageId, token});
            }
        }
        
        sqlite3_finalize(stmt);
        stmt = nullptr;
        
        // Obtener mensaje de archivo directo
        const char* fileSQL = "SELECT message_id, uploader_bot_token FROM files WHERE file_id = ? AND message_id IS NOT NULL AND uploader_bot_token IS NOT NULL";
        
        rc = sqlite3_prepare_v2(m_db, fileSQL, -1, &stmt, nullptr);
        if (rc != SQLITE_OK) {
            LOG_ERROR("Failed to prepare file query: " + std::string(sqlite3_errmsg(m_db)));
            return messagesToDelete;
        }
        
        sqlite3_bind_text(stmt, 1, fileId.c_str(), -1, SQLITE_STATIC);
        
        if (sqlite3_step(stmt) == SQLITE_ROW) {
            int64_t messageId = sqlite3_column_int64(stmt, 0);
            const char* tokenPtr = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 1));
            std::string token = tokenPtr ? tokenPtr : "";
            
            if (messageId > 0 && !token.empty()) {
                messagesToDelete.push_back({messageId, token});
            }
        }
        
        sqlite3_finalize(stmt);
        
        LOG_INFO("Found " + std::to_string(messagesToDelete.size()) + " messages to delete for file: " + fileId);
        
    } catch (const std::exception& e) {
        LOG_ERROR("Exception in getMessagesToDelete: " + std::string(e.what()));
        if (stmt) {
            sqlite3_finalize(stmt);
        }
    }
    
    return messagesToDelete;
}

int64_t Database::getTotalStorageUsed() {
    if (!m_db) {
        return 0;
    }
    
    const char* sumSQL = "SELECT SUM(file_size) FROM files";
    sqlite3_stmt* stmt;
    
    int rc = sqlite3_prepare_v2(m_db, sumSQL, -1, &stmt, nullptr);
    if (rc != SQLITE_OK) {
        return 0;
    }
    
    int64_t totalSize = 0;
    if (sqlite3_step(stmt) == SQLITE_ROW) {
        totalSize = sqlite3_column_int64(stmt, 0);
    }
    
    sqlite3_finalize(stmt);
    return totalSize;
}

int Database::getTotalFilesCount() {
    if (!m_db) {
        return 0;
    }
    
    const char* countSQL = "SELECT COUNT(*) FROM files";
    sqlite3_stmt* stmt;
    
    int rc = sqlite3_prepare_v2(m_db, countSQL, -1, &stmt, nullptr);
    if (rc != SQLITE_OK) {
        return 0;
    }
    
    int count = 0;
    if (sqlite3_step(stmt) == SQLITE_ROW) {
        count = sqlite3_column_int(stmt, 0);
    }
    
    sqlite3_finalize(stmt);
    return count;
}

std::string Database::getLastError() const {
    if (m_db) {
        return sqlite3_errmsg(m_db);
    }
    return "Database not initialized";
}

std::string Database::generateEncryptionKey() {
    std::random_device rd;
    std::mt19937 gen(rd());
    std::uniform_int_distribution<> dis(0, 255);
    
    std::stringstream ss;
    for (int i = 0; i < 32; ++i) {
        ss << std::hex << std::setw(2) << std::setfill('0') << dis(gen);
    }
    
    return ss.str();
}

bool Database::setEncryptionKey(const std::string& key) {
    if (!m_db) {
        LOG_ERROR("Database not initialized");
        return false;
    }
    
    m_encryptionKey = key;
    
    // Configurar clave de encriptación (DEBE ser lo primero después de sqlite3_open)
    std::string pragmaSQL = "PRAGMA key = '" + key + "'";
    char* errMsg = nullptr;
    int rc = sqlite3_exec(m_db, pragmaSQL.c_str(), nullptr, nullptr, &errMsg);
    
    if (rc != SQLITE_OK) {
        LOG_ERROR("Failed to set encryption key: " + std::string(errMsg));
        sqlite3_free(errMsg);
        return false;
    }
    
    // NO hacer verificación aquí - debe hacerse DESPUÉS de configurar todos los pragmas
    return true;
}

bool Database::isDatabaseEncrypted() {
    return m_isEncrypted;
}

bool Database::configureEncryption() {
    if (!m_db) {
        LOG_ERROR("Database not initialized");
        return false;
    }
    
    // Obtener o generar clave de encriptación desde EnvManager
    EnvManager& envMgr = EnvManager::instance();
    
    // CRÍTICO: Asegurar que EnvManager ha cargado el archivo encriptado
    envMgr.load();
    
    if (m_encryptionKey.empty()) {
        // Intentar cargar clave existente desde configuración encriptada
        std::string storedKey = envMgr.get("DB_ENCRYPTION_KEY");
        
        if (!storedKey.empty()) {
            m_encryptionKey = storedKey;
            LOG_INFO("Loaded existing database encryption key from secure storage");
        } else {
            // Generar nueva clave y guardarla
            m_encryptionKey = generateEncryptionKey();
            envMgr.set("DB_ENCRYPTION_KEY", m_encryptionKey);
            
            if (!envMgr.save()) {
                LOG_WARNING("Failed to save database encryption key to secure storage: " + envMgr.lastError());
            } else {
                LOG_INFO("Generated and saved new database encryption key to secure storage");
            }
        }
    }
    
    // Configurar encriptación SQLCipher
    if (!setEncryptionKey(m_encryptionKey)) {
        LOG_ERROR("Failed to configure database encryption");
        return false;
    }
    
    // Configurar parámetros de encriptación adicionales (DESPUÉS del PRAGMA key)
    const char* encryptionPragmas[] = {
        "PRAGMA cipher_page_size = 4096",
        "PRAGMA cipher_kdf_iter = 256000",
        "PRAGMA cipher_hmac_algorithm = HMAC_SHA1",
        "PRAGMA cipher_kdf_algorithm = PBKDF2_HMAC_SHA1"
    };
    
    for (const char* pragma : encryptionPragmas) {
        char* errMsg = nullptr;
        int rc = sqlite3_exec(m_db, pragma, nullptr, nullptr, &errMsg);
        if (rc != SQLITE_OK) {
            LOG_WARNING("Failed to set encryption pragma: " + std::string(errMsg));
            sqlite3_free(errMsg);
        }
    }
    
    // AHORA verificar que la base de datos funciona correctamente
    char* errMsg = nullptr;
    int rc = sqlite3_exec(m_db, "SELECT count(*) FROM sqlite_master", nullptr, nullptr, &errMsg);
    if (rc != SQLITE_OK) {
        LOG_ERROR("Database encryption verification failed: " + std::string(errMsg));
        sqlite3_free(errMsg);
        return false;
    }
    
    m_isEncrypted = true;
    LOG_INFO("Database encryption configured successfully");
    LOG_INFO("Database encryption configuration completed");
    return true;
}

// ============================================================================
// Upload Progress Persistence Implementation
// ============================================================================

bool Database::updateUploadState(const std::string& fileId, const std::string& state) {
    if (!m_db) {
        LOG_ERROR("Database not initialized");
        return false;
    }
    
    const char* updateSQL = "UPDATE chunked_files SET status = ?, last_update = CURRENT_TIMESTAMP WHERE file_id = ?";
    sqlite3_stmt* stmt;
    
    int rc = sqlite3_prepare_v2(m_db, updateSQL, -1, &stmt, nullptr);
    if (rc != SQLITE_OK) {
        LOG_ERROR("Failed to prepare update upload state query: " + getLastError());
        return false;
    }
    
    sqlite3_bind_text(stmt, 1, state.c_str(), -1, SQLITE_STATIC);
    sqlite3_bind_text(stmt, 2, fileId.c_str(), -1, SQLITE_STATIC);
    
    rc = sqlite3_step(stmt);
    sqlite3_finalize(stmt);
    
    if (rc != SQLITE_DONE) {
        LOG_ERROR("Failed to update upload state: " + getLastError());
        return false;
    }
    
    LOG_DEBUG("Updated upload state for " + fileId + " to: " + state);
    return true;
}

bool Database::updateChunkState(const std::string& fileId, int64_t chunkNumber, const std::string& state) {
    if (!m_db) {
        LOG_ERROR("Database not initialized");
        return false;
    }
    
    const char* updateSQL = "UPDATE file_chunks SET status = ?, last_updated = CURRENT_TIMESTAMP WHERE file_id = ? AND chunk_number = ?";
    sqlite3_stmt* stmt;
    
    int rc = sqlite3_prepare_v2(m_db, updateSQL, -1, &stmt, nullptr);
    if (rc != SQLITE_OK) {
        LOG_ERROR("Failed to prepare update chunk state query: " + getLastError());
        return false;
    }
    
    sqlite3_bind_text(stmt, 1, state.c_str(), -1, SQLITE_STATIC);
    sqlite3_bind_text(stmt, 2, fileId.c_str(), -1, SQLITE_STATIC);
    sqlite3_bind_int64(stmt, 3, chunkNumber);
    
    rc = sqlite3_step(stmt);
    sqlite3_finalize(stmt);
    
    if (rc != SQLITE_DONE) {
        LOG_ERROR("Failed to update chunk state: " + getLastError());
        return false;
    }
    
    LOG_DEBUG("Updated chunk " + std::to_string(chunkNumber) + " state to: " + state);
    return true;
}

std::vector<ChunkedFileInfo> Database::getIncompleteUploads() {
    std::vector<ChunkedFileInfo> incompleteUploads;
    
    if (!m_db) {
        LOG_ERROR("Database not initialized");
        return incompleteUploads;
    }
    
    // Primero, detectar y corregir archivos "completed" sin entrada en tabla 'files'
    const char* orphanedSQL = R"(
        SELECT cf.file_id, cf.original_filename, cf.mime_type, cf.total_size, 
               cf.total_chunks, cf.completed_chunks, cf.status, cf.original_file_hash
        FROM chunked_files cf
        LEFT JOIN files f ON cf.file_id = f.file_id
        WHERE cf.status = 'completed' AND f.file_id IS NULL
    )";
    
    sqlite3_stmt* stmt;
    int rc = sqlite3_prepare_v2(m_db, orphanedSQL, -1, &stmt, nullptr);
    if (rc == SQLITE_OK) {
        std::vector<std::string> orphanedFileIds;
        while (sqlite3_step(stmt) == SQLITE_ROW) {
            const char* fileId = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 0));
            if (fileId) {
                orphanedFileIds.push_back(fileId);
            }
        }
        sqlite3_finalize(stmt);
        
        // Finalizar archivos huérfanos
        for (const auto& fileId : orphanedFileIds) {
            LOG_INFO("Found orphaned completed file, finalizing: " + fileId);
            finalizeChunkedFile(fileId, fileId);
        }
    }
    
    // Ahora buscar archivos realmente incompletos
    const char* querySQL = "SELECT file_id, original_filename, mime_type, total_size, total_chunks, completed_chunks, status, original_file_hash FROM chunked_files WHERE status IN ('uploading', 'paused', 'stopped', 'pending')";
    
    rc = sqlite3_prepare_v2(m_db, querySQL, -1, &stmt, nullptr);
    if (rc != SQLITE_OK) {
        LOG_ERROR("Failed to prepare get incomplete uploads query: " + getLastError());
        return incompleteUploads;
    }
    
    while (sqlite3_step(stmt) == SQLITE_ROW) {
        ChunkedFileInfo info;
        info.fileId = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 0));
        info.originalFilename = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 1));
        
        const char* mimePtr = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 2));
        info.mimeType = mimePtr ? mimePtr : "";
        
        info.totalSize = sqlite3_column_int64(stmt, 3);
        info.totalChunks = sqlite3_column_int(stmt, 4);
        info.completedChunks = sqlite3_column_int(stmt, 5);
        
        const char* statusPtr = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 6));
        info.status = statusPtr ? statusPtr : "unknown";
        
        const char* hashPtr = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 7));
        info.originalFileHash = hashPtr ? hashPtr : "";
        
        incompleteUploads.push_back(info);
    }
    
    sqlite3_finalize(stmt);
    LOG_INFO("Found " + std::to_string(incompleteUploads.size()) + " incomplete uploads");
    return incompleteUploads;
}

std::vector<int64_t> Database::getCompletedChunks(const std::string& fileId) {
    std::vector<int64_t> completedChunks;
    
    if (!m_db) {
        LOG_ERROR("Database not initialized");
        return completedChunks;
    }
    
    const char* querySQL = "SELECT chunk_number FROM file_chunks WHERE file_id = ? AND status = 'completed' ORDER BY chunk_number";
    sqlite3_stmt* stmt;
    
    int rc = sqlite3_prepare_v2(m_db, querySQL, -1, &stmt, nullptr);
    if (rc != SQLITE_OK) {
        LOG_ERROR("Failed to prepare get completed chunks query: " + getLastError());
        return completedChunks;
    }
    
    sqlite3_bind_text(stmt, 1, fileId.c_str(), -1, SQLITE_STATIC);
    
    while (sqlite3_step(stmt) == SQLITE_ROW) {
        completedChunks.push_back(sqlite3_column_int64(stmt, 0));
    }
    
    sqlite3_finalize(stmt);
    LOG_DEBUG("File " + fileId + " has " + std::to_string(completedChunks.size()) + " completed chunks");
    return completedChunks;
}

bool Database::validateChunkIntegrity(const std::string& fileId, int64_t chunkNumber, const std::string& expectedHash) {
    if (!m_db) {
        LOG_ERROR("Database not initialized");
        return false;
    }
    
    const char* querySQL = "SELECT chunk_hash FROM file_chunks WHERE file_id = ? AND chunk_number = ? AND status = 'completed'";
    sqlite3_stmt* stmt;
    
    int rc = sqlite3_prepare_v2(m_db, querySQL, -1, &stmt, nullptr);
    if (rc != SQLITE_OK) {
        LOG_ERROR("Failed to prepare validate chunk query: " + getLastError());
        return false;
    }
    
    sqlite3_bind_text(stmt, 1, fileId.c_str(), -1, SQLITE_STATIC);
    sqlite3_bind_int64(stmt, 2, chunkNumber);
    
    bool isValid = false;
    if (sqlite3_step(stmt) == SQLITE_ROW) {
        const char* storedHash = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 0));
        if (storedHash && expectedHash == storedHash) {
            isValid = true;
        } else {
            LOG_WARNING("Chunk " + std::to_string(chunkNumber) + " hash mismatch");
        }
    }
    
    sqlite3_finalize(stmt);
    return isValid;
}

bool Database::deleteUploadProgress(const std::string& fileId) {
    if (!m_db) {
        LOG_ERROR("Database not initialized");
        return false;
    }
    
    // CASCADE eliminará también los chunks
    const char* deleteSQL = "DELETE FROM chunked_files WHERE file_id = ?";
    sqlite3_stmt* stmt;
    
    int rc = sqlite3_prepare_v2(m_db, deleteSQL, -1, &stmt, nullptr);
    if (rc != SQLITE_OK) {
        LOG_ERROR("Failed to prepare delete upload query: " + getLastError());
        return false;
    }
    
    sqlite3_bind_text(stmt, 1, fileId.c_str(), -1, SQLITE_STATIC);
    
    rc = sqlite3_step(stmt);
    sqlite3_finalize(stmt);
    
    if (rc != SQLITE_DONE) {
        LOG_ERROR("Failed to delete upload progress: " + getLastError());
        return false;
    }
    
    LOG_INFO("Deleted upload progress for: " + fileId);
    return true;
}

bool Database::updateUploadProgress(const std::string& fileId, int64_t completedChunks) {
    if (!m_db) {
        LOG_ERROR("Database not initialized");
        return false;
    }
    
    const char* updateSQL = "UPDATE chunked_files SET completed_chunks = ?, last_update = CURRENT_TIMESTAMP WHERE file_id = ?";
    sqlite3_stmt* stmt;
    
    int rc = sqlite3_prepare_v2(m_db, updateSQL, -1, &stmt, nullptr);
    if (rc != SQLITE_OK) {
        LOG_ERROR("Failed to prepare update progress query: " + getLastError());
        return false;
    }
    
    sqlite3_bind_int64(stmt, 1, completedChunks);
    sqlite3_bind_text(stmt, 2, fileId.c_str(), -1, SQLITE_STATIC);
    
    rc = sqlite3_step(stmt);
    sqlite3_finalize(stmt);
    
    if (rc != SQLITE_DONE) {
        LOG_ERROR("Failed to update upload progress: " + getLastError());
        return false;
    }
    
    return true;
}

bool Database::markAllActiveUploadsAsPaused() {
    if (!m_db) {
        LOG_ERROR("Database not initialized");
        return false;
    }
    
    const char* updateSQL = "UPDATE chunked_files SET status = 'paused', last_update = CURRENT_TIMESTAMP WHERE status = 'uploading'";
    
    char* errMsg = nullptr;
    int rc = sqlite3_exec(m_db, updateSQL, nullptr, nullptr, &errMsg);
    
    if (rc != SQLITE_OK) {
        LOG_ERROR("Failed to mark uploads as paused: " + std::string(errMsg));
        sqlite3_free(errMsg);
        return false;
    }
    
    LOG_INFO("Marked all active uploads as paused");
    return true;
}

bool Database::finalizeChunkedFile(const std::string& fileId, const std::string& telegramFileId) {
    if (!m_db) {
        LOG_ERROR("Database not initialized");
        return false;
    }
    
    // Verificar que todos los chunks estén completos
    const char* checkSQL = R"(
        SELECT cf.file_id, cf.total_chunks, cf.original_filename, cf.total_size, cf.mime_type,
               cf.status,
               COUNT(fc.id) as chunks_in_db,
               SUM(CASE WHEN fc.status = 'completed' THEN 1 ELSE 0 END) as completed_count
        FROM chunked_files cf
        LEFT JOIN file_chunks fc ON cf.file_id = fc.file_id
        WHERE cf.file_id = ?
        GROUP BY cf.file_id
    )";
    
    sqlite3_stmt* stmt;
    int rc = sqlite3_prepare_v2(m_db, checkSQL, -1, &stmt, nullptr);
    if (rc != SQLITE_OK) {
        LOG_ERROR("Failed to prepare check query: " + getLastError());
        return false;
    }
    
    sqlite3_bind_text(stmt, 1, fileId.c_str(), -1, SQLITE_STATIC);
    
    if (sqlite3_step(stmt) != SQLITE_ROW) {
        LOG_ERROR("No chunked_files record found for: " + fileId);
        sqlite3_finalize(stmt);
        return false;
    }
    
    int64_t totalChunks = sqlite3_column_int64(stmt, 1);
    const char* originalFilenamePtr = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 2));
    std::string originalFilename = originalFilenamePtr ? std::string(originalFilenamePtr) : "";
    int64_t totalSize = sqlite3_column_int64(stmt, 3);
    const char* mimeTypePtr = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 4));
    std::string mimeType = mimeTypePtr ? std::string(mimeTypePtr) : "";
    const char* statusPtr = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 5));
    std::string status = statusPtr ? std::string(statusPtr) : "";
    int64_t completedCount = sqlite3_column_int64(stmt, 7);
    
    sqlite3_finalize(stmt);
    
    // Verificar que todos los chunks estén completos
    if (completedCount < totalChunks) {
        LOG_WARNING("Cannot finalize " + fileId + ": " + 
                   std::to_string(completedCount) + "/" + std::to_string(totalChunks) + " chunks completed");
        return false;
    }
    
    // Actualizar status a 'completed' si no lo está
    bool alreadyCompleted = (status == "completed");
    if (!alreadyCompleted) {
        const char* updateSQL = "UPDATE chunked_files SET status = 'completed', final_telegram_file_id = ?, last_update = CURRENT_TIMESTAMP WHERE file_id = ?";
        
        rc = sqlite3_prepare_v2(m_db, updateSQL, -1, &stmt, nullptr);
        if (rc != SQLITE_OK) {
            LOG_ERROR("Failed to prepare update query: " + getLastError());
            return false;
        }
        
        sqlite3_bind_text(stmt, 1, telegramFileId.c_str(), -1, SQLITE_STATIC);
        sqlite3_bind_text(stmt, 2, fileId.c_str(), -1, SQLITE_STATIC);
        
        rc = sqlite3_step(stmt);
        sqlite3_finalize(stmt);
        
        if (rc != SQLITE_DONE) {
            LOG_ERROR("Failed to update chunked_files status: " + getLastError());
            return false;
        }
        
        LOG_INFO("Chunked file " + fileId + " marked as COMPLETED");
    } else {
        LOG_INFO("Chunked file " + fileId + " was already finalized");
    }
    
    // Verificar si ya existe en tabla 'files'
    const char* checkFileSQL = "SELECT 1 FROM files WHERE file_id = ?";
    rc = sqlite3_prepare_v2(m_db, checkFileSQL, -1, &stmt, nullptr);
    if (rc != SQLITE_OK) {
        LOG_ERROR("Failed to prepare check files query: " + getLastError());
        return false;
    }
    
    sqlite3_bind_text(stmt, 1, fileId.c_str(), -1, SQLITE_STATIC);
    bool existsInFiles = (sqlite3_step(stmt) == SQLITE_ROW);
    sqlite3_finalize(stmt);
    
    if (!existsInFiles) {
        LOG_INFO("Creating entry in 'files' table for chunked file: " + fileId);
        
        // Para archivos fragmentados, categoría siempre es "chunked"
        std::string category = "chunked";
        
        const char* insertSQL = R"(
            INSERT INTO files
            (file_id, file_name, file_size, mime_type, category,
             message_id, telegram_file_id, uploader_bot_token, is_encrypted)
            VALUES (?, ?, ?, ?, ?, NULL, ?, NULL, 0)
        )";
        
        rc = sqlite3_prepare_v2(m_db, insertSQL, -1, &stmt, nullptr);
        if (rc != SQLITE_OK) {
            LOG_ERROR("Failed to prepare insert files query: " + getLastError());
            return false;
        }
        
        std::string finalTelegramFileId = telegramFileId.empty() ? fileId : telegramFileId;
        
        sqlite3_bind_text(stmt, 1, fileId.c_str(), -1, SQLITE_TRANSIENT);
        sqlite3_bind_text(stmt, 2, originalFilename.c_str(), -1, SQLITE_TRANSIENT);
        sqlite3_bind_int64(stmt, 3, totalSize);
        sqlite3_bind_text(stmt, 4, mimeType.c_str(), -1, SQLITE_TRANSIENT);
        sqlite3_bind_text(stmt, 5, category.c_str(), -1, SQLITE_TRANSIENT);
        sqlite3_bind_text(stmt, 6, finalTelegramFileId.c_str(), -1, SQLITE_TRANSIENT);
        
        rc = sqlite3_step(stmt);
        sqlite3_finalize(stmt);
        
        if (rc != SQLITE_DONE) {
            LOG_ERROR("Failed to insert into files table: " + getLastError());
            return false;
        }
        
        LOG_INFO("Entry created in 'files' table for: " + fileId);
    } else {
        LOG_INFO("Entry for " + fileId + " already exists in 'files' table");
        
        // Actualizar categoría y nombre de archivo con los valores correctos
        LOG_INFO("Updating file metadata for: " + fileId);
        const char* updateSQL = "UPDATE files SET category = 'chunked', file_name = ?, file_size = ?, mime_type = ? WHERE file_id = ?";
        rc = sqlite3_prepare_v2(m_db, updateSQL, -1, &stmt, nullptr);
        if (rc == SQLITE_OK) {
            sqlite3_bind_text(stmt, 1, originalFilename.c_str(), -1, SQLITE_TRANSIENT);
            sqlite3_bind_int64(stmt, 2, totalSize);
            sqlite3_bind_text(stmt, 3, mimeType.c_str(), -1, SQLITE_TRANSIENT);
            sqlite3_bind_text(stmt, 4, fileId.c_str(), -1, SQLITE_TRANSIENT);
            sqlite3_step(stmt);
            sqlite3_finalize(stmt);
            LOG_INFO("File metadata updated successfully");
        }
    }
    
    return true;
}

// ============================================================================
// Download Progress Persistence
// ============================================================================

bool Database::registerDownload(const DownloadInfo& downloadInfo) {
    if (!m_db) {
        LOG_ERROR("Database not initialized");
        return false;
    }
    
    const char* sql = R"(
        INSERT INTO downloads 
        (download_id, file_id, file_name, dest_path, total_size, total_chunks, 
         completed_chunks, status, temp_dir)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
    )";
    
    sqlite3_stmt* stmt;
    int rc = sqlite3_prepare_v2(m_db, sql, -1, &stmt, nullptr);
    if (rc != SQLITE_OK) {
        LOG_ERROR("Failed to prepare register download query: " + getLastError());
        return false;
    }
    
    sqlite3_bind_text(stmt, 1, downloadInfo.downloadId.c_str(), -1, SQLITE_TRANSIENT);
    sqlite3_bind_text(stmt, 2, downloadInfo.fileId.c_str(), -1, SQLITE_TRANSIENT);
    sqlite3_bind_text(stmt, 3, downloadInfo.fileName.c_str(), -1, SQLITE_TRANSIENT);
    sqlite3_bind_text(stmt, 4, downloadInfo.destPath.c_str(), -1, SQLITE_TRANSIENT);
    sqlite3_bind_int64(stmt, 5, downloadInfo.totalSize);
    sqlite3_bind_int64(stmt, 6, downloadInfo.totalChunks);
    sqlite3_bind_int64(stmt, 7, downloadInfo.completedChunks);
    sqlite3_bind_text(stmt, 8, downloadInfo.status.c_str(), -1, SQLITE_TRANSIENT);
    sqlite3_bind_text(stmt, 9, downloadInfo.tempDir.c_str(), -1, SQLITE_TRANSIENT);
    
    rc = sqlite3_step(stmt);
    sqlite3_finalize(stmt);
    
    if (rc != SQLITE_DONE) {
        LOG_ERROR("Failed to register download: " + getLastError());
        return false;
    }
    
    LOG_INFO("Download registered: " + downloadInfo.downloadId);
    return true;
}

bool Database::updateDownloadState(const std::string& downloadId, const std::string& state) {
    if (!m_db) {
        LOG_ERROR("Database not initialized");
        return false;
    }
    
    const char* sql = "UPDATE downloads SET status = ?, last_update = CURRENT_TIMESTAMP WHERE download_id = ?";
    
    sqlite3_stmt* stmt;
    int rc = sqlite3_prepare_v2(m_db, sql, -1, &stmt, nullptr);
    if (rc != SQLITE_OK) {
        LOG_ERROR("Failed to prepare update download state query: " + getLastError());
        return false;
    }
    
    sqlite3_bind_text(stmt, 1, state.c_str(), -1, SQLITE_TRANSIENT);
    sqlite3_bind_text(stmt, 2, downloadId.c_str(), -1, SQLITE_TRANSIENT);
    
    rc = sqlite3_step(stmt);
    sqlite3_finalize(stmt);
    
    if (rc != SQLITE_DONE) {
        LOG_ERROR("Failed to update download state: " + getLastError());
        return false;
    }
    
    LOG_DEBUG("Download state updated: " + downloadId + " -> " + state);
    return true;
}

bool Database::updateDownloadChunkState(const std::string& downloadId, int64_t chunkNumber, const std::string& state) {
    if (!m_db) {
        LOG_ERROR("Database not initialized");
        return false;
    }
    
    const char* sql = R"(
        INSERT INTO download_chunks (download_id, chunk_number, status, last_updated)
        VALUES (?, ?, ?, CURRENT_TIMESTAMP)
        ON CONFLICT(download_id, chunk_number) 
        DO UPDATE SET status = ?, last_updated = CURRENT_TIMESTAMP
    )";
    
    sqlite3_stmt* stmt;
    int rc = sqlite3_prepare_v2(m_db, sql, -1, &stmt, nullptr);
    if (rc != SQLITE_OK) {
        LOG_ERROR("Failed to prepare update download chunk state query: " + getLastError());
        return false;
    }
    
    sqlite3_bind_text(stmt, 1, downloadId.c_str(), -1, SQLITE_TRANSIENT);
    sqlite3_bind_int64(stmt, 2, chunkNumber);
    sqlite3_bind_text(stmt, 3, state.c_str(), -1, SQLITE_TRANSIENT);
    sqlite3_bind_text(stmt, 4, state.c_str(), -1, SQLITE_TRANSIENT);
    
    rc = sqlite3_step(stmt);
    sqlite3_finalize(stmt);
    
    if (rc != SQLITE_DONE) {
        LOG_ERROR("Failed to update download chunk state: " + getLastError());
        return false;
    }
    
    return true;
}

std::vector<DownloadInfo> Database::getIncompleteDownloads() {
    std::vector<DownloadInfo> downloads;
    
    if (!m_db) {
        LOG_ERROR("Database not initialized");
        return downloads;
    }
    
    const char* sql = R"(
        SELECT download_id, file_id, file_name, dest_path, total_size, 
               total_chunks, completed_chunks, status, temp_dir
        FROM downloads
        WHERE status IN ('pending', 'downloading', 'paused', 'stopped')
        ORDER BY last_update DESC
    )";
    
    sqlite3_stmt* stmt;
    int rc = sqlite3_prepare_v2(m_db, sql, -1, &stmt, nullptr);
    if (rc != SQLITE_OK) {
        LOG_ERROR("Failed to prepare get incomplete downloads query: " + getLastError());
        return downloads;
    }
    
    while (sqlite3_step(stmt) == SQLITE_ROW) {
        DownloadInfo info;
        info.downloadId = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 0));
        info.fileId = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 1));
        info.fileName = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 2));
        info.destPath = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 3));
        info.totalSize = sqlite3_column_int64(stmt, 4);
        info.totalChunks = sqlite3_column_int64(stmt, 5);
        info.completedChunks = sqlite3_column_int64(stmt, 6);
        info.status = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 7));
        const char* tempDir = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 8));
        info.tempDir = tempDir ? tempDir : "";
        
        downloads.push_back(info);
    }
    
    sqlite3_finalize(stmt);
    LOG_INFO("Found " + std::to_string(downloads.size()) + " incomplete downloads");
    
    return downloads;
}

std::vector<int64_t> Database::getCompletedDownloadChunks(const std::string& downloadId) {
    std::vector<int64_t> chunks;
    
    if (!m_db) {
        LOG_ERROR("Database not initialized");
        return chunks;
    }
    
    const char* sql = "SELECT chunk_number FROM download_chunks WHERE download_id = ? AND status = 'completed' ORDER BY chunk_number";
    
    sqlite3_stmt* stmt;
    int rc = sqlite3_prepare_v2(m_db, sql, -1, &stmt, nullptr);
    if (rc != SQLITE_OK) {
        LOG_ERROR("Failed to prepare get completed download chunks query: " + getLastError());
        return chunks;
    }
    
    sqlite3_bind_text(stmt, 1, downloadId.c_str(), -1, SQLITE_TRANSIENT);
    
    while (sqlite3_step(stmt) == SQLITE_ROW) {
        chunks.push_back(sqlite3_column_int64(stmt, 0));
    }
    
    sqlite3_finalize(stmt);
    LOG_DEBUG("Found " + std::to_string(chunks.size()) + " completed download chunks for " + downloadId);
    
    return chunks;
}

bool Database::validateDownloadChunkExists(const std::string& downloadId, int64_t chunkNumber) {
    if (!m_db) {
        LOG_ERROR("Database not initialized");
        return false;
    }
    
    const char* sql = "SELECT 1 FROM download_chunks WHERE download_id = ? AND chunk_number = ? AND status = 'completed'";
    
    sqlite3_stmt* stmt;
    int rc = sqlite3_prepare_v2(m_db, sql, -1, &stmt, nullptr);
    if (rc != SQLITE_OK) {
        LOG_ERROR("Failed to prepare validate download chunk query: " + getLastError());
        return false;
    }
    
    sqlite3_bind_text(stmt, 1, downloadId.c_str(), -1, SQLITE_TRANSIENT);
    sqlite3_bind_int64(stmt, 2, chunkNumber);
    
    bool exists = (sqlite3_step(stmt) == SQLITE_ROW);
    sqlite3_finalize(stmt);
    
    return exists;
}

bool Database::deleteDownloadProgress(const std::string& downloadId) {
    if (!m_db) {
        LOG_ERROR("Database not initialized");
        return false;
    }
    
    const char* sql = "DELETE FROM downloads WHERE download_id = ?";
    
    sqlite3_stmt* stmt;
    int rc = sqlite3_prepare_v2(m_db, sql, -1, &stmt, nullptr);
    if (rc != SQLITE_OK) {
        LOG_ERROR("Failed to prepare delete download query: " + getLastError());
        return false;
    }
    
    sqlite3_bind_text(stmt, 1, downloadId.c_str(), -1, SQLITE_TRANSIENT);
    
    rc = sqlite3_step(stmt);
    sqlite3_finalize(stmt);
    
    if (rc != SQLITE_DONE) {
        LOG_ERROR("Failed to delete download progress: " + getLastError());
        return false;
    }
    
    LOG_INFO("Download progress deleted: " + downloadId);
    return true;
}

bool Database::updateDownloadProgress(const std::string& downloadId, int64_t completedChunks) {
    if (!m_db) {
        LOG_ERROR("Database not initialized");
        return false;
    }
    
    const char* sql = "UPDATE downloads SET completed_chunks = ?, last_update = CURRENT_TIMESTAMP WHERE download_id = ?";
    
    sqlite3_stmt* stmt;
    int rc = sqlite3_prepare_v2(m_db, sql, -1, &stmt, nullptr);
    if (rc != SQLITE_OK) {
        LOG_ERROR("Failed to prepare update download progress query: " + getLastError());
        return false;
    }
    
    sqlite3_bind_int64(stmt, 1, completedChunks);
    sqlite3_bind_text(stmt, 2, downloadId.c_str(), -1, SQLITE_TRANSIENT);
    
    rc = sqlite3_step(stmt);
    sqlite3_finalize(stmt);
    
    if (rc != SQLITE_DONE) {
        LOG_ERROR("Failed to update download progress: " + getLastError());
        return false;
    }
    
    return true;
}

bool Database::markAllActiveDownloadsAsPaused() {
    if (!m_db) {
        LOG_ERROR("Database not initialized");
        return false;
    }
    
    LOG_INFO("Marking all active downloads as paused...");
    
    const char* sql = "UPDATE downloads SET status = 'paused', last_update = CURRENT_TIMESTAMP WHERE status = 'downloading'";
    
    sqlite3_stmt* stmt;
    int rc = sqlite3_prepare_v2(m_db, sql, -1, &stmt, nullptr);
    if (rc != SQLITE_OK) {
        LOG_ERROR("Failed to prepare mark downloads as paused query: " + getLastError());
        return false;
    }
    
    rc = sqlite3_step(stmt);
    int changedRows = sqlite3_changes(m_db);
    sqlite3_finalize(stmt);
    
    if (rc != SQLITE_DONE) {
        LOG_ERROR("Failed to mark downloads as paused: " + getLastError());
        return false;
    }
    
    LOG_INFO("Marked " + std::to_string(changedRows) + " downloads as paused");
    return true;
}

} // namespace TelegramCloud
