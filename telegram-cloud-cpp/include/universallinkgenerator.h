#ifndef UNIVERSALLINKGENERATOR_H
#define UNIVERSALLINKGENERATOR_H

#include <string>
#include <vector>
#include "database.h"

namespace TelegramCloud {

/**
 * @brief Generador de archivos .link universales
 * 
 * Crea archivos .link que contienen toda la información necesaria
 * para descargar archivos sin depender de la base de datos local.
 */
class UniversalLinkGenerator {
public:
    UniversalLinkGenerator(Database* database);
    
    /**
     * @brief Genera un archivo .link para un archivo individual
     * @param fileId ID del archivo en la base de datos
     * @param password Contraseña para encriptar el link
     * @param outputPath Ruta donde guardar el archivo .link
     * @return true si se generó correctamente
     */
    bool generateLinkFile(
        const std::string& fileId,
        const std::string& password,
        const std::string& outputPath
    );
    
    /**
     * @brief Genera un archivo .link para múltiples archivos
     * @param fileIds Lista de IDs de archivos
     * @param password Contraseña para encriptar el link
     * @param outputPath Ruta donde guardar el archivo .link
     * @return true si se generó correctamente
     */
    bool generateBatchLinkFile(
        const std::vector<std::string>& fileIds,
        const std::string& password,
        const std::string& outputPath
    );
    
private:
    Database* m_database;
    
    /**
     * @brief Serializa la información de un archivo a JSON
     */
    std::string serializeFileData(const FileInfo& fileInfo, const std::vector<ChunkInfo>& chunks);
    
    /**
     * @brief Serializa múltiples archivos a JSON
     */
    std::string serializeBatchData(const std::vector<std::pair<FileInfo, std::vector<ChunkInfo>>>& filesData);
    
    /**
     * @brief Encripta los datos con AES-256
     */
    std::string encryptData(const std::string& data, const std::string& password);
    
    /**
     * @brief Genera salt aleatorio para encriptación
     */
    std::string generateSalt();
    
    /**
     * @brief Deriva clave de encriptación desde contraseña
     */
    std::string deriveKey(const std::string& password, const std::string& salt);
};

} // namespace TelegramCloud

#endif // UNIVERSALLINKGENERATOR_H


