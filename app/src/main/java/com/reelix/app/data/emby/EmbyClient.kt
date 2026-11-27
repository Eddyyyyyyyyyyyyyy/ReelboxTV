package com.reelix.app.data.emby

import com.reelix.app.data.alist.AlistFileData
import com.reelix.app.data.alist.AlistFileItem
import com.reelix.app.data.alist.AlistVideo
import com.reelix.app.data.alist.LibraryDataSource
import com.reelix.app.data.alist.LibraryScan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Emby / Jellyfin library source.
 *
 * Both servers expose the same core API. Items are mapped to synthetic paths so the
 * existing AlistLibraryBuilder can group seasons and episodes without a separate model.
 * The path -> item id map is kept for getFile(), which returns the server's direct
 * stream URL.
 */
class EmbyClient(
    baseUrl: String,
    private val apiKey: String = "",
    private val username: String = "",
    private val password: String = "",
    providedOkHttp: OkHttpClient? = null,
) : LibraryDataSource {
    override val sourceKey: String get() = "emby"

    private val rootUrl: HttpUrl? = run {
        val raw = baseUrl.trim().trimEnd('/')
        val withScheme = when {
            raw.isBlank() -> ""
            raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw
            else -> "http://$raw"
        }
        withScheme.toHttpUrlOrNull()
    }

    private val okHttp: OkHttpClient by lazy {
        providedOkHttp ?: OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    @Volatile private var token: String = apiKey.trim()
    @Volatile private var userId: String = ""
    @Volatile private var loggedIn: Boolean = false
    private val itemIds = mutableMapOf<String, String>()

    override val isLoggedIn: Boolean get() = loggedIn && rootUrl != null && userId.isNotBlank()

    override suspend fun login(user: String, pass: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val base = rootUrl ?: error("Emby/Jellyfin 地址为空或格式不正确")
            if (token.isBlank()) {
                check(username.isNotBlank() || user.isNotBlank()) { "请填写 API Key 或用户名密码" }
                val loginUser = username.ifBlank { user }
                val loginPass = password.ifBlank { pass }
                val body = JSONObject().apply {
                    put("Username", loginUser)
                    put("Pw", loginPass)
                }.toString()
                val response = request(
                    base.newBuilder().addPathSegments("Users/AuthenticateByName").build(),
                    method = "POST",
                    body = body,
                    auth = false,
                )
                val json = JSONObject(response)
                token = json.optString("AccessToken")
                userId = json.optJSONObject("User")?.optString("Id").orEmpty()
            } else {
                val me = JSONObject(request(base.newBuilder().addPathSegments("Users/Me").build()))
                userId = me.optString("Id")
            }
            check(token.isNotBlank()) { "Emby/Jellyfin 未返回访问令牌" }
            check(userId.isNotBlank()) { "Emby/Jellyfin 未返回用户 ID" }
            loggedIn = true
            Unit
        }
    }

    override suspend fun scanVideos(
        root: String,
        maxDepth: Int,
        forceRefresh: Boolean,
        onProgress: (Int, String) -> Unit,
    ): List<AlistVideo> = scanSnapshot(root, maxDepth, forceRefresh, onProgress).videos

    override suspend fun scanSnapshot(
        root: String,
        maxDepth: Int,
        forceRefresh: Boolean,
        onProgress: (Int, String) -> Unit,
    ): LibraryScan = withContext(Dispatchers.IO) {
        val base = rootUrl ?: error("Emby/Jellyfin 地址为空或格式不正确")
        if (!loggedIn) error("Emby/Jellyfin 未登录")
        val url = base.newBuilder()
            .addPathSegments("Users")
            .addPathSegment(userId)
            .addPathSegment("Items")
            .addQueryParameter("Recursive", "true")
            .addQueryParameter("IncludeItemTypes", "Movie,Episode")
            .addQueryParameter("Fields", "Path,MediaSources,SeriesId,SeriesName,ParentIndexNumber,IndexNumber,ProductionYear,Overview,RunTimeTicks")
            .addQueryParameter("Limit", "10000")
            .build()
        onProgress(1, "Emby/Jellyfin")
        val json = JSONObject(request(url))
        val items = json.optJSONArray("Items") ?: return@withContext LibraryScan(emptyList())
        val videos = mutableListOf<AlistVideo>()
        itemIds.clear()
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val id = item.optString("Id")
            val name = item.optString("Name").ifBlank { "未命名" }
            if (id.isBlank()) continue
            val type = item.optString("Type")
            val series = item.optString("SeriesName").ifBlank { name }
            val season = item.optInt("ParentIndexNumber", 0)
            val episode = item.optInt("IndexNumber", 0)
            val path = if (type.equals("Episode", true)) {
                "/Emby/$series/Season $season/$name.mkv"
            } else {
                "/Emby/$name/$name.mkv"
            }
            val size = item.optJSONArray("MediaSources")
                ?.optJSONObject(0)?.optLong("Size", 0L) ?: 0L
            val runtime = item.optLong("RunTimeTicks", 0L)
            itemIds[path] = id
            videos += AlistVideo(
                path = path,
                name = "$name.mkv",
                sizeBytes = size,
                provider = "emby",
                mountRoot = "/Emby",
                sourceIdentity = "${base.host}|$userId",
                modifiedAt = if (runtime > 0) System.currentTimeMillis() else 0L,
            )
        }
        val directories = videos.map { it.path.substringBeforeLast('/', "") }
            .filter { it.isNotBlank() }.distinct().sorted()
        LibraryScan(videos, directories)
    }

    override suspend fun getFile(path: String): AlistFileData? = withContext(Dispatchers.IO) {
        val base = rootUrl ?: return@withContext null
        val id = itemIds[path] ?: return@withContext null
        val stream = base.newBuilder()
            .addPathSegment("Videos")
            .addPathSegment(id)
            .addPathSegment("stream")
            .addQueryParameter("static", "true")
            .addQueryParameter("api_key", token)
            .build()
        AlistFileData(
            name = path.substringAfterLast('/'),
            size = 0,
            isDir = false,
            rawUrl = stream.toString(),
            provider = "emby",
        )
    }

    private fun request(
        url: HttpUrl,
        method: String = "GET",
        body: String? = null,
        auth: Boolean = true,
    ): String {
        val builder = Request.Builder().url(url)
        if (body != null) {
            builder.method(method, body.toByteArray(Charsets.UTF_8).toRequestBody(JSON))
        } else {
            builder.method(method, null)
        }
        if (auth && token.isNotBlank()) {
            builder.header("X-Emby-Token", token)
            builder.header("X-MediaBrowser-Token", token)
        }
        builder.header(
            "X-Emby-Authorization",
            "MediaBrowser Client=\"Reelbox\", Device=\"AndroidTV\", DeviceId=\"reelix\", Version=\"1.0\"",
        )
        okHttp.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Emby/Jellyfin HTTP ${response.code}: ${text.take(180)}")
            return text
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
