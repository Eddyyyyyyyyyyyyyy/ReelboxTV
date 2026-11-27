package com.reelix.app.data.image

import android.content.Context
import android.widget.Toast
import com.reelix.app.R
import com.reelix.app.data.model.MediaItem
import com.reelix.app.data.repo.LibraryRepository
import kotlinx.coroutines.*

/** Retains application context and data only. Leaving a page never retains its Activity or Dialog. */
class LibraryArtworkDownloads private constructor(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /**
     * 换图成功后的回调。
     *
     * ★ 片库换图是异步的：对话框提交后立刻关闭，真正写盘在后台。
     *   没有这个回调，详情页的资料刷新逻辑永远收不到"图已经换了"的通知，
     *   用户看到的就是"海报换了，简介/演职员还是旧的"。
     */
    var onSucceeded: ((id: String) -> Unit)? = null
    private val queue = ArtworkDownloadQueue(scope) { status ->
        val message = if (status.phase == ArtworkDownloadPhase.SUCCEEDED) R.string.library_artwork_download_done
            else R.string.library_artwork_download_failed
        Toast.makeText(app, app.getString(message, status.title), Toast.LENGTH_LONG).show()
    }
    val states get() = queue.states
    fun submit(repo: LibraryRepository, item: MediaItem, candidate: LibraryArtworkCandidate, matchMetadata: Boolean) {
        queue.submit(repo.artworkTaskKey(item.id), item.title) { ticket ->
            val saved = repo.setCover(item.id, candidate.poster, candidate.backdrop,
                if (matchMetadata) candidate.identity else null, ticket)
            if (saved) onSucceeded?.invoke(item.id)
            saved
        }
    }
    fun retry(key: String): Boolean = queue.retry(key)

    companion object {
        @Volatile private var instance: LibraryArtworkDownloads? = null
        fun get(context: Context): LibraryArtworkDownloads = instance ?: synchronized(this) {
            instance ?: LibraryArtworkDownloads(context).also { instance = it }
        }
    }
}
