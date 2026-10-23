package com.dashcam.editor.media

import java.io.Closeable
import java.io.File

internal data class CacheUsage(val totalBytes: Long, val inUseBytes: Long) {
    val reclaimableBytes: Long get() = totalBytes - inUseBytes
}

internal data class CacheCleanup(val freedBytes: Long, val failedFiles: Int, val busy: Boolean)

/** Owns only imported copies and our temporary outputs, never gallery files or other caches. */
internal class CacheStorage(root: File) {
    private val root = root.canonicalFile
    private val sessions = mutableMapOf<Any, Set<String>>()
    private var operations = 0

    @Synchronized fun setSession(owner: Any, files: List<File>, now: Long = System.currentTimeMillis()) {
        val next = files.map { it.canonicalPath }.filter { it.startsWith(root.path + File.separator) }.toSet()
        val previous = sessions[owner].orEmpty()
        (previous + next).forEach { File(it).setLastModified(now) }
        if (next.isEmpty()) sessions.remove(owner) else sessions[owner] = next
    }

    /** Covers the whole operation, including handing imported files to the editor on Main. */
    @Synchronized fun beginOperation(): Closeable {
        operations++
        var closed = false
        return Closeable {
            synchronized(this) {
                if (!closed) { operations--; closed = true }
            }
        }
    }

    @Synchronized fun usage(): CacheUsage {
        val protected = sessions.values.flatten().toSet()
        val files = managedFiles()
        return CacheUsage(files.sumOf { it.length() },
            files.filter { operations > 0 || it.path in protected }.sumOf { it.length() })
    }

    @Synchronized fun clean(expiredOnly: Boolean, now: Long = System.currentTimeMillis()): CacheCleanup {
        if (operations > 0) return CacheCleanup(0, 0, true)
        val protected = sessions.values.flatten().toSet()
        var freed = 0L
        var failed = 0
        for (file in managedFiles()) {
            if (file.path in protected) continue
            if (expiredOnly && now - file.lastModified() < EXPIRY_MS) continue
            val size = file.length()
            if (file.delete()) freed += size else failed++
        }
        root.listFiles().orEmpty().filter { isManagedDirectory(it) && isLocal(it) }.forEach {
            if (it.listFiles()?.isEmpty() == true) it.delete()
        }
        return CacheCleanup(freed, failed, false)
    }

    private fun managedFiles(): List<File> = root.listFiles().orEmpty().flatMap { entry ->
        when {
            !isLocal(entry) -> emptyList()
            isManagedDirectory(entry) -> entry.listFiles().orEmpty().filter { it.isFile && isLocal(it) }
            entry.isFile && LEGACY_OUTPUT.matches(entry.name) -> listOf(entry)
            else -> emptyList()
        }
    }

    private fun isManagedDirectory(file: File): Boolean =
        file.isDirectory && (file.name == "imports" || file.name.startsWith("export_"))

    // Do not follow symbolic links, including links pointing at files outside the cache.
    private fun isLocal(file: File): Boolean = file.canonicalFile == file.absoluteFile &&
        file.canonicalPath.startsWith(root.path + File.separator)

    companion object {
        const val EXPIRY_MS = 24 * 60 * 60 * 1000L
        private val LEGACY_OUTPUT = Regex("(?:dashcam_.*\\.(?:mp4|txt)|shot_.*\\.jpg)")
    }
}
