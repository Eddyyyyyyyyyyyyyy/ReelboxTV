package com.reelix.app.data.alist

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Alist 客户端 —— 私有片库的数据入口。
 *
 * 设计要点（都是从实际踩坑总结的）：
 *
 * 1. **请求体必须 UTF-8 原始字节**
 *    中文路径（"我不是大师"、"与晋长安"）如果经过字符串转义层，
 *    alist 会返回 500。这里统一 `toRequestBody` + UTF-8 编码。
 *
 * 2. **服务地址对电视来说是局域网**
 *    `192.168.1.x` 完全绕过 DNS，是这台机器上唯一稳定可用的数据源。
 *
 * 3. **raw_url 是 115/夸克等网盘的直链**
 *    有效期很短（带 t= 时间戳），所以**不能缓存**，每次播放前现取。
 *
 * 4. **只扫视频文件**
 *    目录里混杂 nfo/jpg/srt，必须过滤，否则媒体库会脏。
 */
class AlistClient(
    private val baseUrl: String,
    providedOkHttp: OkHttpClient? = null,
) : LibraryDataSource {

    /**
     * 用户经常只填 `192.168.1.111:5244`，不带协议。
     * OkHttp 的 `Request.url()` 会直接拒绝这种地址，表现成“片库连不上”。
     * 这里统一补 `http://`；已经是 http/https 的不动。
     */
    private val rootUrl: String = run {
        val trimmed = baseUrl.trim().trimEnd('/')
        when {
            trimmed.isBlank() -> trimmed
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            else -> "http://$trimmed"
        }
    }

    /**
     * ★ 延迟创建：AlistClient 在首帧组合里就会 new 出来，
     *   而 OkHttpClient 构造（连接池/Dispatcher）会拖慢启动。
     *   真正发请求时才付这笔钱。
     */
    private val okHttp: OkHttpClient by lazy { providedOkHttp ?: defaultClient() }

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    @Volatile
    private var token: String = ""
    private var accountIdentity: String = ""

    override val isLoggedIn: Boolean get() = token.isNotBlank()

    /** 登录并保存 token */
    override suspend fun login(username: String, password: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val body = buildString {
                append("""{"username":"""").append(escape(username)).append("""",""")
                append(""""password":"""").append(escape(password)).append(""""}""")
            }
            val resp = post("/api/auth/login", body)
            val parsed = AlistJson.decodeFromString(AlistResp.serializer(AlistLoginData.serializer()), resp)
            if (!parsed.ok) error("登录失败: ${parsed.message}")
            token = parsed.data?.token ?: error("登录未返回 token")
            accountIdentity = "$rootUrl|$username"
            Unit
        }
    }

    /** 列出目录内容 */
    suspend fun list(path: String, forceRefresh: Boolean = false): List<AlistFileItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<AlistFileItem>()
        val namesSeen = mutableSetOf<String>()
        var page = 1
        while (true) {
        coroutineContext.ensureActive()
        val body = buildString {
            append("""{"path":"""").append(escape(path)).append("""",""")
            append(""""password":"","page":$page,"per_page":1000,"refresh":${forceRefresh && page == 1}}""")
        }
        val resp = post("/api/fs/list", body)
        val parsed = AlistJson.decodeFromString(AlistResp.serializer(AlistListData.serializer()), resp)
        if (!parsed.ok) error("列目录失败 [$path]: ${parsed.message}")
        val batch = parsed.data?.content.orEmpty()
        if (batch.isEmpty()) {
            check(items.size >= (parsed.data?.total ?: 0)) { "Incomplete directory: $path" }
            break
        }
        check(batch.all { namesSeen.add(it.name) }) { "Repeated directory page: $path" }
        items.addAll(batch)
        val total = parsed.data?.total ?: 0
        if ((total > 0 && items.size >= total) || (total <= 0 && batch.size < 1000)) break
        page++
        }
        items
    }

    /**
     * 取文件详情，重点是 [AlistFileData.rawUrl] —— 真正的播放地址。
     * 注意每次调用都会返回**新的**带签名的 URL，不要缓存结果。
     */
    override suspend fun getFile(path: String): AlistFileData? = withContext(Dispatchers.IO) {
        val body = buildString {
            append("""{"path":"""").append(escape(path)).append("""","password":""}""")
        }
        val resp = post("/api/fs/get", body)
        val parsed = AlistJson.decodeFromString(AlistResp.serializer(AlistFileData.serializer()), resp)
        if (!parsed.ok) error("获取文件失败: ${parsed.message}")
        parsed.data
    }

    /**
     * Alist 自己的下载代理。115 的 raw_url 是外部 CDN 直链，部分网络/账号下会 403；
     * `/d/<path>` 由 Alist 转发并支持 Range，播放器可以直接用。
     */
    fun proxyUrl(path: String): String? {
        val base = rootUrl.toHttpUrlOrNull() ?: return null
        val clean = path.trim().trimStart('/')
        if (clean.isBlank()) return null
        return base.newBuilder()
            .addPathSegment("d")
            .addPathSegments(clean)
            .build()
            .toString()
    }

    override suspend fun remove(path: String, isDirectory: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val parent = path.substringBeforeLast('/', "").ifBlank { "/" }
                val name = path.substringAfterLast('/')
                require(name.isNotBlank()) { "路径无效" }
                // Build with explicit quotes: a hand-concatenated array previously lost the
                // opening quote and Alist rejected the body as malformed JSON.
                val body = "{\"dir\":\"${escape(parent)}\",\"names\":[\"${escape(name)}\"]}"
                val resp = post("/api/fs/remove", body)
                val parsed = AlistJson.decodeFromString(
                    AlistResp.serializer(kotlinx.serialization.json.JsonElement.serializer()), resp)
                if (!parsed.ok) {
                    val message = parsed.message.orEmpty()
                    error(when {
                        message.contains("permission", true) || message.contains("denied", true) ->
                            "该源不允许删除，请检查账号权限"
                        message.contains("not found", true) || message.contains("不存在") ->
                            "文件已不存在，可能已被其他设备删除"
                        message.isBlank() -> "删除失败"
                        else -> message
                    })
                }
                Unit
            }
        }

    /**
     * 递归扫描整个目录树，收集所有视频文件。
     *
     * @param root 起始目录
     * @param maxDepth 最大深度，防止网盘目录异常深导致卡死
     * @param onProgress 进度回调（已扫描目录数，当前路径）
     */
    override suspend fun scanVideos(
        root: String,
        maxDepth: Int,
        forceRefresh: Boolean,
        onProgress: (dirs: Int, current: String) -> Unit,
    ): List<AlistVideo> = scanSnapshot(root, maxDepth, forceRefresh, onProgress).videos

    override suspend fun scanSnapshot(root: String, maxDepth: Int, forceRefresh: Boolean,
        onProgress: (Int, String) -> Unit): LibraryScan = withContext(Dispatchers.IO) {
        LibraryScanner { path, refresh -> list(path, refresh) }
            .scan(root, maxDepth, forceRefresh, accountIdentity, onProgress)
    }

    // ---------- 内部 ----------

    private fun post(pathSeg: String, body: String): String {
        val builder = Request.Builder()
            .url(rootUrl + pathSeg)
            .post(body.toByteArray(Charsets.UTF_8).toRequestBody(jsonMedia))
        if (token.isNotBlank()) {
            builder.header("Authorization", token)
        }
        okHttp.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                // alist 的错误体也是 JSON，尽量把 message 带出来
                error("HTTP ${resp.code}: ${text.take(200)}")
            }
            return text
        }
    }

    /** 最小 JSON 字符串转义，只处理路径里可能出现的特殊字符 */
    private fun escape(s: String): String = buildString(s.length + 8) {
        for (c in s) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

/** 扫描出来的视频文件 */
data class AlistVideo(
    val path: String,
    val name: String,
    val sizeBytes: Long,
    val provider: String = "",
    val mountRoot: String = "",
    val sourceIdentity: String = "",
    val modifiedAt: Long = 0L,
    val firstSeenAt: Long = 0L,
    val changedAt: Long = 0L,
    /** Configured library source this file came from: alist / webdav / emby. */
    val sourceKey: String = "",
) {
    val sizeText: String
        get() {
            val mb = sizeBytes / 1024.0 / 1024.0
            return if (mb > 1024) String.format("%.1fGB", mb / 1024) else String.format("%.0fMB", mb)
        }
}
