package com.reelix.app.data.alist

import android.content.Context
import android.util.AtomicFile
import com.reelix.app.data.model.MediaItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import com.reelix.app.data.image.applyManualLibraryArtwork
import com.reelix.app.data.image.manualArtworkUrlAllowed
import com.reelix.app.data.image.LibraryWorkIdentity
import com.reelix.app.data.image.LibraryWorkMetadata
import com.reelix.app.data.image.applyLibraryMetadata
import com.reelix.app.data.image.sameLibraryWork
import com.reelix.app.data.image.libraryMetadataApplies
import com.reelix.app.data.image.ArtworkCommitGate

/** Account-scoped user edits are independent of disposable scan/image caches. IO callers only. */
class LibraryCustomizationStore(context: Context, server: String, username: String, root: String) {
    @Serializable data class Cover(val poster: String, val backdrop: String? = null, val identity: LibraryWorkIdentity? = null)
    @Serializable data class Saved(val rules: Map<String, DirectoryMode> = emptyMap(), val covers: Map<String, Cover> = emptyMap(),
        val metadata: Map<String, LibraryWorkMetadata> = emptyMap(), val resourceChoices: Map<String, String> = emptyMap())
    private val directory = File(context.applicationContext.filesDir, "library-artwork").apply { mkdirs() }
    private val identity = "${normalizeLibraryServer(server)}|$username|${normalizeLibraryRoot(root)}"
    private val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
    val artworkAccountKey: String get() = digest
    private val file = AtomicFile(File(directory, "$digest.json"))
    private val json = Json { ignoreUnknownKeys = true }
    private var saved: Saved = runCatching { file.openRead().bufferedReader().use { json.decodeFromString<Saved>(it.readText()) } }.getOrDefault(Saved())
    @Synchronized fun rules(): Map<String, DirectoryMode> = saved.rules.toMap()
    @Synchronized fun setMode(path: String, mode: DirectoryMode?) {
        val next = saved.rules.toMutableMap().apply { if (mode == null) remove(path) else put(path, mode) }
        persist(saved.copy(rules = next))
    }
    @Synchronized fun apply(item: MediaItem): MediaItem {
        val metadata = metadata(item.id)?.takeIf { libraryMetadataApplies(item, it, binding(item.id)) }
        val base = (if (metadata == null) item else applyLibraryMetadata(item, metadata)).copy(preferredResourcePath = saved.resourceChoices[item.id])
        val cover = saved.covers[item.id] ?: return base
        val poster = artworkValue(cover.poster) ?: return base
        return applyManualLibraryArtwork(base, poster, cover.backdrop?.let(::artworkValue))
    }
    @Synchronized fun binding(id: String): LibraryWorkIdentity? = saved.covers[id]?.identity
    @Synchronized fun coverOnly(id: String): Boolean = saved.covers[id]?.let { it.identity == null } == true
    /** 手动换图后资料跟随用户选择，不再比对海报绑定身份。 */
    @Synchronized fun metadata(id: String): LibraryWorkMetadata? = saved.metadata[id]
    @Synchronized fun setMetadata(id: String, record: LibraryWorkMetadata) {
        persist(saved.copy(metadata = saved.metadata + (id to record)))
    }
    @Synchronized fun setResourceChoice(id: String, path: String) {
        persist(saved.copy(resourceChoices = saved.resourceChoices + (id to path)))
    }
    /** Drops every local edit for a work that no longer exists on the drive. */
    @Synchronized fun removeWork(id: String) {
        val cover = saved.covers[id]
        val next = saved.copy(
            covers = saved.covers - id,
            metadata = saved.metadata - id,
            resourceChoices = saved.resourceChoices - id,
        )
        if (next != saved) persist(next)
        cover?.let {
            deleteLocalArtwork(it.poster)
            it.backdrop?.let(::deleteLocalArtwork)
        }
    }
    @Synchronized private fun persist(next: Saved) {
        synchronized(maintenanceLock) { persistLocked(next) }
    }
    private fun persistLocked(next: Saved) {
        val output = file.startWrite()
        try { output.write(json.encodeToString(next).toByteArray()); file.finishWrite(output); saved = next }
        catch (e: Exception) { file.failWrite(output); throw e }
    }
    suspend fun saveCover(id: String, posterUrl: String, backdropUrl: String?, identity: LibraryWorkIdentity? = null,
        ticket: ArtworkCommitGate.Ticket? = null): Boolean {
        require(manualArtworkUrlAllowed(posterUrl)) { "Invalid image URL" }
        if (backdropUrl != null) require(manualArtworkUrlAllowed(backdropUrl)) { "Invalid image URL" }
        return synchronized(this) {
            val persistCover = {
                val previous = saved.metadata[id]
                val metadata = if (identity != null && previous != null && !sameLibraryWork(identity, previous.identity)) {
                    saved.metadata - id
                } else {
                    saved.metadata
                }
                persist(saved.copy(covers = saved.covers + (id to Cover(posterUrl, backdropUrl, identity)), metadata = metadata))
            }
            ticket?.commit(persistCover) ?: run { persistCover(); true }
        }
    }

    private fun artworkValue(value: String): String? {
        if (value.startsWith("http://", true) || value.startsWith("https://", true)) return value
        val file = File(value)
        return if (file.isFile) file.toURI().toString() else null
    }

    private fun deleteLocalArtwork(value: String) {
        if (!value.startsWith("http://", true) && !value.startsWith("https://", true)) {
            runCatching { File(value).delete() }
        }
    }
    companion object {
        private val maintenanceLock = Any()
        private val inFlight = mutableSetOf<String>()
        fun cleanupUnused(context: Context): Int = synchronized(maintenanceLock) {
            reclaim(File(context.applicationContext.filesDir, "library-artwork"))
        }
        /** Any unreadable manifest makes reference knowledge incomplete, so reclaim nothing. */
        private fun reclaim(directory: File): Int {
            val files = directory.listFiles() ?: return 0
            val manifests = files.filter { it.name.endsWith(".json") || it.name.endsWith(".json.bak") }
            val decoder = Json { ignoreUnknownKeys = true }
            val references = mutableSetOf<String>()
            for (manifest in manifests) {
                val saved = runCatching { decoder.decodeFromString<Saved>(manifest.readText()) }.getOrNull() ?: return 0
                saved.covers.values.forEach { cover ->
                    localArtworkName(cover.poster)?.let { references += it }
                    cover.backdrop?.let { localArtworkName(it)?.let { name -> references += name } }
                }
            }
            return files.count { it.isFile && com.reelix.app.data.image.manualArtworkCanBeReclaimed(it.name, references, inFlight) && it.delete() }
        }

        private fun localArtworkName(value: String): String? =
            if (value.startsWith("http://", true) || value.startsWith("https://", true)) null
            else File(value).name
    }
}
