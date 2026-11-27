package com.reelix.app.data.alist

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 片库扫描结果缓存。
 *
 * ## 为什么需要
 *
 * 片库每次打开都要走一遍：
 * ```
 * 登录 Alist -> 递归扫描所有目录 -> 构建作品 -> 渲染
 * ```
 * 180 部片 / 388 集实测要 8~15 秒。用户每次切到片库页都要等，
 * 而实际上文件根本不会变。
 *
 * ## 设计
 *
 * ```
 * 存什么：扫描结果（AlistVideo 列表），不存构建后的 LibraryEntry
 *        —— AlistVideo 只有 4 个字段，序列化简单且稳定；
 *           build() 是纯计算，180 部片约 50ms，重新跑无所谓
 *
 * 何时读：进入片库时先读缓存 -> 立刻显示（秒开）
 * 何时写：手动刷新扫描成功后
 * 何时失效：用户改 Alist 地址/账号，或手动点刷新
 * ```
 *
 * ## 为什么不存进 Room
 *
 * 这是**可再生数据**：删了只是要重新扫一次，不影响任何用户数据。
 * 分成独立 JSON 文件，坏了/删了不连累播放记录和设置。
 */
object LibraryCache {

    private const val TAG = "LibraryCache"
    private const val FILE_NAME = "library-cache.json"
    private const val VERSION = 3

    /** 缓存里的一条视频 */
    data class Entry(
        val path: String,
        val name: String,
        val sizeBytes: Long,
        val provider: String,
        val mountRoot: String = "",
        val sourceIdentity: String = "",
        val modifiedAt: Long = 0L,
        val firstSeenAt: Long = 0L,
        val changedAt: Long = 0L,
    ) {
        fun toVideo() = AlistVideo(
            path = path,
            name = name,
            sizeBytes = sizeBytes,
            provider = provider,
            mountRoot = mountRoot,
            sourceIdentity = sourceIdentity,
            modifiedAt = modifiedAt, firstSeenAt = firstSeenAt, changedAt = changedAt,
        )
    }

    /** 缓存内容 */
    data class Snapshot(
        val entries: List<Entry>,
        /** 扫描起始目录，换了目录缓存就该失效 */
        val root: String,
        /** Alist 地址，换了地址缓存就该失效 */
        val serverUrl: String,
        val savedAt: Long,
        val directories: List<String> = emptyList(),
        val failures: List<DirectoryFailure> = emptyList(),
        val archived: List<AlistVideo> = emptyList(),
    ) {
        val videos: List<AlistVideo> get() = entries.map { it.toVideo() }
        val scan: LibraryScan get() = LibraryScan(videos,
            (directories + DirectoryRules.directories(videos)).distinct().sorted(), failures, archived)
    }

    /**
     * One cache file per source. Without this, Alist / WebDAV / Emby scans would
     * overwrite each other and only the last source would survive a restart.
     */
    private fun file(context: android.content.Context, sourceKey: String = "alist"): File {
        val name = if (sourceKey.isBlank() || sourceKey == "alist") FILE_NAME
        else FILE_NAME.removeSuffix(".json") + "-" + safeSourceKey(sourceKey) + ".json"
        return File(context.applicationContext.filesDir, name)
    }

    private fun safeSourceKey(sourceKey: String): String =
        sourceKey.lowercase().replace(Regex("[^a-z0-9_-]"), "_")

    /**
     * 比较用的 URL 规范化。
     *
     * ★ 2026-11-07：原来直接 `optString("serverUrl") != serverUrl` 逐字符比较，
     *   末尾多一个 `/`、协议大小写不同（HTTP:// vs http://）都会判定"地址变了"，
     *   于是每次进片库都重新扫描。用户完全感知不到自己"改了配置"。
     */
    private fun normUrl(u: String): String = normalizeLibraryServer(u)

    /** 扫描目录同样忽略末尾斜杠；空值等价于根目录 */
    private fun normRoot(r: String): String {
        return normalizeLibraryRoot(r)
    }

    /**
     * 读缓存。
     *
     * @param root 当前配置的扫描目录
     * @param serverUrl 当前配置的 Alist 地址
     * @return 匹配的缓存；不存在或不匹配返回 null
     */
    fun load(
        context: android.content.Context,
        root: String,
        serverUrl: String,
        username: String = "",
        sourceKey: String = "alist",
    ): Snapshot? = runCatching {
        val f = file(context, sourceKey)
        if (!f.exists() && !File(f.path + ".bak").exists()) return null
        val text = android.util.AtomicFile(f).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        if (text.isBlank()) return null

        val rootObj = JSONObject(text)
        if (rootObj.has("username") && rootObj.optString("username") != username) return null

        if (rootObj.optInt("version", 0) !in 1..VERSION) {
            Log.i(TAG, "缓存版本不匹配，丢弃")
            return null
        }

        if (normRoot(rootObj.optString("root")) != normRoot(root)) {
            Log.i(TAG, "扫描目录变了（${rootObj.optString("root")} -> $root），缓存失效")
            return null
        }

        if (normUrl(rootObj.optString("serverUrl")) != normUrl(serverUrl)) {
            Log.i(
                TAG,
                "Alist 地址变了（${rootObj.optString("serverUrl")} -> $serverUrl），缓存失效",
            )
            return null
        }

        val arr = rootObj.optJSONArray("items") ?: return null
        val entries = ArrayList<Entry>(arr.length())

        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            entries.add(
                Entry(
                    path = o.optString("path"),
                    name = o.optString("name"),
                    sizeBytes = o.optLong("sizeBytes", 0L),
                    provider = o.optString("provider"),
                    mountRoot = o.optString("mountRoot"),
                    sourceIdentity = o.optString("sourceIdentity"),
                    modifiedAt = o.optLong("modifiedAt"), firstSeenAt = o.optLong("firstSeenAt"), changedAt = o.optLong("changedAt"),
                )
            )
        }

