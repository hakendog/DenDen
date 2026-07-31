package com.tensal.denden.setup

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Data
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging
import com.tensal.denden.messaging.connectedWorkConstraints
import com.tensal.denden.branding.DirectBrandStore
import com.tensal.denden.protocol.DirectFcmInvite
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class DirectPairingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = DirectPairingStore(applicationContext)
        val expectedRevision = inputData.getLong(REVISION_KEY, -1)
        val before = store.snapshot()
        if (expectedRevision < 0 || before.localPairingRevision != expectedRevision) return Result.success()
        if (before.state == PairingState.ERROR && before.phase == null) return Result.failure()
        return runCatching {
            if (before.state == PairingState.ACTIVE) {
                resubscribeActiveDirectPairing(applicationContext, store, expectedRevision)
            } else {
                resumeDirectPairing(applicationContext, store)
            }
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { error ->
                val after = store.snapshot()
                if (before.state == PairingState.PENDING &&
                    after.state == PairingState.ACTIVE &&
                    after.localPairingRevision == expectedRevision
                ) {
                    Result.success()
                } else {
                    Log.w("DenDenPairing", "FCM 配對重試：${error.javaClass.simpleName}: ${error.message ?: "無錯誤訊息"}")
                    if (before.state == PairingState.PENDING) store.markError(expectedRevision, error.message ?: "FCM 配對失敗")
                    if (runAttemptCount < MAX_ATTEMPTS - 1) Result.retry() else Result.failure()
                }
            }
        )
    }

    companion object {
        const val WORK_NAME = "denden-direct-pairing"
        const val REVISION_KEY = "local_pairing_revision"
        const val MAX_ATTEMPTS = 5
    }
}

fun scheduleDirectPairing(context: Context) {
    val revision = DirectPairingStore(context).snapshot().localPairingRevision
    WorkManager.getInstance(context).enqueueUniqueWork(
        DirectPairingWorker.WORK_NAME,
        ExistingWorkPolicy.REPLACE,
        OneTimeWorkRequestBuilder<DirectPairingWorker>()
            .setInputData(Data.Builder().putLong(DirectPairingWorker.REVISION_KEY, revision).build())
            .setConstraints(connectedWorkConstraints())
            .build()
    )
}

suspend fun resumeDirectPairing(
    context: Context,
    store: DirectPairingStore = DirectPairingStore(context)
): Unit = runDirectPairingAttempt {
    var snapshot = store.snapshot()
    if (snapshot.state == PairingState.ERROR) {
        check(store.retryError(snapshot.localPairingRevision)) { "配對錯誤狀態無法重試" }
        snapshot = store.snapshot()
    }
    if (snapshot.state != PairingState.PENDING) return@runDirectPairingAttempt
    val revision = snapshot.localPairingRevision
    if (snapshot.phase == PairingPhase.CLEANUP) {
        snapshot.cleanup?.let { cleanupSubscription(context, it, store, revision) }
        check(store.markCleanupComplete(revision)) { "配對版本已變更" }
        snapshot = store.snapshot()
        if (snapshot.state == PairingState.UNPAIRED) return@runDirectPairingAttempt
    }
    val candidate = snapshot.candidate ?: throw IllegalStateException("缺少待訂閱配對")
    requireCurrent(store, revision, PairingPhase.SUBSCRIBE)
    val app = initializeDefaultFirebase(context, candidate)
    val messaging = FirebaseMessaging.getInstance()
    messaging.isAutoInitEnabled = true
    messaging.subscribeToTopic(candidate.topic).awaitTask()
    requireCurrent(store, revision, PairingPhase.SUBSCRIBE)
    DirectBrandStore(context).activatePairing(candidate.pairingId)
    check(store.markActive(revision)) { "配對版本已變更" }
}

internal suspend fun <T> runDirectPairingAttempt(
    timeoutMillis: Long = DIRECT_PAIRING_ATTEMPT_TIMEOUT_MILLIS,
    block: suspend () -> T
): T = withTimeout(timeoutMillis) { directRuntimeMutex.withLock { block() } }

private const val DIRECT_PAIRING_ATTEMPT_TIMEOUT_MILLIS = 30_000L

