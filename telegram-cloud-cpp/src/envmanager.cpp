#include "envmanager.h"
#include "obfuscated_strings.h"
#include "anti_debug.h"
#include <fstream>
#include <sstream>
#include <iostream>
#include <iomanip>
#include <algorithm>
#include <cstring>
#include <openssl/evp.h>
#include <openssl/bio.h>
#include <openssl/buffer.h>

namespace TelegramCloud {

EnvManager& EnvManager::instance() {
    static EnvManager instance;
    return instance;
}

EnvManager::EnvManager()
    : m_encryptedPath(DEFAULT_ENCRYPTED_PATH)
{
}

bool EnvManager::load() {
    ANTI_DEBUG_CHECK(); // Anti-debug al cargar configuración
    
    m_lastError.clear();
    
    // Verificar si existe el archivo encriptado
    std::ifstream file(m_encryptedPath, std::ios::binary);
    if (!file.is_open()) {
        // Intentar migrar desde .env en texto plano
        if (initializeFromPlaintext(ObfuscatedStrings::ENV_FILE_NAME()) || 
            initializeFromPlaintext(ObfuscatedStrings::ENV_FILE_PARENT())) {
            return true;
        }
        m_lastError = ObfuscatedStrings::ERR_NO_CONFIG_FILE();
        return false;
    }
    
    // Leer el archivo encriptado
    std::stringstream buffer;
    buffer << file.rdbuf();
    std::string fileContent = buffer.str();
    file.close();
    
    if (fileContent.empty()) {
        m_lastError = ObfuscatedStrings::ERR_EMPTY_CONFIG();
        return false;
    }
    
    try {
        // Formato: IV(base64)|CONTENT_HASH(hex)|CIPHERTEXT(base64)
        size_t sep1 = fileContent.find('|');
        if (sep1 == std::string::npos) {
            m_lastError = "Formato de archivo inválido (falta separador 1)";
            return false;
        }
        
        size_t sep2 = fileContent.find('|', sep1 + 1);
        if (sep2 == std::string::npos) {
            m_lastError = "Formato de archivo inválido (falta separador 2)";
            return false;
        }
        
        std::string ivBase64 = fileContent.substr(0, sep1);
        std::string contentHash = fileContent.substr(sep1 + 1, sep2 - sep1 - 1);
        std::string ciphertextBase64 = fileContent.substr(sep2 + 1);
        
        // Decodificar IV y ciphertext
        std::vector<unsigned char> iv = base64Decode(ivBase64);
        std::vector<unsigned char> ciphertext = base64Decode(ciphertextBase64);
        
        if (iv.size() != IV_SIZE) {
            m_lastError = "IV inválido";
            return false;
        }
        
        // Derivar clave del hash del contenido
        std::vector<unsigned char> key(KEY_SIZE);
        const unsigned char* salt = reinterpret_cast<const unsigned char*>("TELEGRAM_CLOUD_SALT");
        
        PKCS5_PBKDF2_HMAC(
            contentHash.c_str(),
            contentHash.length(),
            salt,
            19,
            PBKDF2_ITERATIONS,
            EVP_sha256(),
            KEY_SIZE,
            key.data()
        );
        
        // Desencriptar
        std::string plaintext = decrypt(ciphertext, key, iv);
        deserialize(plaintext);
        
        return true;
        
    } catch (const std::exception& e) {
        m_lastError = std::string("Error al procesar archivo: ") + e.what();
        return false;
    }
}

bool EnvManager::save() {
    m_lastError.clear();
    
    try {
        // Serializar configuración
        std::string plaintext = serialize();
        
        // Calcular hash del contenido (esto será el hint para derivar la clave)
        std::string contentHash = sha256(plaintext);
        
        // Derivar clave del hash del contenido
        std::vector<unsigned char> key(KEY_SIZE);
        const unsigned char* salt = reinterpret_cast<const unsigned char*>("TELEGRAM_CLOUD_SALT");
        
        PKCS5_PBKDF2_HMAC(
            contentHash.c_str(),
            contentHash.length(),
            salt,
            19,
            PBKDF2_ITERATIONS,
            EVP_sha256(),
            KEY_SIZE,
            key.data()
        );
        
        // Generar IV aleatorio
        std::vector<unsigned char> iv(IV_SIZE);
        if (RAND_bytes(iv.data(), IV_SIZE) != 1) {
            m_lastError = "Error al generar IV";
            return false;
        }
        
        // Encriptar
        std::vector<unsigned char> ciphertext = encrypt(plaintext, key, iv);
        
        // Guardar en formato: IV(base64)|CONTENT_HASH(hex)|CIPHERTEXT(base64)
        std::string ivBase64 = base64Encode(iv);
        std::string ciphertextBase64 = base64Encode(ciphertext);
        
        std::ofstream file(m_encryptedPath, std::ios::binary | std::ios::trunc);
        if (!file.is_open()) {
            m_lastError = "No se pudo abrir archivo para escritura";
            return false;
        }
        
        file << ivBase64 << "|" << contentHash << "|" << ciphertextBase64;
        file.close();
        
        return true;
        
    } catch (const std::exception& e) {
        m_lastError = std::string("Error al guardar: ") + e.what();
        return false;
    }
}

std::string EnvManager::get(const std::string& key) const {
    auto it = m_config.find(key);
    if (it != m_config.end()) {
        return it->second;
    }
    return "";
}

void EnvManager::set(const std::string& key, const std::string& value) {
    m_config[key] = value;
}

void EnvManager::remove(const std::string& key) {
    m_config.erase(key);
}

std::vector<std::string> EnvManager::keys() const {
    std::vector<std::string> result;
    for (const auto& pair : m_config) {
        result.push_back(pair.first);
    }
    return result;
}

std::map<std::string, std::string> EnvManager::getAll() const {
    return m_config;
}

bool EnvManager::exists(const std::string& key) const {
    return m_config.find(key) != m_config.end();
}

bool EnvManager::initializeFromPlaintext(const std::string& plainEnvPath) {
    m_lastError.clear();
    
    std::ifstream file(plainEnvPath);
    if (!file.is_open()) {
        return false;
    }
    
    std::string line;
    m_config.clear();
    
    while (std::getline(file, line)) {
        line = trim(line);
        
        // Ignorar líneas vacías y comentarios
        if (line.empty() || line[0] == '#') {
            continue;
        }
        
        // Parsear KEY=VALUE
        size_t equalPos = line.find('=');
        if (equalPos == std::string::npos) {
            continue;
        }
        
        std::string key = trim(line.substr(0, equalPos));
        std::string value = trim(line.substr(equalPos + 1));
        
        // Remover comillas
        if (value.length() >= 2) {
            if ((value.front() == '\'' && value.back() == '\'') ||
                (value.front() == '"' && value.back() == '"')) {
                value = value.substr(1, value.length() - 2);
            }
        }
        
        m_config[key] = value;
    }
    
    file.close();
    
    // Guardar como archivo encriptado
    return save();
}

bool EnvManager::exportToPlaintext(const std::string& plainEnvPath) const {
    m_lastError.clear();
    
    std::ofstream file(plainEnvPath, std::ios::trunc);
    if (!file.is_open()) {
        m_lastError = "No se pudo abrir archivo para escritura";
        return false;
    }
    
    file << serialize();
    file.close();
    
    return true;
}

std::vector<unsigned char> EnvManager::deriveMasterKey() const {
    // Derivar la clave del contenido de la configuración
    // Usamos los valores críticos para generar una "semilla"
    std::string seed;
    
    // Orden determinístico de las claves para consistencia
    std::vector<std::string> criticalKeys = {
        "BOT_TOKEN", "CHANNEL_ID", "API_ID", "API_HASH"
    };
    
    for (const auto& key : criticalKeys) {
        auto it = m_config.find(key);
        if (it != m_config.end()) {
            seed += it->second;
        }
    }
    
    // Si no hay valores críticos, usar todos los valores
    if (seed.empty()) {
        for (const auto& pair : m_config) {
            seed += pair.second;
        }
    }
    
    // Si aún está vacío, usar una semilla por defecto
    if (seed.empty()) {
        seed = "DEFAULT_SEED_" + m_encryptedPath;
    }
    
    // Derivar clave usando PBKDF2-SHA256
    std::vector<unsigned char> key(KEY_SIZE);
    const unsigned char* salt = reinterpret_cast<const unsigned char*>("TELEGRAM_CLOUD_SALT");
    
    PKCS5_PBKDF2_HMAC(
        seed.c_str(),
        seed.length(),
        salt,
        19, // longitud de "TELEGRAM_CLOUD_SALT"
        PBKDF2_ITERATIONS,
        EVP_sha256(),
        KEY_SIZE,
        key.data()
    );
    
    return key;
}

std::vector<unsigned char> EnvManager::encrypt(
    const std::string& plaintext,
    const std::vector<unsigned char>& key,
    const std::vector<unsigned char>& iv
) const {
    EVP_CIPHER_CTX* ctx = EVP_CIPHER_CTX_new();
    if (!ctx) {
        throw std::runtime_error("Error al crear contexto de encriptación");
    }
    
    if (EVP_EncryptInit_ex(ctx, EVP_aes_256_cbc(), nullptr, key.data(), iv.data()) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        throw std::runtime_error("Error al inicializar encriptación");
    }
    
    std::vector<unsigned char> ciphertext(plaintext.length() + EVP_CIPHER_block_size(EVP_aes_256_cbc()));
    int len = 0;
    int ciphertext_len = 0;
    
    if (EVP_EncryptUpdate(ctx, ciphertext.data(), &len,
                          reinterpret_cast<const unsigned char*>(plaintext.data()),
                          plaintext.length()) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        throw std::runtime_error("Error al encriptar datos");
    }
    ciphertext_len = len;
    
    if (EVP_EncryptFinal_ex(ctx, ciphertext.data() + len, &len) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        throw std::runtime_error("Error al finalizar encriptación");
    }
    ciphertext_len += len;
    
    EVP_CIPHER_CTX_free(ctx);
    
    ciphertext.resize(ciphertext_len);
    return ciphertext;
}

std::string EnvManager::decrypt(
    const std::vector<unsigned char>& ciphertext,
    const std::vector<unsigned char>& key,
    const std::vector<unsigned char>& iv
) const {
    EVP_CIPHER_CTX* ctx = EVP_CIPHER_CTX_new();
    if (!ctx) {
        throw std::runtime_error("Error al crear contexto de desencriptación");
    }
    
    if (EVP_DecryptInit_ex(ctx, EVP_aes_256_cbc(), nullptr, key.data(), iv.data()) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        throw std::runtime_error("Error al inicializar desencriptación");
    }
    
    std::vector<unsigned char> plaintext(ciphertext.size() + EVP_CIPHER_block_size(EVP_aes_256_cbc()));
    int len = 0;
    int plaintext_len = 0;
    
    if (EVP_DecryptUpdate(ctx, plaintext.data(), &len, ciphertext.data(), ciphertext.size()) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        throw std::runtime_error("Error al desencriptar datos");
    }
    plaintext_len = len;
    
    if (EVP_DecryptFinal_ex(ctx, plaintext.data() + len, &len) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        throw std::runtime_error("Error al finalizar desencriptación");
    }
    plaintext_len += len;
    
    EVP_CIPHER_CTX_free(ctx);
    
    return std::string(reinterpret_cast<char*>(plaintext.data()), plaintext_len);
}

std::string EnvManager::serialize() const {
    std::stringstream ss;
    
    for (const auto& pair : m_config) {
        ss << pair.first << "=" << pair.second << "\n";
    }
    
    return ss.str();
}

void EnvManager::deserialize(const std::string& data) {
    m_config.clear();
    
    std::istringstream iss(data);
    std::string line;
    
    while (std::getline(iss, line)) {
        line = trim(line);
        
        if (line.empty() || line[0] == '#') {
            continue;
        }
        
        size_t equalPos = line.find('=');
        if (equalPos == std::string::npos) {
            continue;
        }
        
        std::string key = trim(line.substr(0, equalPos));
        std::string value = trim(line.substr(equalPos + 1));
        
        m_config[key] = value;
    }
}

std::string EnvManager::sha256(const std::string& data) const {
    unsigned char hash[SHA256_DIGEST_LENGTH];
    SHA256_CTX sha256;
    SHA256_Init(&sha256);
    SHA256_Update(&sha256, data.c_str(), data.length());
    SHA256_Final(hash, &sha256);
    
    std::stringstream ss;
    for (int i = 0; i < SHA256_DIGEST_LENGTH; i++) {
        ss << std::hex << std::setw(2) << std::setfill('0') << static_cast<int>(hash[i]);
    }
    
    return ss.str();
}

std::string EnvManager::toHex(const std::vector<unsigned char>& data) const {
    std::stringstream ss;
    for (unsigned char byte : data) {
        ss << std::hex << std::setw(2) << std::setfill('0') << static_cast<int>(byte);
    }
    return ss.str();
}

std::vector<unsigned char> EnvManager::fromHex(const std::string& hex) const {
    std::vector<unsigned char> result;
    for (size_t i = 0; i < hex.length(); i += 2) {
        std::string byteString = hex.substr(i, 2);
        unsigned char byte = static_cast<unsigned char>(std::stoi(byteString, nullptr, 16));
        result.push_back(byte);
    }
    return result;
}

std::string EnvManager::base64Encode(const std::vector<unsigned char>& data) const {
    BIO* bio = BIO_new(BIO_s_mem());
    BIO* b64 = BIO_new(BIO_f_base64());
    bio = BIO_push(b64, bio);
    
    BIO_set_flags(bio, BIO_FLAGS_BASE64_NO_NL);
    BIO_write(bio, data.data(), data.size());
    BIO_flush(bio);
    
    BUF_MEM* bufferPtr;
    BIO_get_mem_ptr(bio, &bufferPtr);
    
    std::string result(bufferPtr->data, bufferPtr->length);
    BIO_free_all(bio);
    
    return result;
}

std::vector<unsigned char> EnvManager::base64Decode(const std::string& encoded) const {
    BIO* bio = BIO_new_mem_buf(encoded.data(), encoded.length());
    BIO* b64 = BIO_new(BIO_f_base64());
    bio = BIO_push(b64, bio);
    
    BIO_set_flags(bio, BIO_FLAGS_BASE64_NO_NL);
    
    std::vector<unsigned char> result(encoded.length());
    int decodedLength = BIO_read(bio, result.data(), encoded.length());
    
    BIO_free_all(bio);
    
    if (decodedLength > 0) {
        result.resize(decodedLength);
    }
    
    return result;
}

std::string EnvManager::trim(const std::string& str) const {
    size_t start = str.find_first_not_of(" \t\r\n");
    if (start == std::string::npos) return "";
    
    size_t end = str.find_last_not_of(" \t\r\n");
    return str.substr(start, end - start + 1);
}

} // namespace TelegramCloud

