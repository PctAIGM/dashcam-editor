package com.dashcam.editor.media

import android.app.Application
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

internal object CacheMaintenance {
    lateinit var storage: CacheStorage
        private set

    @Synchronized fun getStorage(context: Context): CacheStorage {
        if (!::storage.isInitialized) storage = CacheStorage(context.cacheDir)
        return storage
    }

    fun initialize(context: Context) {
        getStorage(context)
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "expired-media-cache", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CacheCleanupWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(24, TimeUnit.HOURS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES).build(),
        )
    }

    fun onAppOpened(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "startup-media-cache", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<CacheCleanupWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES).build(),
        )
    }
}

class DashcamApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CacheMaintenance.initialize(this)
    }
}

class CacheCleanupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val result = CacheMaintenance.getStorage(applicationContext).clean(expiredOnly = true)
        if (result.busy || result.failedFiles > 0) Result.retry() else Result.success()
    }
}
