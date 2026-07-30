package com.tensal.denden.setup

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.tensal.denden.messaging.ActivePairingKeys
import com.tensal.denden.protocol.DirectFcmInvite
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.sync.Mutex

internal val directRuntimeMutex = Mutex()

enum class PairingState { UNPAIRED, PENDING, ACTIVE, ERROR }
enum class PairingPhase { CLEANUP, SUBSCRIBE }

data class DirectPairingSnapshot(
    val state: PairingState,
    val phase: PairingPhase?,
    val localPairingRevision: Long,
    val active: DirectFcmInvite?,
    val candidate: DirectFcmInvite?,
    val cleanup: CleanupSubscription?,
    val error: String?
) {
    fun activeKeys(): ActivePairingKeys? = active?.let {
        ActivePairingKeys(it.pairingId, it.eventKeyId, it.eventKey, it.brandKeyId, it.brandKey)
    }
}

data class CleanupSubscription(
    val projectId: String,
    val firebaseAppId: String,
    val apiKey: String,
    val gcmSenderId: String,
    val topic: String
)

class DirectPairingStore(context: Context, preferencesName: String = PREFS) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val secretAliasPrefix = "$preferencesName.secrets"
    private val cleanupCipher = KeystoreCipher("$preferencesName.cleanup")

    fun stage(invite: DirectFcmInvite): Long = synchronized(STORE_LOCK) {
        require(invite.androidPackageName == appContext.packageName) { "QR Code 套件名稱與此 App 不符" }
        val current = snapshot()
        val revision = current.localPairingRevision + 1
        val cleanup = current.cleanup ?: current.active?.toCleanupSubscription()
        val phase = if (cleanup == null) PairingPhase.SUBSCRIBE else PairingPhase.CLEANUP
        val candidateCipher = KeystoreCipher(secretAlias(revision))
        val committed = prefs.edit()
            .remove(ACTIVE_KEY)
            .remove(ACTIVE_REVISION_KEY)
            .putString(CANDIDATE_KEY, candidateCipher.encrypt(invite.toJson().toString()))
            .putLong(CANDIDATE_REVISION_KEY, revision)
            .putString(STATE_KEY, PairingState.PENDING.name)
            .putString(PHASE_KEY, phase.name)
            .putLong(REVISION_KEY, revision)
            .putString(ERROR_KEY, null)
            .applyCleanup(cleanup)
            .commit()
        if (!committed) {
            candidateCipher.deleteKey()
            throw IllegalStateException("無法保存配對狀態")
        }
        deleteSecretKeys(current)
        return revision
    }

    fun markCleanupComplete(revision: Long): Boolean = synchronized(STORE_LOCK) {
        val current = snapshot()
        if (current.localPairingRevision != revision || current.state != PairingState.PENDING || current.phase != PairingPhase.CLEANUP) return false
        val editor = prefs.edit().remove(ACTIVE_KEY).remove(CLEANUP_KEY)
        if (current.candidate == null) {
            editor.putString(STATE_KEY, PairingState.UNPAIRED.name).remove(PHASE_KEY)
        } else {
            editor.putString(PHASE_KEY, PairingPhase.SUBSCRIBE.name)
        }
        val committed = editor.commit()
        if (committed) cleanupCipher.deleteKey()
        return committed
    }

    fun markActive(revision: Long): Boolean = synchronized(STORE_LOCK) {
        val current = snapshot()
        val candidate = current.candidate ?: return false
        if (current.localPairingRevision != revision || current.state != PairingState.PENDING || current.phase != PairingPhase.SUBSCRIBE) return false
        return prefs.edit()
            .putString(ACTIVE_KEY, prefs.getString(CANDIDATE_KEY, null))
            .putLong(ACTIVE_REVISION_KEY, revision)
            .remove(CANDIDATE_KEY)
            .remove(CANDIDATE_REVISION_KEY)
            .remove(CLEANUP_KEY)
            .putString(STATE_KEY, PairingState.ACTIVE.name)
            .remove(PHASE_KEY)
            .remove(ERROR_KEY)
            .commit()
    }

    fun markError(revision: Long, message: String): Boolean = synchronized(STORE_LOCK) {
        val current = snapshot()
        if (current.localPairingRevision != revision || current.state != PairingState.PENDING) return false
        return prefs.edit()
            .putString(STATE_KEY, PairingState.ERROR.name)
            .putString(ERROR_KEY, message.take(200))
            .commit()
    }

    fun retryError(revision: Long): Boolean = synchronized(STORE_LOCK) {
        val current = snapshot()
        if (current.localPairingRevision != revision || current.state != PairingState.ERROR || current.phase == null) return false
        if (current.phase == PairingPhase.CLEANUP && current.cleanup == null) return false
        if (current.phase == PairingPhase.SUBSCRIBE && current.candidate == null) return false
        return prefs.edit().putString(STATE_KEY, PairingState.PENDING.name).remove(ERROR_KEY).commit()
    }

    fun beginClear(): Long = synchronized(STORE_LOCK) {
        val current = snapshot()
        val revision = current.localPairingRevision + 1
        val cleanup = (current.active ?: current.candidate)?.toCleanupSubscription()
        val editor = prefs.edit()
            .remove(ACTIVE_KEY)
            .remove(ACTIVE_REVISION_KEY)
            .remove(CANDIDATE_KEY)
            .remove(CANDIDATE_REVISION_KEY)
            .putLong(REVISION_KEY, revision)
            .remove(ERROR_KEY)
        if (cleanup == null) {
            editor.putString(STATE_KEY, PairingState.UNPAIRED.name).remove(PHASE_KEY).remove(CLEANUP_KEY)
        } else {
            editor.putString(STATE_KEY, PairingState.PENDING.name)
                .putString(PHASE_KEY, PairingPhase.CLEANUP.name)
                .putString(CLEANUP_KEY, cleanupCipher.encrypt(cleanup.toJson().toString()))
        }
        check(editor.commit()) { "無法清除配對狀態" }
        if (cleanup == null) cleanupCipher.deleteKey()
        deleteSecretKeys(current)
        return revision
    }

    fun observeState(onChange: () -> Unit): () -> Unit {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == STATE_KEY) onChange()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun snapshot(): DirectPairingSnapshot = synchronized(STORE_LOCK) {
        fun invite(key: String, revisionKey: String): DirectFcmInvite? = prefs.getString(key, null)?.let { encrypted ->
            val storedRevision = prefs.getLong(revisionKey, prefs.getLong(REVISION_KEY, 0))
            val plaintext = runCatching { KeystoreCipher(secretAlias(storedRevision)).decrypt(encrypted) }
                .recoverCatching { KeystoreCipher(secretAliasPrefix).decrypt(encrypted) }
                .getOrNull() ?: return@let null
            runCatching { directInviteFromJson(JSONObject(plaintext)) }.getOrNull()
        }
        val storedState = prefs.getString(STATE_KEY, null)?.let { runCatching { PairingState.valueOf(it) }.getOrNull() }
            ?: PairingState.UNPAIRED
        val phase = prefs.getString(PHASE_KEY, null)?.let { runCatching { PairingPhase.valueOf(it) }.getOrNull() }
        val cleanup = prefs.getString(CLEANUP_KEY, null)?.let {
            runCatching { cleanupFromJson(JSONObject(cleanupCipher.decrypt(it))) }.getOrNull()
        }
        val active = invite(ACTIVE_KEY, ACTIVE_REVISION_KEY)
        val candidate = invite(CANDIDATE_KEY, CANDIDATE_REVISION_KEY)
        val unreadableSecret = (storedState == PairingState.ACTIVE && prefs.contains(ACTIVE_KEY) && active == null) ||
            (storedState == PairingState.PENDING && prefs.contains(CANDIDATE_KEY) && candidate == null) ||
            (storedState == PairingState.PENDING && phase == PairingPhase.CLEANUP && prefs.contains(CLEANUP_KEY) && cleanup == null)
        return DirectPairingSnapshot(
            if (unreadableSecret) PairingState.ERROR else storedState,
            phase,
            prefs.getLong(REVISION_KEY, 0),
            active,
            candidate,
            cleanup,
            if (unreadableSecret) "配對金鑰無法解密，請重新配對" else prefs.getString(ERROR_KEY, null)
        )
    }

    private fun android.content.SharedPreferences.Editor.applyCleanup(value: CleanupSubscription?) = apply {
        if (value == null) remove(CLEANUP_KEY)
        else putString(CLEANUP_KEY, cleanupCipher.encrypt(value.toJson().toString()))
    }

    private fun secretAlias(revision: Long): String = "$secretAliasPrefix.$revision"

    private fun deleteSecretKeys(snapshot: DirectPairingSnapshot) {
        val revisions = setOf(snapshot.localPairingRevision).filter { it >= 0 }
        revisions.forEach { KeystoreCipher(secretAlias(it)).deleteKey() }
        KeystoreCipher(secretAliasPrefix).deleteKey()
    }

    private companion object {
        val STORE_LOCK = Any()
        const val PREFS = "direct_pairing"
        const val STATE_KEY = "state"
        const val PHASE_KEY = "phase"
        const val REVISION_KEY = "local_revision"
        const val ACTIVE_KEY = "active"
        const val ACTIVE_REVISION_KEY = "active_revision"
        const val CANDIDATE_KEY = "candidate"
        const val CANDIDATE_REVISION_KEY = "candidate_revision"
        const val CLEANUP_KEY = "cleanup"
        const val ERROR_KEY = "error"
    }
}

