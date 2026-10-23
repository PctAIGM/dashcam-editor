package com.dashcam.editor.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

class CacheStorageTest {
    @get:Rule val temp = TemporaryFolder()
    private val now = 10 * CacheStorage.EXPIRY_MS

    private fun file(path: String, modified: Long = now): File = File(temp.root, path).apply {
        parentFile!!.mkdirs()
        writeBytes(ByteArray(100))
        check(setLastModified(modified))
    }

    @Test fun autoCleanupDeletesOnlyExpiredOwnedFiles() {
        val oldImport = file("imports/old.mp4", now - CacheStorage.EXPIRY_MS)
        val recent = file("imports/recent.mp4", now - CacheStorage.EXPIRY_MS + 1_000)
        val oldExport = file("export_abandoned/out.mp4", 0)
        val legacy = file("dashcam_20261001.mp4", 0)
        val shot = file("shot_20261001.jpg", 0)
        val unrelated = file("other-cache/value", 0)
        val unknown = file("settings.json", 0)
        val result = CacheStorage(temp.root).clean(expiredOnly = true, now = now)
        assertEquals(400, result.freedBytes)
        assertFalse(oldImport.exists())
        assertFalse(oldExport.exists())
        assertFalse(oldExport.parentFile!!.exists())
        assertFalse(legacy.exists())
        assertFalse(shot.exists())
        assertTrue(recent.exists())
        assertTrue(unrelated.exists())
        assertTrue(unknown.exists())
    }

    @Test fun manualCleanupRemovesRecentUnusedFilesButKeepsCurrentSession() {
        val current = file("imports/current.mp4")
        val unused = file("imports/unused.mp4")
        val storage = CacheStorage(temp.root)
        storage.setSession("editor", listOf(current), now)
        assertEquals(CacheUsage(200, 100), storage.usage())
        assertEquals(100, storage.clean(expiredOnly = false, now = now).freedBytes)
        assertTrue(current.exists())
        assertFalse(unused.exists())
        // Even a days-long edit must remain protected.
        assertEquals(0, storage.clean(expiredOnly = true, now = now + 3 * CacheStorage.EXPIRY_MS).freedBytes)
    }

    @Test fun importHandoffKeepsFilesProtectedWithoutAGap() {
        val storage = CacheStorage(temp.root)
        val operation = storage.beginOperation()
        val imported = file("imports/imported.mp4")
        assertTrue(storage.clean(false, now).busy)
        storage.setSession("editor", listOf(imported), now)
        operation.close()
        assertEquals(0, storage.clean(false, now).freedBytes)
        assertTrue(imported.exists())
    }

    @Test fun nestedOperationsStayBusyUntilLastNativeOperationEnds() {
        val storage = CacheStorage(temp.root)
        val export = storage.beginOperation()
        val native = storage.beginOperation()
        val output = file("export_active/out.mp4")
        export.close()
        export.close()
        assertEquals(CacheUsage(100, 100), storage.usage())
        assertTrue(storage.clean(false, now).busy)
        assertTrue(output.exists())
        native.close()
        assertEquals(100, storage.clean(false, now).freedBytes)
    }

    @Test fun releasedSessionExpiresFromItsLastUseAndCanBeManuallyCleared() {
        val storage = CacheStorage(temp.root)
        val clip = file("imports/current.mp4", 0)
        storage.setSession("editor", listOf(clip), now)
        storage.setSession("editor", emptyList(), now + 1_000)
        assertEquals(0, storage.clean(true, now + CacheStorage.EXPIRY_MS).freedBytes)
        assertEquals(100, storage.clean(true, now + CacheStorage.EXPIRY_MS + 1_000).freedBytes)
    }

    @Test fun differentSessionOwnersCannotUnpinEachOther() {
        val storage = CacheStorage(temp.root)
        val clip = file("imports/current.mp4")
        storage.setSession("old activity", listOf(clip), now)
        storage.setSession("new activity", listOf(clip), now)
        storage.setSession("old activity", emptyList(), now)
        assertEquals(0, storage.clean(false, now).freedBytes)
        assertTrue(clip.exists())
    }

    @Test fun accountingUsesLongForLargeVideos() {
        val clip = file("imports/large.mp4")
        val bytes = 3_000_000_000L
        RandomAccessFile(clip, "rw").use { it.setLength(bytes) }
        val storage = CacheStorage(temp.root)
        assertEquals(bytes, storage.usage().reclaimableBytes)
        assertEquals(bytes, storage.clean(false, now).freedBytes)
    }

    @Test fun galleryOriginalOutsideCacheIsNeverTouched() {
        val cache = File(temp.root, "cache").apply { mkdirs() }
        val original = file("Movies/source.mp4", 0)
        val storage = CacheStorage(cache)
        storage.setSession("editor", listOf(original), now)
        storage.clean(false, now)
        assertTrue(original.exists())
        assertEquals(0, original.lastModified())
    }
}
