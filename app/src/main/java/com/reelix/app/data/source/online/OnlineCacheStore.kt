package com.reelix.app.data.source.online

import android.content.Context
import java.io.File

/**
 * 在线缓存的文件存储。
 *
 * 采用“临时文件写完再替换”：电视可能在看片时被拔电，
 * 半截 JSON 会让下次启动既读不到缓存、也无法自动恢复。
 */
class OnlineCacheStore(context: Context) : OnlineCache.Store {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)
    private val tmp = File(file.parentFile, "$FILE_NAME.tmp")

    override fun read(): String? = runCatching {
        if (!file.exists() || file.length() == 0L) return null
        file.readText(Charsets.UTF_8)
    }.getOrElse {
        AndroidLog.w("OnlineCacheStore", "read failed: ${it.message}")
        null
    }

    override fun write(json: String) {
        val parent = file.parentFile ?: return
        if (!parent.exists()) parent.mkdirs()
        tmp.writeText(json, Charsets.UTF_8)
        // File.renameTo 在同一目录内原子替换；旧 Android 没有 java.nio.file。
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) error("$FILE_NAME rename failed")
        }
    }

    companion object {
        private const val FILE_NAME = "online-cache.json"
    }
}