@OptIn(ExperimentalEncodingApi::class)
private class KeystoreCipher(private val alias: String) {
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keyOrCreate())
        return "${Base64.UrlSafe.encode(cipher.iv)}.${Base64.UrlSafe.encode(cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)))}"
    }

    fun decrypt(value: String): String {
        val parts = value.split('.', limit = 2)
        require(parts.size == 2) { "加密配對資料無效" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, existingKey(), GCMParameterSpec(128, Base64.UrlSafe.decode(parts[0])))
        return String(cipher.doFinal(Base64.UrlSafe.decode(parts[1])), StandardCharsets.UTF_8)
    }

    fun deleteKey() {
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    private fun existingKey(): SecretKey = keyStore.getKey(alias, null) as? SecretKey
        ?: throw IllegalStateException("配對金鑰不存在")

    private fun keyOrCreate(): SecretKey = (keyStore.getKey(alias, null) as? SecretKey) ?: KeyGenerator
        .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        .apply {
            init(KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        }
        .generateKey()
}

private fun DirectFcmInvite.toJson() = JSONObject().apply {
    put("projectId", projectId)
    put("firebaseAppId", firebaseAppId)
    put("apiKey", apiKey)
    put("gcmSenderId", gcmSenderId)
    put("androidPackageName", androidPackageName)
    put("pairingId", pairingId)
    put("topic", topic)
    put("eventKeyId", eventKeyId)
    put("eventKey", eventKey)
    put("brandKeyId", brandKeyId)
    put("brandKey", brandKey)
    put("createdAtMillis", createdAtMillis)
    put("displayExpiresAtMillis", displayExpiresAtMillis)
}

private fun directInviteFromJson(value: JSONObject) = DirectFcmInvite(
    value.getString("projectId"),
    value.getString("firebaseAppId"),
    value.getString("apiKey"),
    value.getString("gcmSenderId"),
    value.getString("androidPackageName"),
    value.getString("pairingId"),
    value.getString("topic"),
    value.getString("eventKeyId"),
    value.getString("eventKey"),
    value.getString("brandKeyId"),
    value.getString("brandKey"),
    value.getLong("createdAtMillis"),
    value.getLong("displayExpiresAtMillis")
)

private fun DirectFcmInvite.toCleanupSubscription() = CleanupSubscription(
    projectId, firebaseAppId, apiKey, gcmSenderId, topic
)

private fun CleanupSubscription.toJson() = JSONObject().apply {
    put("projectId", projectId)
    put("firebaseAppId", firebaseAppId)
    put("apiKey", apiKey)
    put("gcmSenderId", gcmSenderId)
    put("topic", topic)
}

private fun cleanupFromJson(value: JSONObject) = CleanupSubscription(
    value.getString("projectId"),
    value.getString("firebaseAppId"),
    value.getString("apiKey"),
    value.getString("gcmSenderId"),
    value.getString("topic")
)
