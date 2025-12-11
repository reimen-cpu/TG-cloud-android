#pragma once

#include <string>

namespace TelegramCloud {

class BackupManager {
public:
    // Crea un .zip con .env y la base de datos usando PowerShell (sin dependencias externas)
    // Si password no está vacío, se guardan .env y DB en versión cifrada (.enc) y se incluye manifest JSON.
    static bool createZipBackup(const std::string& archivePath, const std::string& password = "");

    // Extrae un .zip en el directorio de trabajo (sobrescribe archivos). Si el backup es cifrado,
    // se requiere password para descifrar los .enc extraídos.
    static bool restoreZipBackup(const std::string& archivePath, const std::string& password = "");

    // Encrypt/decrypt individual files (public for Android JNI usage)
    static bool encryptFile(const std::string& in, const std::string& out, const std::string& password);
    static bool decryptFile(const std::string& in, const std::string& out, const std::string& password);

private:
    static std::string quote(const std::string& s);
    static std::string randomBytes(size_t n);
};

} // namespace TelegramCloud