        if (entries.isEmpty() && rootObj.optInt("version") < 2) return null

        Log.i(TAG, "读到缓存：${entries.size} 个视频")
        Snapshot(
            entries = entries,
            root = rootObj.optString("root"),
            serverUrl = rootObj.optString("serverUrl"),
            savedAt = rootObj.optLong("savedAt", 0L),
            directories = rootObj.optJSONArray("directories")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
            failures = rootObj.optJSONArray("failures")?.let { a -> (0 until a.length()).map {
                a.getJSONObject(it).let { o -> DirectoryFailure(o.getString("path"), o.optString("message")) }
            } }.orEmpty(),
            archived = rootObj.optJSONArray("archived")?.let { a -> (0 until a.length()).map {
                a.getJSONObject(it).let { o -> AlistVideo(o.getString("path"), o.getString("name"), o.optLong("sizeBytes"),
                    o.optString("provider"), o.optString("mountRoot"), o.optString("sourceIdentity"),
                    o.optLong("modifiedAt"), o.optLong("firstSeenAt"), o.optLong("changedAt")) }
            } }.orEmpty(),
        )
    }.onFailure {
        Log.w(TAG, "读缓存失败: ${it.message}")
    }.getOrNull()

    /**
     * 写缓存。
     *
     * 写入用「先写临时文件再改名」——
     * 直接覆写的话，如果写到一半进程被杀，会留下半个 JSON，
     * 下次启动读出来是坏的。
     */
    fun save(
        context: android.content.Context,
        videos: List<AlistVideo>,
        root: String,
        serverUrl: String,
        username: String = "",
        scan: LibraryScan = LibraryScan(videos),
        sourceKey: String = "alist",
    ) {
        runCatching {
            val arr = JSONArray()
            videos.forEach { v ->
                arr.put(
                    JSONObject()
                        .put("path", v.path)
                        .put("name", v.name)
                        .put("sizeBytes", v.sizeBytes)
                        .put("provider", v.provider)
                        .put("mountRoot", v.mountRoot)
                        .put("sourceIdentity", v.sourceIdentity)
                        .put("modifiedAt", v.modifiedAt).put("firstSeenAt", v.firstSeenAt).put("changedAt", v.changedAt)
                )
            }

            val obj = JSONObject()
                .put("version", VERSION)
                .put("root", root)
                .put("serverUrl", serverUrl)
                .put("username", username)
                .put("savedAt", System.currentTimeMillis())
                .put("items", arr)
                .put("directories", JSONArray(scan.directories))
                .put("failures", JSONArray().apply { scan.failures.forEach {
                    put(JSONObject().put("path", it.path).put("message", it.message))
                } })
                .put("archived", JSONArray().apply { scan.archivedVideos.forEach {
                    put(JSONObject().put("path", it.path).put("name", it.name).put("sizeBytes", it.sizeBytes)
                        .put("provider", it.provider).put("mountRoot", it.mountRoot).put("sourceIdentity", it.sourceIdentity)
                        .put("modifiedAt", it.modifiedAt).put("firstSeenAt", it.firstSeenAt).put("changedAt", it.changedAt))
                } })

            val f = file(context, sourceKey)
            // AtomicFile retains the previous snapshot if the process dies during replacement.
            val atomic = android.util.AtomicFile(f)
            var stream: java.io.FileOutputStream? = null
            try {
                stream = atomic.startWrite()
                stream.write(obj.toString().toByteArray(Charsets.UTF_8))
                atomic.finishWrite(stream)
            } catch (e: Exception) {
                atomic.failWrite(stream)
                throw e
            }

            // 写完立刻回读校验，确保下次启动真的能命中
            val ok = runCatching {
                f.exists() && f.length() > 0L &&
                    JSONObject(f.readText()).optInt("version", 0) == VERSION
            }.getOrDefault(false)
            Log.i(
                TAG,
                if (ok) "已写缓存：${videos.size} 个视频（${f.length()}B）"
                else "写缓存后校验失败：${f.absolutePath}（存在=${f.exists()}）",
            )
            check(ok) { "Library snapshot verification failed" }
        }.onFailure {
            Log.w(TAG, "写缓存失败: ${it.message}")
        }.getOrThrow()
    }

    /** 清缓存（用户改配置时调用） */
    fun clear(context: android.content.Context, sourceKey: String = "alist") {
        runCatching { file(context, sourceKey).delete() }
    }

    /** 缓存时间，给 UI 显示「上次刷新」 */
    fun savedAt(context: android.content.Context, sourceKey: String = "alist"): Long = runCatching {
        val f = file(context, sourceKey)
        if (!f.exists()) return 0L
        JSONObject(f.readText()).optLong("savedAt", 0L)
    }.getOrDefault(0L)
}
