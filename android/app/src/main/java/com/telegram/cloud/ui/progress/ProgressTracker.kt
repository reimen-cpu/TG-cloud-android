package com.telegram.cloud.ui.progress

/**
 * Módulo de utilidades para calcular progreso de operaciones de upload y download.
 * Solo contiene funciones de cálculo, sin gestión de estado.
 */
object ProgressTracker {
    
    /**
     * Calcula el progreso para archivos directos basado en bytes escritos vs total.
     * @param bytesWritten Bytes escritos hasta el momento
     * @param totalBytes Tamaño total del archivo
     * @return Progreso como Float entre 0.0 y 1.0
     */
    fun calculateDirectProgress(bytesWritten: Long, totalBytes: Long): Float {
        if (totalBytes <= 0) return 0f
        return (bytesWritten.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
    }
    
    /**
     * Calcula el progreso para archivos fragmentados basado en chunks completados.
     * @param completedChunks Número de chunks completados
     * @param totalChunks Número total de chunks
     * @return Progreso como Float entre 0.0 y 1.0
     */
    fun calculateChunkedProgress(completedChunks: Int, totalChunks: Int): Float {
        if (totalChunks <= 0) return 0f
        return (completedChunks.toFloat() / totalChunks.toFloat()).coerceIn(0f, 1f)
    }
}
