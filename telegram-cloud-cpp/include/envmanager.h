#ifndef ENVMANAGER_H
#define ENVMANAGER_H

#include <string>
#include <map>
#include <vector>
#include <openssl/evp.h>
#include <openssl/sha.h>
#include <openssl/rand.h>

namespace TelegramCloud {

/**
 * @brief Gestor de configuración encriptada
 * 
 * Este módulo maneja la encriptación y desencriptación del archivo .env
 * La masterkey se deriva automáticamente del contenido del archivo
 */
class EnvManager {
public:
    static EnvManager& instance();
    
    /**
     * @brief Carga el archivo de configuración encriptado
     * @return true si se cargó correctamente
     */
    bool load();
    
    /**
     * @brief Guarda el archivo de configuración encriptado
     * @return true si se guardó correctamente
     */
    bool save();
    
    /**
     * @brief Obtiene un valor de configuración
     * @param key Clave de configuración
     * @return Valor de configuración o string vacío
     */
    std::string get(const std::string& key) const;
    
    /**
     * @brief Establece un valor de configuración
     * @param key Clave de configuración
     * @param value Valor de configuración
     */
    void set(const std::string& key, const std::string& value);
    
    /**
     * @brief Elimina un valor de configuración
     * @param key Clave de configuración
     */
    void remove(const std::string& key);
    
    /**
     * @brief Obtiene todas las claves de configuración
     * @return Vector con todas las claves
     */
    std::vector<std::string> keys() const;
    
    /**
     * @brief Obtiene todos los pares clave-valor
     * @return Mapa con toda la configuración
     */
    std::map<std::string, std::string> getAll() const;
    
    /**
     * @brief Verifica si existe un valor de configuración
     * @param key Clave de configuración
     * @return true si existe
     */
    bool exists(const std::string& key) const;
    
    /**
     * @brief Obtiene el último error
     * @return Mensaje de error o string vacío
     */
    std::string lastError() const { return m_lastError; }
    
    /**
     * @brief Inicializa el archivo encriptado desde un .env en texto plano
     * @param plainEnvPath Ruta al archivo .env en texto plano
     * @return true si se inicializó correctamente
     */
    bool initializeFromPlaintext(const std::string& plainEnvPath);
    
    /**
     * @brief Exporta la configuración a un archivo .env en texto plano
     * @param plainEnvPath Ruta donde guardar el archivo
     * @return true si se exportó correctamente
     */
    bool exportToPlaintext(const std::string& plainEnvPath) const;
    
private:
    EnvManager();
    ~EnvManager() = default;
    EnvManager(const EnvManager&) = delete;
    EnvManager& operator=(const EnvManager&) = delete;
    
    /**
     * @brief Deriva la masterkey del contenido de la configuración
     * @return Clave derivada (32 bytes)
     */
    std::vector<unsigned char> deriveMasterKey() const;
    
    /**
     * @brief Encripta datos usando AES-256-CBC
     * @param plaintext Datos en texto plano
     * @param key Clave de encriptación
     * @param iv Vector de inicialización
     * @return Datos encriptados
     */
    std::vector<unsigned char> encrypt(
        const std::string& plaintext,
        const std::vector<unsigned char>& key,
        const std::vector<unsigned char>& iv
    ) const;
    
    /**
     * @brief Desencripta datos usando AES-256-CBC
     * @param ciphertext Datos encriptados
     * @param key Clave de encriptación
     * @param iv Vector de inicialización
     * @return Datos desencriptados
     */
    std::string decrypt(
        const std::vector<unsigned char>& ciphertext,
        const std::vector<unsigned char>& key,
        const std::vector<unsigned char>& iv
    ) const;
    
    /**
     * @brief Serializa la configuración a string
     * @return String con la configuración en formato KEY=VALUE
     */
    std::string serialize() const;
    
    /**
     * @brief Deserializa una string a configuración
     * @param data String con la configuración
     */
    void deserialize(const std::string& data);
    
    /**
     * @brief Calcula el hash SHA-256 de un string
     * @param data String a hashear
     * @return Hash en hexadecimal
     */
    std::string sha256(const std::string& data) const;
    
    /**
     * @brief Convierte bytes a hexadecimal
     * @param data Bytes a convertir
     * @return String hexadecimal
     */
    std::string toHex(const std::vector<unsigned char>& data) const;
    
    /**
     * @brief Convierte hexadecimal a bytes
     * @param hex String hexadecimal
     * @return Vector de bytes
     */
    std::vector<unsigned char> fromHex(const std::string& hex) const;
    
    /**
     * @brief Codifica en base64
     * @param data Datos a codificar
     * @return String en base64
     */
    std::string base64Encode(const std::vector<unsigned char>& data) const;
    
    /**
     * @brief Decodifica desde base64
     * @param encoded String en base64
     * @return Vector de bytes
     */
    std::vector<unsigned char> base64Decode(const std::string& encoded) const;
    
    /**
     * @brief Trim de espacios en blanco
     * @param str String a procesar
     * @return String sin espacios al inicio/final
     */
    std::string trim(const std::string& str) const;
    
    // Configuración
    std::map<std::string, std::string> m_config;
    
    // Rutas de archivos
    std::string m_encryptedPath;
    
    // Error tracking
    mutable std::string m_lastError;
    
    // Constantes
    static constexpr int KEY_SIZE = 32;        // 256 bits
    static constexpr int IV_SIZE = 16;         // 128 bits
    static constexpr int PBKDF2_ITERATIONS = 100000;
    static constexpr const char* DEFAULT_ENCRYPTED_PATH = ".env";
};

} // namespace TelegramCloud

#endif // ENVMANAGER_H

