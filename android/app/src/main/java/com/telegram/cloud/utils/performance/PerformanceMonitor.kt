package com.telegram.cloud.utils.performance

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Process
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.measureTimeMillis

object PerformanceMonitor {
    
    private val performanceData = ConcurrentHashMap<String, PerformanceMetric>()
    private var isMonitoring = false
    
    data class PerformanceMetric(
        val operationName: String,
        val totalTime: Long,
        val executionCount: Int,
        val lastExecutionTime: Long,
        val averageTime: Long,
        val maxTime: Long,
        val minTime: Long
    )
    
    fun startMonitoring(context: Context) {
        if (isMonitoring) return
        isMonitoring = true
        
        ProcessLifecycleOwner.get().lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    logMemoryUsage(context)
                    logPerformanceStats()
                    delay(5000) // Log every 5 seconds
                } catch (e: Exception) {
                    Timber.w(e, "Performance monitoring error")
                }
            }
        }
    }
    
    inline fun <T> measureOperation(operation: String, block: () -> T): T {
        val executionTime = measureTimeMillis {
            return block()
        }
        
        recordMetric(operation, executionTime)
        
        if (executionTime > 1000) { // Log slow operations
            Timber.w("Slow operation detected: $operation took ${executionTime}ms")
        }
        
        return block()
    }
    
    suspend fun <T> measureSuspendOperation(operation: String, block: suspend () -> T): T {
        val executionTime = measureTimeMillis {
            return block()
        }
        
        recordMetric(operation, executionTime)
        
        if (executionTime > 2000) { // Log slow suspend operations
            Timber.w("Slow suspend operation detected: $operation took ${executionTime}ms")
        }
        
        return block()
    }
    
    private fun recordMetric(operation: String, executionTime: Long) {
        val existing = performanceData[operation]
        
        if (existing != null) {
            val newCount = existing.executionCount + 1
            val newTotal = existing.totalTime + executionTime
            val newMax = maxOf(existing.maxTime, executionTime)
            val newMin = minOf(existing.minTime, executionTime)
            
            performanceData[operation] = PerformanceMetric(
                operationName = operation,
                totalTime = newTotal,
                executionCount = newCount,
                lastExecutionTime = System.currentTimeMillis(),
                averageTime = newTotal / newCount,
                maxTime = newMax,
                minTime = newMin
            )
        } else {
            performanceData[operation] = PerformanceMetric(
                operationName = operation,
                totalTime = executionTime,
                executionCount = 1,
                lastExecutionTime = System.currentTimeMillis(),
                averageTime = executionTime,
                maxTime = executionTime,
                minTime = executionTime
            )
        }
    }
    
    private fun logMemoryUsage(context: Context) {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        
        val runtime = Runtime.getRuntime()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        val maxMemory = runtime.maxMemory()
        val memoryUsagePercent = (usedMemory * 100) / maxMemory
        
        val nativeHeapSize = Debug.getNativeHeapSize()
        val nativeHeapAllocated = Debug.getNativeHeapAllocatedSize()
        val nativeHeapFree = Debug.getNativeHeapFreeSize()
        
        Timber.d("Memory Usage: ${usedMemory / 1024 / 1024}MB/${maxMemory / 1024 / 1024}MB ($memoryUsagePercent%)")
        Timber.d("Native Heap: ${nativeHeapAllocated / 1024 / 1024}MB allocated, ${nativeHeapFree / 1024 / 1024}MB free")
        
        if (memoryUsagePercent > 85) {
            Timber.w("High memory usage detected: $memoryUsagePercent%")
        }
        
        if (memoryInfo.lowMemory) {
            Timber.w("System is low on memory!")
        }
    }
    
    private fun logPerformanceStats() {
        if (performanceData.isNotEmpty()) {
            Timber.d("=== Performance Summary ===")
            performanceData.forEach { (operation, metric) ->
                if (metric.executionCount > 10) { // Only log operations with significant data
                    Timber.d("$operation: avg=${metric.averageTime}ms, count=${metric.executionCount}, max=${metric.maxTime}ms")
                }
            }
        }
    }
    
    fun getPerformanceReport(): Map<String, PerformanceMetric> {
        return performanceData.toMap()
    }
    
    fun clearMetrics() {
        performanceData.clear()
    }
    
    fun getMemoryPressureLevel(context: Context): MemoryPressure {
        val runtime = Runtime.getRuntime()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        val maxMemory = runtime.maxMemory()
        val memoryUsagePercent = (usedMemory * 100) / maxMemory
        
        return when {
            memoryUsagePercent > 90 -> MemoryPressure.CRITICAL
            memoryUsagePercent > 80 -> MemoryPressure.HIGH
            memoryUsagePercent > 70 -> MemoryPressure.MEDIUM
            else -> MemoryPressure.LOW
        }
    }
    
    enum class MemoryPressure {
        LOW, MEDIUM, HIGH, CRITICAL
    }
}

@Composable
fun PerformanceTracker(operation: String) {
    val context = LocalContext.current
    LaunchedEffect(operation) {
        PerformanceMonitor.startMonitoring(context)
    }
}

class PerformanceTimer(private val operation: String) {
    private var startTime = 0L
    
    fun start() {
        startTime = System.currentTimeMillis()
    }
    
    fun end() {
        val duration = System.currentTimeMillis() - startTime
        PerformanceMonitor.recordMetric(operation, duration)
    }
    
    companion object {
        inline fun timed(operation: String, block: PerformanceTimer.() -> Unit) {
            val timer = PerformanceTimer(operation)
            timer.start()
            block(timer)
            timer.end()
        }
    }
}