suspend fun resubscribeActiveDirectPairing(
    context: Context,
    store: DirectPairingStore = DirectPairingStore(context),
    expectedRevision: Long? = null
) {
    val snapshot = store.snapshot()
    val active = snapshot.active ?: return
    if (snapshot.state != PairingState.ACTIVE) return
    if (expectedRevision != null && snapshot.localPairingRevision != expectedRevision) return
    initializeDefaultFirebase(context, active)
    FirebaseMessaging.getInstance().subscribeToTopic(active.topic).awaitTask()
    val after = store.snapshot()
    check(after.state == PairingState.ACTIVE && after.localPairingRevision == snapshot.localPairingRevision) { "配對版本已變更" }
}

fun initializeDirectFirebaseRuntime(
    context: Context,
    store: DirectPairingStore = DirectPairingStore(context)
): Boolean {
    val snapshot = store.snapshot()
    val target = when {
        snapshot.state == PairingState.ACTIVE -> snapshot.active
        snapshot.state == PairingState.PENDING && snapshot.phase == PairingPhase.SUBSCRIBE -> snapshot.candidate
        else -> null
    }
    if (snapshot.state == PairingState.PENDING && snapshot.phase == PairingPhase.CLEANUP) {
        val cleanup = snapshot.cleanup ?: return false
        initializeDefaultFirebase(context, cleanup)
        FirebaseMessaging.getInstance().isAutoInitEnabled = false
        return true
    }
    if (target != null) {
        initializeDefaultFirebase(context, target)
        FirebaseMessaging.getInstance().isAutoInitEnabled = true
        return true
    }
    runCatching { FirebaseMessaging.getInstance().isAutoInitEnabled = false }
    return false
}

fun defaultFirebaseMatches(snapshot: DirectPairingSnapshot): Boolean {
    val target = when {
        snapshot.state == PairingState.ACTIVE -> snapshot.active
        snapshot.state == PairingState.PENDING && snapshot.phase == PairingPhase.SUBSCRIBE -> snapshot.candidate
        else -> null
    } ?: return false
    val app = runCatching { FirebaseApp.getInstance() }.getOrNull() ?: return false
    return app.options.projectId == target.projectId &&
        app.options.applicationId == target.firebaseAppId &&
        app.options.gcmSenderId == target.gcmSenderId &&
        app.options.apiKey == target.apiKey
}

private suspend fun cleanupSubscription(
    context: Context,
    cleanup: CleanupSubscription,
    store: DirectPairingStore,
    revision: Long
) {
    requireCurrent(store, revision, PairingPhase.CLEANUP)
    val app = initializeDefaultFirebase(context, cleanup)
    val messaging = FirebaseMessaging.getInstance()
    messaging.isAutoInitEnabled = false
    messaging.unsubscribeFromTopic(cleanup.topic).awaitTask()
    messaging.deleteToken().awaitTask()
    FirebaseInstallations.getInstance(app).delete().awaitTask()
    requireCurrent(store, revision, PairingPhase.CLEANUP)
    app.delete()
}

@Synchronized
private fun initializeDefaultFirebase(context: Context, invite: DirectFcmInvite): FirebaseApp =
    initializeDefaultFirebase(context, invite.projectId, invite.firebaseAppId, invite.apiKey, invite.gcmSenderId)

@Synchronized
private fun initializeDefaultFirebase(context: Context, cleanup: CleanupSubscription): FirebaseApp =
    initializeDefaultFirebase(context, cleanup.projectId, cleanup.firebaseAppId, cleanup.apiKey, cleanup.gcmSenderId)

@Synchronized
private fun initializeDefaultFirebase(
    context: Context,
    projectId: String,
    firebaseAppId: String,
    apiKey: String,
    gcmSenderId: String
): FirebaseApp {
    runCatching { FirebaseApp.getInstance() }.getOrNull()?.let { current ->
        val options = current.options
        if (options.projectId == projectId && options.applicationId == firebaseAppId && options.gcmSenderId == gcmSenderId && options.apiKey == apiKey) return current
        throw IllegalStateException("預設 Firebase App 與目前配對不一致")
    }
    val options = FirebaseOptions.Builder()
        .setProjectId(projectId)
        .setApplicationId(firebaseAppId)
        .setApiKey(apiKey)
        .setGcmSenderId(gcmSenderId)
        .build()
    return checkNotNull(FirebaseApp.initializeApp(context, options))
}

private fun requireCurrent(store: DirectPairingStore, revision: Long, phase: PairingPhase) {
    val current = store.snapshot()
    check(current.localPairingRevision == revision && current.state == PairingState.PENDING && current.phase == phase) {
        "配對版本已變更"
    }
}

private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
