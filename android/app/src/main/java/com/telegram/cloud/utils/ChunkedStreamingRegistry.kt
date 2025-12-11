package com.telegram.cloud.utils

import com.telegram.cloud.gallery.streaming.ChunkedStreamingManager
import java.util.Collections
import java.util.WeakHashMap

/**
 * Keeps weak references to active streaming managers so we can release them
 * when the app goes idle or the system requests memory.
 */
object ChunkedStreamingRegistry {
    private val managers =
        Collections.newSetFromMap(WeakHashMap<ChunkedStreamingManager, Boolean>())

    fun register(manager: ChunkedStreamingManager?) {
        manager ?: return
        managers.add(manager)
    }

    fun unregister(manager: ChunkedStreamingManager?) {
        manager ?: return
        managers.remove(manager)
    }

    fun releaseAll() {
        val snapshot = managers.toList()
        managers.clear()
        snapshot.forEach {
            try {
                it.release(keepChunks = true)
            } catch (_: Exception) {
            }
        }
    }
}


