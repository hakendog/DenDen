package com.tensal.denden.branding

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import com.tensal.denden.setup.DirectPairingStore
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Base64

class DirectBrandStore(context: Context) {
    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "direct-branding")
    private val staging = File(root, "staging")
    private val active = File(root, "active")
    private val prefs = appContext.getSharedPreferences("direct_branding", Context.MODE_PRIVATE)

    fun activatePairing(pairingId: String) = synchronized(LOCK) { ensurePairing(pairingId) }

    fun clearPairing() = synchronized(LOCK) {
        staging.deleteRecursively()
        active.deleteRecursively()
        root.listFiles()?.filter { it.isDirectory && it.name.startsWith("revision-") }?.forEach(File::deleteRecursively)
        check(prefs.edit().clear().commit()) { "無法清除舊品牌資料" }
    }

    fun accept(pairingId: String, kind: String, payload: JSONObject, nowMillis: Long = System.currentTimeMillis()): Boolean = synchronized(LOCK) {
        try {
            ensurePairing(pairingId)
            cleanupExpired(nowMillis)
            when (kind) {
                "brand-reset" -> stageReset(payload)
                "brand-manifest" -> stageManifest(payload, nowMillis)
                "brand-chunk" -> stageChunk(payload, nowMillis)
                else -> false
            }
        } catch (error: Exception) {
            prefs.edit().putString(LAST_ERROR_KEY, error.message?.take(160) ?: "外觀接收失敗").commit()
            throw error
        }
    }

    fun load(expectedPairingId: String? = currentPairingId()): CachedBranding? = synchronized(LOCK) {
        if (!matchesPairing(expectedPairingId)) return null
        val revision = prefs.getString(REVISION_KEY, null) ?: return null
        val directory = activeDirectory(revision)
        val mascot = decode(File(directory, "mascot.png"), 512, 512) ?: return null
        val shortcut = decode(File(directory, "shortcut.png"), 432, 432) ?: return null
        val brandColorValue = prefs.getString(BRAND_COLOR_KEY, null)
        val backgroundColorValue = prefs.getString(BACKGROUND_COLOR_KEY, null)
        val brandColor = brandColorValue?.let { runCatching { Color.parseColor(it) }.getOrNull() ?: return null }
        val backgroundColor = backgroundColorValue?.let { runCatching { Color.parseColor(it) }.getOrNull() ?: return null }
        CachedBranding(revision, brandColor, backgroundColor, mascot, shortcut)
    }

    fun candidate(expectedPairingId: String? = currentPairingId()): DirectBrandCandidate? = synchronized(LOCK) {
        if (!matchesPairing(expectedPairingId)) return null
        val generation = prefs.getLong(CANDIDATE_GENERATION_KEY, 0).takeIf { it > 0 } ?: return null
        if (prefs.getString(CANDIDATE_CONTENT_DIGEST_KEY, null) == null) {
            return discardInvalidCandidate("外觀候選狀態不完整，請重送")
        }
        if (prefs.getBoolean(CANDIDATE_RESET_KEY, false)) {
            return DirectBrandCandidate(generation, null, true, null, null, null)
        }
        val revision = prefs.getString(CANDIDATE_REVISION_KEY, null)
            ?: return discardInvalidCandidate("外觀候選狀態不完整，請重送")
        val mascot = decode(File(File(root, "revision-$revision"), "mascot.png"), 512, 512)
            ?: return discardInvalidCandidate("外觀候選檔案無效，請重送")
        val brandColorValue = prefs.getString(CANDIDATE_BRAND_COLOR_KEY, null)
        val backgroundColorValue = prefs.getString(CANDIDATE_BACKGROUND_COLOR_KEY, null)
        val brandColor = brandColorValue?.let { runCatching { Color.parseColor(it) }.getOrNull() }
        val backgroundColor = backgroundColorValue?.let { runCatching { Color.parseColor(it) }.getOrNull() }
        if (brandColorValue != null && brandColor == null || backgroundColorValue != null && backgroundColor == null) {
            return discardInvalidCandidate("外觀候選顏色無效，請重送")
        }
        DirectBrandCandidate(
            generation = generation,
            revision = revision,
            isReset = false,
            brandColor = brandColor,
            backgroundColor = backgroundColor,
            mascot = mascot
        )
    }

    fun applyCandidate(expectedPairingId: String? = currentPairingId()): Boolean = synchronized(LOCK) {
        try {
            if (!matchesPairing(expectedPairingId)) return false
            val hadCandidate = prefs.getLong(CANDIDATE_GENERATION_KEY, 0) > 0
            val candidate = candidate(expectedPairingId)
            if (candidate == null) {
                if (hadCandidate) error(prefs.getString(LAST_ERROR_KEY, null) ?: "外觀候選無效，請重送")
                return false
            }
            val contentDigest = requireNotNull(prefs.getString(CANDIDATE_CONTENT_DIGEST_KEY, null))
            if (!candidate.isReset) {
                val revision = requireNotNull(candidate.revision)
                require(File(root, "revision-$revision").isDirectory) { "外觀候選檔案遺失" }
            }
            val editor = prefs.edit().putLong(GENERATION_KEY, candidate.generation)
                .putString(CONTENT_DIGEST_KEY, contentDigest)
                .putBoolean(SHORTCUT_PENDING_KEY, true)
                .remove(CANDIDATE_GENERATION_KEY)
                .remove(CANDIDATE_REVISION_KEY)
                .remove(CANDIDATE_CONTENT_DIGEST_KEY)
                .remove(CANDIDATE_RESET_KEY)
                .remove(CANDIDATE_BRAND_COLOR_KEY)
                .remove(CANDIDATE_BACKGROUND_COLOR_KEY)
                .remove(LAST_ERROR_KEY)
            if (candidate.isReset) {
                editor.remove(REVISION_KEY).remove(BRAND_COLOR_KEY).remove(BACKGROUND_COLOR_KEY)
            } else {
                editor.putString(REVISION_KEY, candidate.revision)
                if (candidate.brandColor == null) editor.remove(BRAND_COLOR_KEY)
                else editor.putString(BRAND_COLOR_KEY, colorString(candidate.brandColor))
                if (candidate.backgroundColor == null) editor.remove(BACKGROUND_COLOR_KEY)
                else editor.putString(BACKGROUND_COLOR_KEY, colorString(candidate.backgroundColor))
            }
            check(editor.commit()) { "外觀套用狀態保存失敗" }
            val activeRevision = candidate.revision
            root.listFiles()?.filter {
                it.isDirectory && it.name.startsWith("revision-") && it.name != "revision-$activeRevision"
            }?.forEach(File::deleteRecursively)
            active.deleteRecursively()
            true
        } catch (error: Exception) {
            prefs.edit().putString(LAST_ERROR_KEY, error.message?.take(160) ?: "外觀套用失敗").commit()
            throw error
        }
    }

    fun rejectCandidate(expectedPairingId: String? = currentPairingId()): Boolean = synchronized(LOCK) {
        val candidate = candidate(expectedPairingId) ?: return false
        check(prefs.edit()
            .putLong(REJECTED_GENERATION_KEY, maxOf(prefs.getLong(REJECTED_GENERATION_KEY, 0), candidate.generation))
            .remove(CANDIDATE_GENERATION_KEY)
            .remove(CANDIDATE_REVISION_KEY)
            .remove(CANDIDATE_CONTENT_DIGEST_KEY)
            .remove(CANDIDATE_RESET_KEY)
            .remove(CANDIDATE_BRAND_COLOR_KEY)
            .remove(CANDIDATE_BACKGROUND_COLOR_KEY)
            .remove(LAST_ERROR_KEY)
            .commit()) { "外觀拒絕狀態保存失敗" }
        candidate.revision?.takeIf { it != prefs.getString(REVISION_KEY, null) }
            ?.let { File(root, "revision-$it").deleteRecursively() }
        true
    }

    fun activeStatusMask(expectedPairingId: String? = currentPairingId()): Bitmap? = synchronized(LOCK) {
        prefs.getString(REVISION_KEY, null)
            ?.takeIf { matchesPairing(expectedPairingId) }
            ?.let(::activeDirectory)?.let { decode(File(it, "status-mask.png"), 96, 96) }
    }

    fun activeBrandColor(expectedPairingId: String? = currentPairingId()): Int? = synchronized(LOCK) {
        if (!matchesPairing(expectedPairingId) || prefs.getString(REVISION_KEY, null) == null) return@synchronized null
        prefs.getString(BRAND_COLOR_KEY, null)?.let { runCatching { Color.parseColor(it) }.getOrNull() }
    }

    fun pendingShortcutUpdate(expectedPairingId: String? = currentPairingId()): DirectShortcutUpdate? = synchronized(LOCK) {
        if (!prefs.getBoolean(SHORTCUT_PENDING_KEY, false) || !matchesPairing(expectedPairingId)) return@synchronized null
        DirectShortcutUpdate(
            pairingFingerprint = prefs.getString(PAIRING_KEY, null) ?: return@synchronized null,
            generation = highWater(),
            revision = prefs.getString(REVISION_KEY, null)
        )
    }

    fun shortcutUpdatePending(expectedPairingId: String? = currentPairingId()): Boolean = pendingShortcutUpdate(expectedPairingId) != null

    fun markShortcutUpdated(expected: DirectShortcutUpdate): Boolean = synchronized(LOCK) {
        if (!prefs.getBoolean(SHORTCUT_PENDING_KEY, false)) return@synchronized false
        val current = DirectShortcutUpdate(
            pairingFingerprint = prefs.getString(PAIRING_KEY, null) ?: return@synchronized false,
            generation = highWater(),
            revision = prefs.getString(REVISION_KEY, null)
        )
        if (current != expected) return@synchronized false
        prefs.edit().putBoolean(SHORTCUT_PENDING_KEY, false).commit()
    }

    fun status(expectedPairingId: String? = currentPairingId()): DirectBrandStatus = synchronized(LOCK) {
        if (!matchesPairing(expectedPairingId)) {
            return@synchronized DirectBrandStatus(false, 0, null, null, 0, 0, false)
        }
        val transfers = staging.listFiles()?.filter(File::isDirectory).orEmpty()
        val latest = transfers.maxByOrNull { it.lastModified() }
        val manifest = latest?.let { File(it, "manifest.json") }?.takeIf(File::isFile)?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
        val received = latest?.listFiles()?.count { it.name.startsWith("chunk-") } ?: 0
        DirectBrandStatus(
            isCustom = prefs.getString(REVISION_KEY, null) != null,
            generation = highWater(),
            activeRevisionFingerprint = prefs.getString(REVISION_KEY, null)?.let { sha256(it.toByteArray()).take(12) },
            receivingTransferFingerprint = latest?.name?.take(12),
            receivedChunks = received,
            totalChunks = manifest?.optInt("chunkCount", 0) ?: 0,
            shortcutUpdatePending = shortcutUpdatePending(expectedPairingId),
            candidateGeneration = prefs.getLong(CANDIDATE_GENERATION_KEY, 0).takeIf { it > 0 },
            lastError = prefs.getString(LAST_ERROR_KEY, null)
        )
    }

    private fun stageManifest(payload: JSONObject, nowMillis: Long): Boolean {
        require(payload.optInt("version", -1) == 2 && payload.optString("type") == "brand-manifest") { "品牌清單格式無效" }
        val transferId = payload.transferId()
        val generation = payload.generation()
        val expires = payload.expiresAt()
        require(expires >= nowMillis && generation >= highWater()) { "品牌清單已過期或世代過舊" }
        val count = payload.optInt("chunkCount", -1)
        val length = payload.optInt("byteLength", -1)
        val digest = payload.optString("sha256")
        require(count in 1..64 && length in 1..MAX_IMAGE_BYTES && digest.matches(Regex("^[a-f0-9]{64}$"))) { "品牌清單範圍無效" }
        validateManifestColors(payload)
        val directory = stageDirectory(transferId, expires, generation)
        return withStagingCleanup(directory) {
            val manifest = File(directory, "manifest.json")
            val canonical = payload.toString()
            if (manifest.exists()) require(manifest.readText() == canonical) { "品牌清單衝突" }
            else writeAtomic(manifest, canonical.toByteArray())
            tryCommit(directory, generation)
        }
    }

    private fun stageChunk(payload: JSONObject, nowMillis: Long): Boolean {
        require(payload.optInt("version", -1) == 2 && payload.optString("type") == "brand-chunk") { "品牌分片格式無效" }
        val transferId = payload.transferId()
        val generation = payload.generation()
        val expires = payload.expiresAt()
        val index = payload.optInt("index", -1)
        val count = payload.optInt("chunkCount", -1)
        require(expires >= nowMillis && generation >= highWater() && count in 1..64 && index in 0 until count) { "品牌分片範圍無效" }
        val bytes = runCatching { Base64.getUrlDecoder().decode(payload.getString("data")) }
            .getOrElse { throw IllegalArgumentException("品牌分片無法解碼") }
        require(bytes.isNotEmpty() && bytes.size <= 1024) { "品牌分片大小無效" }
        val directory = stageDirectory(transferId, expires, generation)
        return withStagingCleanup(directory) {
            val meta = File(directory, "chunk-meta.json")
            val expected = JSONObject()
                .put("generation", generation)
                .put("chunkCount", count)
                .put("expiresAtMillis", expires)
            if (meta.exists()) require(meta.readText() == expected.toString()) { "品牌分片中繼資料衝突" }
            else writeAtomic(meta, expected.toString().toByteArray())
            val target = File(directory, "chunk-$index")
            if (target.exists()) require(target.readBytes().contentEquals(bytes)) { "品牌分片內容衝突" }
            else writeAtomic(target, bytes)
            require(staging.walkTopDown().filter(File::isFile).sumOf(File::length) <= MAX_STAGING_BYTES) {
                "品牌暫存超過上限"
            }
            tryCommit(directory, generation)
        }
    }

    private fun tryCommit(directory: File, generation: Long): Boolean {
        val manifestFile = File(directory, "manifest.json")
        if (!manifestFile.isFile) return false
        val manifest = JSONObject(manifestFile.readText())
        val count = manifest.getInt("chunkCount")
        val meta = File(directory, "chunk-meta.json").takeIf(File::isFile)?.let { JSONObject(it.readText()) } ?: return false
        require(
            manifest.getLong("generation") == generation &&
                meta.getLong("generation") == generation &&
                meta.getInt("chunkCount") == count &&
                meta.getLong("expiresAtMillis") == manifest.getLong("expiresAtMillis")
        ) { "品牌清單與分片不一致" }
        val chunks = (0 until count).map { File(directory, "chunk-$it") }
        if (chunks.any { !it.isFile }) return false
        val bytes = chunks.flatMap { it.readBytes().asIterable() }.toByteArray()
        require(bytes.size == manifest.getInt("byteLength")) { "品牌圖片長度不符" }
        val digest = sha256(bytes)
        require(digest == manifest.getString("sha256")) { "品牌圖片雜湊不符" }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        require(bitmap != null && bitmap.width == 512 && bitmap.height == 512 && bitmap.hasTransparentPixel()) { "品牌圖片格式無效" }
        val revision = "$generation-$digest"
        val contentDigest = sha256(manifestFile.readBytes())
        val previousGeneration = highWater()
        if (generation == previousGeneration) {
            val sameCandidate = prefs.getLong(CANDIDATE_GENERATION_KEY, 0) == generation &&
                prefs.getString(CANDIDATE_REVISION_KEY, null) == revision &&
                prefs.getString(CANDIDATE_CONTENT_DIGEST_KEY, null) == contentDigest
            val sameActive = prefs.getLong(GENERATION_KEY, 0) == generation &&
                prefs.getString(REVISION_KEY, null) == revision &&
                prefs.getString(CONTENT_DIGEST_KEY, null) == contentDigest
            require(sameCandidate || sameActive) { "品牌世代衝突" }
            directory.deleteRecursively()
            return true
        }
        val candidateDirectory = File(root, "revision-$revision.tmp").apply { deleteRecursively(); mkdirs() }
        val brandColor = manifest.colorOrNull("brandColor")
        val backgroundColor = manifest.colorOrNull("backgroundColor")
        val shortcutBackground = backgroundColor ?: brandColor ?: DEFAULT_SHORTCUT_BACKGROUND
        File(candidateDirectory, "mascot.png").writeBytes(bytes)
        createStatusMask(bitmap).writePng(File(candidateDirectory, "status-mask.png"))
        createShortcut(bitmap, Color.parseColor(shortcutBackground)).writePng(File(candidateDirectory, "shortcut.png"))
        val committed = File(root, "revision-$revision")
        if (!committed.exists()) check(candidateDirectory.renameTo(committed)) { "品牌候選保存失敗" }
        else candidateDirectory.deleteRecursively()
        val previousCandidateRevision = prefs.getString(CANDIDATE_REVISION_KEY, null)
        val editor = prefs.edit()
            .putLong(CANDIDATE_GENERATION_KEY, generation)
            .putString(CANDIDATE_REVISION_KEY, revision)
            .putString(CANDIDATE_CONTENT_DIGEST_KEY, contentDigest)
            .putBoolean(CANDIDATE_RESET_KEY, false)
            .remove(LAST_ERROR_KEY)
        if (brandColor == null) editor.remove(CANDIDATE_BRAND_COLOR_KEY)
        else editor.putString(CANDIDATE_BRAND_COLOR_KEY, brandColor)
        if (backgroundColor == null) editor.remove(CANDIDATE_BACKGROUND_COLOR_KEY)
        else editor.putString(CANDIDATE_BACKGROUND_COLOR_KEY, backgroundColor)
        check(editor.commit()) { "品牌候選狀態保存失敗" }
        previousCandidateRevision?.takeIf {
            it != revision && it != prefs.getString(REVISION_KEY, null)
        }?.let { File(root, "revision-$it").deleteRecursively() }
        directory.deleteRecursively()
        return true
    }

    private fun stageReset(payload: JSONObject): Boolean {
        require(payload.optInt("version", -1) == 2 && payload.optString("type") == "brand-reset") { "品牌重設格式無效" }
        val generation = payload.generation()
        val contentDigest = sha256(payload.toString().toByteArray())
        require(generation >= highWater()) { "品牌重設世代過舊" }
        if (generation == highWater()) {
            val sameCandidate = prefs.getLong(CANDIDATE_GENERATION_KEY, 0) == generation &&
                prefs.getBoolean(CANDIDATE_RESET_KEY, false) &&
                prefs.getString(CANDIDATE_CONTENT_DIGEST_KEY, null) == contentDigest
            val sameActive = prefs.getLong(GENERATION_KEY, 0) == generation &&
                prefs.getString(REVISION_KEY, null) == null &&
                prefs.getString(CONTENT_DIGEST_KEY, null) == contentDigest
            require(sameCandidate || sameActive) { "品牌重設世代衝突" }
            return true
        }
        val previousCandidateRevision = prefs.getString(CANDIDATE_REVISION_KEY, null)
        check(prefs.edit()
            .putLong(CANDIDATE_GENERATION_KEY, generation)
            .remove(CANDIDATE_REVISION_KEY)
            .putString(CANDIDATE_CONTENT_DIGEST_KEY, contentDigest)
            .putBoolean(CANDIDATE_RESET_KEY, true)
            .remove(CANDIDATE_BRAND_COLOR_KEY)
            .remove(CANDIDATE_BACKGROUND_COLOR_KEY)
            .remove(LAST_ERROR_KEY)
            .commit()) { "品牌重設候選保存失敗" }
        previousCandidateRevision?.takeIf { it != prefs.getString(REVISION_KEY, null) }
            ?.let { File(root, "revision-$it").deleteRecursively() }
        staging.deleteRecursively()
        return true
    }

    private fun stageDirectory(transferId: String, expiresAtMillis: Long, generation: Long): File {
        staging.mkdirs()
        val directory = File(staging, sha256(transferId.toByteArray()))
        if (!directory.exists()) {
            staging.listFiles()?.filter(File::isDirectory)?.filter { stagedGeneration(it) < generation }
                ?.forEach(File::deleteRecursively)
            require(staging.listFiles()?.count(File::isDirectory) ?: 0 < 2) { "同時品牌傳輸超過上限" }
            check(directory.mkdirs()) { "無法建立品牌暫存" }
            writeAtomic(File(directory, "expires"), expiresAtMillis.toString().toByteArray())
        }
        return directory
    }

    private fun cleanupExpired(nowMillis: Long) {
        staging.listFiles()?.filter(File::isDirectory)?.forEach { directory ->
            val expires = File(directory, "expires").takeIf(File::isFile)?.readText()?.toLongOrNull() ?: 0
            if (expires < nowMillis) directory.deleteRecursively()
        }
    }

    private fun stagedGeneration(directory: File): Long =
        listOf("manifest.json", "chunk-meta.json").firstNotNullOfOrNull { name ->
            File(directory, name).takeIf(File::isFile)?.let {
                runCatching { JSONObject(it.readText()).optLong("generation", -1) }.getOrNull()
            }
        } ?: -1

    private inline fun <T> withStagingCleanup(directory: File, block: () -> T): T = try {
        block()
    } catch (error: Exception) {
        directory.deleteRecursively()
        throw error
    }

    private fun highWater(): Long = maxOf(
        prefs.getLong(GENERATION_KEY, 0),
        prefs.getLong(CANDIDATE_GENERATION_KEY, 0),
        prefs.getLong(REJECTED_GENERATION_KEY, 0)
    )

    private fun discardInvalidCandidate(message: String): DirectBrandCandidate? {
        val revision = prefs.getString(CANDIDATE_REVISION_KEY, null)
        check(prefs.edit()
            .remove(CANDIDATE_GENERATION_KEY)
            .remove(CANDIDATE_REVISION_KEY)
            .remove(CANDIDATE_CONTENT_DIGEST_KEY)
            .remove(CANDIDATE_RESET_KEY)
            .remove(CANDIDATE_BRAND_COLOR_KEY)
            .remove(CANDIDATE_BACKGROUND_COLOR_KEY)
            .putString(LAST_ERROR_KEY, message)
            .commit()) { "外觀候選錯誤狀態保存失敗" }
        revision?.takeIf { it != prefs.getString(REVISION_KEY, null) }
            ?.let { File(root, "revision-$it").deleteRecursively() }
        return null
    }

    private fun ensurePairing(pairingId: String) {
        val pairingFingerprint = sha256(pairingId.toByteArray())
        val existing = prefs.getString(PAIRING_KEY, null)
        if (existing == pairingFingerprint) return
        staging.deleteRecursively()
        active.deleteRecursively()
        root.listFiles()?.filter { it.isDirectory && it.name.startsWith("revision-") }?.forEach(File::deleteRecursively)
        check(prefs.edit().clear().putString(PAIRING_KEY, pairingFingerprint).commit()) { "品牌配對命名空間切換失敗" }
    }

    private fun matchesPairing(pairingId: String?): Boolean = pairingId != null &&
        prefs.getString(PAIRING_KEY, null) == sha256(pairingId.toByteArray())

    private fun currentPairingId(): String? = DirectPairingStore(appContext).snapshot().active?.pairingId

    private fun activeDirectory(revision: String): File {
        val versioned = File(root, "revision-$revision")
        return if (versioned.isDirectory) versioned else active
    }

    private fun JSONObject.transferId(): String = getString("transferId").also {
        require(it.matches(Regex("^[A-Za-z0-9_-]{16,86}$"))) { "transferId 無效" }
    }

    private fun JSONObject.generation(): Long = optLong("generation", -1).also { require(it > 0) { "generation 無效" } }
    private fun JSONObject.expiresAt(): Long = optLong("expiresAtMillis", -1).also { require(it > 0) { "期限無效" } }

    private fun decode(file: File, width: Int, height: Int): Bitmap? = BitmapFactory.decodeFile(file.absolutePath)
        ?.takeIf { it.width == width && it.height == height }

    companion object {
        const val ACTION_BRAND_CHANGED = "com.tensal.denden.BRAND_CHANGED"
        private val LOCK = Any()
        private const val MAX_IMAGE_BYTES = 64 * 1024
        private const val MAX_STAGING_BYTES = 128 * 1024L
        private const val GENERATION_KEY = "generation"
        private const val REVISION_KEY = "revision"
        private const val CONTENT_DIGEST_KEY = "content_digest"
        private const val BRAND_COLOR_KEY = "brand_color"
        private const val BACKGROUND_COLOR_KEY = "background_color"
        private const val CANDIDATE_GENERATION_KEY = "candidate_generation"
        private const val CANDIDATE_REVISION_KEY = "candidate_revision"
        private const val CANDIDATE_CONTENT_DIGEST_KEY = "candidate_content_digest"
        private const val CANDIDATE_RESET_KEY = "candidate_reset"
        private const val CANDIDATE_BRAND_COLOR_KEY = "candidate_brand_color"
        private const val CANDIDATE_BACKGROUND_COLOR_KEY = "candidate_background_color"
        private const val REJECTED_GENERATION_KEY = "rejected_generation"
        private const val LAST_ERROR_KEY = "last_error"
        private const val DEFAULT_SHORTCUT_BACKGROUND = "#005FCC"
        private const val PAIRING_KEY = "pairing_fingerprint"
        private const val SHORTCUT_PENDING_KEY = "shortcut_update_pending"
    }

}

