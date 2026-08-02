package com.tensal.denden.messaging

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tensal.denden.data.EventDatabase
import com.tensal.denden.data.EventRepository
import com.tensal.denden.data.cleanupExpiredLocalTrash
import java.util.concurrent.TimeUnit

fun connectedWorkConstraints(): Constraints = Constraints.Builder()
    .setRequiredNetworkType(NetworkType.CONNECTED)
    .build()

class LocalTrashCleanupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = runCatching {
        val repository = EventRepository(EventDatabase.getInstance(applicationContext).eventDao())
        cleanupExpiredLocalTrash(applicationContext, repository)
    }.fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
}

fun scheduleLocalTrashCleanup(context: Context) {
    val request = PeriodicWorkRequestBuilder<LocalTrashCleanupWorker>(1, TimeUnit.DAYS).build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        "denden-local-trash-cleanup",
        ExistingPeriodicWorkPolicy.KEEP,
        request
    )
}
