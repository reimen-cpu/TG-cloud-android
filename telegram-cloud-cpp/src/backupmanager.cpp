#include "backupmanager.h"
#include "logger.h"
#include <filesystem>
#include <cstdlib>
#include <sstream>
#include <fstream>
#include <random>
#include <openssl/evp.h>
#include <openssl/rand.h>
#include <nlohmann/json.hpp>

#ifdef TELEGRAMCLOUD_ANDROID
#include <android/log.h>
#define BACKUP_LOG_TAG "TelegramCloudBackup"
#define BACKUP_LOG_INFO(msg) __android_log_print(ANDROID_LOG_INFO, BACKUP_LOG_TAG, "%s", (msg))
#define BACKUP_LOG_ERROR(msg) __android_log_print(ANDROID_LOG_ERROR, BACKUP_LOG_TAG, "%s", (msg))
#else
#define BACKUP_LOG_INFO(msg) LOG_INFO(msg)
#define BACKUP_LOG_ERROR(msg) LOG_ERROR(msg)
#endif

namespace fs = std::filesystem;

namespace TelegramCloud {

using nlohmann::json;

static bool writeFile(const std::string& path, const std::string& data) {
    std::ofstream f(path, std::ios::binary);
    if (!f) return false;
    f.write(data.data(), (std::streamsize)data.size());
    return (bool)f;
}

std::string BackupManager::randomBytes(size_t n) {
    std::string s;
    s.resize(n);
    RAND_bytes(reinterpret_cast<unsigned char*>(&s[0]), (int)n);
    return s;
}

bool BackupManager::encryptFile(const std::string& in, const std::string& out, const std::string& password) {
    try {
        std::ifstream fi(in, std::ios::binary);
        if (!fi) return false;
        std::string plain((std::istreambuf_iterator<char>(fi)), std::istreambuf_iterator<char>());

        std::string salt = randomBytes(16);
        std::string iv = randomBytes(16);

        unsigned char key[32];
        // Derive key = SHA256(password || salt)
        EVP_MD_CTX* mdctx = EVP_MD_CTX_new();
        EVP_DigestInit_ex(mdctx, EVP_sha256(), nullptr);
        EVP_DigestUpdate(mdctx, password.data(), password.size());
        EVP_DigestUpdate(mdctx, salt.data(), salt.size());
        unsigned int outlen = 0;
        EVP_DigestFinal_ex(mdctx, key, &outlen);
        EVP_MD_CTX_free(mdctx);

        EVP_CIPHER_CTX* ctx = EVP_CIPHER_CTX_new();
        EVP_EncryptInit_ex(ctx, EVP_aes_256_cbc(), nullptr,
                           key, reinterpret_cast<const unsigned char*>(iv.data()));

        std::string outbuf;
        outbuf.resize(plain.size() + 16);
        int len = 0, total = 0;
        EVP_EncryptUpdate(ctx, reinterpret_cast<unsigned char*>(&outbuf[0]), &len,
                          reinterpret_cast<const unsigned char*>(plain.data()), (int)plain.size());
        total = len;
        EVP_EncryptFinal_ex(ctx, reinterpret_cast<unsigned char*>(&outbuf[0]) + total, &len);
        total += len;
        outbuf.resize(total);
        EVP_CIPHER_CTX_free(ctx);

        // File format: BKP1 | salt(16) | iv(16) | ciphertext
        std::string magic = "BKP1";
        std::string all = magic + salt + iv + outbuf;
        return writeFile(out, all);
    } catch (...) {
        return false;
    }
}

bool BackupManager::decryptFile(const std::string& in, const std::string& out, const std::string& password) {
    BACKUP_LOG_INFO(("decryptFile: in=" + in + " out=" + out).c_str());
    try {
        std::ifstream fi(in, std::ios::binary);
        if (!fi) {
            BACKUP_LOG_ERROR(("decryptFile: Cannot open input file: " + in).c_str());
            return false;
        }
        std::string enc((std::istreambuf_iterator<char>(fi)), std::istreambuf_iterator<char>());
        BACKUP_LOG_INFO(("decryptFile: Read " + std::to_string(enc.size()) + " bytes").c_str());
        
        if (enc.size() < 4 + 16 + 16) {
            BACKUP_LOG_ERROR("decryptFile: File too small (< 36 bytes header)");
            return false;
        }
        
        std::string magic = enc.substr(0, 4);
        BACKUP_LOG_INFO(("decryptFile: Magic header = '" + magic + "'").c_str());
        
        if (magic != std::string("BKP1")) {
            BACKUP_LOG_ERROR(("decryptFile: Invalid magic header, expected 'BKP1' got '" + magic + "'").c_str());
            return false;
        }
        
        std::string salt = enc.substr(4, 16);
        std::string iv = enc.substr(20, 16);
        std::string cipher = enc.substr(36);
        
        BACKUP_LOG_INFO(("decryptFile: salt=" + std::to_string(salt.size()) + "B iv=" + std::to_string(iv.size()) + "B cipher=" + std::to_string(cipher.size()) + "B").c_str());

        unsigned char key[32];
        EVP_MD_CTX* mdctx = EVP_MD_CTX_new();
        if (!mdctx) {
            BACKUP_LOG_ERROR("decryptFile: EVP_MD_CTX_new failed");
            return false;
        }
        EVP_DigestInit_ex(mdctx, EVP_sha256(), nullptr);
        EVP_DigestUpdate(mdctx, password.data(), password.size());
        EVP_DigestUpdate(mdctx, salt.data(), salt.size());
        unsigned int outlen = 0;
        EVP_DigestFinal_ex(mdctx, key, &outlen);
        EVP_MD_CTX_free(mdctx);
        BACKUP_LOG_INFO(("decryptFile: Derived key, len=" + std::to_string(outlen)).c_str());

        EVP_CIPHER_CTX* ctx = EVP_CIPHER_CTX_new();
        if (!ctx) {
            BACKUP_LOG_ERROR("decryptFile: EVP_CIPHER_CTX_new failed");
            return false;
        }
        
        if (EVP_DecryptInit_ex(ctx, EVP_aes_256_cbc(), nullptr,
                           key, reinterpret_cast<const unsigned char*>(iv.data())) != 1) {
            BACKUP_LOG_ERROR("decryptFile: EVP_DecryptInit_ex failed");
            EVP_CIPHER_CTX_free(ctx);
            return false;
        }

        std::string outbuf;
        outbuf.resize(cipher.size() + 16);
        int len = 0, total = 0;
        if (EVP_DecryptUpdate(ctx, reinterpret_cast<unsigned char*>(&outbuf[0]), &len,
                              reinterpret_cast<const unsigned char*>(cipher.data()), (int)cipher.size()) != 1) {
            BACKUP_LOG_ERROR("decryptFile: EVP_DecryptUpdate failed - wrong password?");
            EVP_CIPHER_CTX_free(ctx);
            return false;
        }
        total = len;
        BACKUP_LOG_INFO(("decryptFile: DecryptUpdate produced " + std::to_string(len) + " bytes").c_str());
        
        if (EVP_DecryptFinal_ex(ctx, reinterpret_cast<unsigned char*>(&outbuf[0]) + total, &len) != 1) {
            BACKUP_LOG_ERROR("decryptFile: EVP_DecryptFinal_ex failed - wrong password or corrupted data");
            EVP_CIPHER_CTX_free(ctx);
            return false;
        }
        total += len;
        outbuf.resize(total);
        EVP_CIPHER_CTX_free(ctx);

        BACKUP_LOG_INFO(("decryptFile: Decrypted " + std::to_string(total) + " bytes total").c_str());

        bool written = writeFile(out, outbuf);
        BACKUP_LOG_INFO(("decryptFile: writeFile result=" + std::to_string(written)).c_str());
        return written;
    } catch (const std::exception& e) {
        BACKUP_LOG_ERROR(("decryptFile: Exception: " + std::string(e.what())).c_str());
        return false;
    } catch (...) {
        BACKUP_LOG_ERROR("decryptFile: Unknown exception");
        return false;
    }
}

bool BackupManager::createZipBackup(const std::string& archivePath, const std::string& password) {
    try {
        // Prepare temp dir with files to include
        fs::path tempDir = fs::path("backup_temp_pack");
        if (fs::exists(tempDir)) fs::remove_all(tempDir);
        fs::create_directories(tempDir);

        bool encrypted = !password.empty();
        if (encrypted) {
            encryptFile(".env", (tempDir / ".env.enc").string(), password);
            encryptFile("database/telegram_cloud.db", (tempDir / "telegram_cloud.db.enc").string(), password);
        } else {
            fs::copy_file(".env", tempDir / ".env", fs::copy_options::overwrite_existing);
            fs::create_directories(tempDir / "database");
            fs::copy_file("database/telegram_cloud.db", tempDir / "database/telegram_cloud.db", fs::copy_options::overwrite_existing);
        }

        // manifest
        json j;
        j["encrypted"] = encrypted;
        std::string manifest = j.dump();
        writeFile((tempDir / "backup_manifest.json").string(), manifest);

        // Compress tempDir
        fs::create_directories(fs::path(archivePath).parent_path());
        std::ostringstream cmd;
        cmd << "pwsh -NoLogo -NoProfile -Command \"Compress-Archive -Path '"
            << (tempDir.string()) << "/*' -DestinationPath '" << archivePath << "' -Force\"";

        LOG_INFO("Creating ZIP backup: " + archivePath);
        int rc = std::system(cmd.str().c_str());
        fs::remove_all(tempDir);
        if (rc != 0) {
            LOG_ERROR("Compress-Archive returned error code: " + std::to_string(rc));
            return false;
        }
        return fs::exists(archivePath);

    } catch (const std::exception& e) {
        LOG_ERROR("Backup creation failed: " + std::string(e.what()));
        return false;
    }
}

bool BackupManager::restoreZipBackup(const std::string& archivePath, const std::string& password) {
    try {
        if (!fs::exists(archivePath)) {
            LOG_ERROR("Backup archive not found: " + archivePath);
            return false;
        }

        // Expand to temp dir
        fs::path tempDir = fs::path("backup_temp_unpack");
        if (fs::exists(tempDir)) fs::remove_all(tempDir);
        fs::create_directories(tempDir);

        std::ostringstream cmd;
        cmd << "pwsh -NoLogo -NoProfile -Command \"Expand-Archive -Path '"
            << archivePath << "' -DestinationPath '" << tempDir.string() << "' -Force\"";

        LOG_INFO("Restoring ZIP backup: " + archivePath);
        int rc = std::system(cmd.str().c_str());
        if (rc != 0) {
            LOG_ERROR("Expand-Archive returned error code: " + std::to_string(rc));
            fs::remove_all(tempDir);
            return false;
        }

        // read manifest
        bool encrypted = false;
        try {
            std::ifstream mf((tempDir / "backup_manifest.json").string());
            if (mf) {
                json j; mf >> j;
                if (j.contains("encrypted")) encrypted = j["encrypted"].get<bool>();
            }
        } catch (...) {}

        if (encrypted) {
            if (password.empty()) {
                LOG_ERROR("Backup requires password but none provided");
                fs::remove_all(tempDir);
                return false;
            }
            decryptFile((tempDir / ".env.enc").string(), ".env", password);
            decryptFile((tempDir / "telegram_cloud.db.enc").string(), "database/telegram_cloud.db", password);
        } else {
            // Copy plain files
            fs::copy_file(tempDir / ".env", ".env", fs::copy_options::overwrite_existing);
            fs::create_directories("database");
            fs::copy_file(tempDir / "database/telegram_cloud.db", "database/telegram_cloud.db", fs::copy_options::overwrite_existing);
        }

        fs::remove_all(tempDir);
        return true;

    } catch (const std::exception& e) {
        LOG_ERROR("Backup restore failed: " + std::string(e.what()));
        return false;
    }
}

} // namespace TelegramCloud