data class DirectBrandStatus(
    val isCustom: Boolean,
    val generation: Long,
    val activeRevisionFingerprint: String?,
    val receivingTransferFingerprint: String?,
    val receivedChunks: Int,
    val totalChunks: Int,
    val shortcutUpdatePending: Boolean,
    val candidateGeneration: Long? = null,
    val lastError: String? = null
)

data class DirectBrandCandidate(
    val generation: Long,
    val revision: String?,
    val isReset: Boolean,
    val brandColor: Int?,
    val backgroundColor: Int?,
    val mascot: Bitmap?
)

data class DirectShortcutUpdate(
    val pairingFingerprint: String,
    val generation: Long,
    val revision: String?
)

private fun writeAtomic(target: File, bytes: ByteArray) {
    target.parentFile?.mkdirs()
    val temporary = File(target.parentFile, ".${target.name}.tmp-${System.nanoTime()}")
    try {
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        check(temporary.renameTo(target)) { "品牌檔案原子寫入失敗" }
    } finally {
        temporary.delete()
    }
}

private fun createStatusMask(source: Bitmap): Bitmap {
    val scaled = Bitmap.createScaledBitmap(source, 96, 96, true)
    val output = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
    for (y in 0 until 96) for (x in 0 until 96) {
        output.setPixel(x, y, Color.argb(Color.alpha(scaled.getPixel(x, y)), 255, 255, 255))
    }
    return output
}

private fun createShortcut(source: Bitmap, color: Int): Bitmap = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888).also {
    val canvas = Canvas(it)
    canvas.drawColor(color)
    canvas.drawBitmap(Bitmap.createScaledBitmap(source, 432, 432, true), 0f, 0f, null)
}

private fun Bitmap.writePng(file: File) {
    file.outputStream().use { check(compress(Bitmap.CompressFormat.PNG, 100, it)) }
}

private fun Bitmap.hasTransparentPixel(): Boolean {
    for (y in 0 until height) for (x in 0 until width) {
        if (Color.alpha(getPixel(x, y)) < 255) return true
    }
    return false
}

private fun validateManifestColors(payload: JSONObject) {
    require(!payload.has("primaryColor")) { "舊版 primaryColor 不受支援" }
    payload.colorOrNull("brandColor")
    payload.colorOrNull("backgroundColor")
}

private fun JSONObject.colorOrNull(name: String): String? {
    if (!has(name)) return null
    return optString(name).also { require(it.matches(Regex("^#[0-9A-F]{6}$"))) { "$name 無效" } }
}

private fun colorString(color: Int): String = "#%06X".format(color and 0xFFFFFF)

private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(value).joinToString("") { "%02x".format(it) }
