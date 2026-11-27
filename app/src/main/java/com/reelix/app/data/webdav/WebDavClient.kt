package com.reelix.app.data.webdav

import com.reelix.app.data.alist.AlistFileData
import com.reelix.app.data.alist.AlistFileItem
import com.reelix.app.data.alist.AlistVideo
import com.reelix.app.data.alist.LibraryDataSource
import com.reelix.app.data.alist.LibraryScan
import com.reelix.app.data.alist.LibraryScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * Minimal WebDAV library source.
 *
 * The existing library pipeline only needs directory listings and a direct file URL.
 * WebDAV gives both without a proprietary API, so it can reuse LibraryScanner and
 * AlistLibraryBuilder unchanged.
 */
class WebDavClient(
    baseUrl: String,
    private val username: String = "",
    private val password: String = "",
    providedOkHttp: OkHttpClient? = null,
) : LibraryDataSource {
    override val sourceKey: String get() = "webdav"

    private val rootUrl: HttpUrl? = run {
        val trimmed = baseUrl.trim().trimEnd('/')
        val withScheme = when {
            trimmed.isBlank() -> ""
            trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true) -> trimmed
            else -> "http://$trimmed"
        }
        withScheme.toHttpUrlOrNull()
    }

    private val okHttp: OkHttpClient by lazy {
        providedOkHttp ?: OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .build()
    }

    @Volatile
    private var loggedIn = false

    override val isLoggedIn: Boolean get() = loggedIn && rootUrl != null

    override suspend fun login(user: String, pass: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val root = rootUrl ?: error("WebDAV 地址为空或格式不正确")
            propfind(root, depth = 0)
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
        val account = "${rootUrl?.toString().orEmpty()}|$username"
        LibraryScanner { path, _ -> list(path) }.scan(root, maxDepth, forceRefresh, account, onProgress)
    }

    override suspend fun getFile(path: String): AlistFileData? = withContext(Dispatchers.IO) {
        val url = fileUrl(path) ?: return@withContext null
        AlistFileData(
            name = path.substringAfterLast('/'),
            size = 0,
            isDir = false,
            rawUrl = url.toString(),
            provider = "webdav",
        )
    }

    override suspend fun remove(path: String, isDirectory: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = pathUrl(path) ?: error("WebDAV 路径无效")
                val builder = Request.Builder().url(url).delete()
                if (username.isNotBlank() || password.isNotBlank()) {
                    builder.header("Authorization", Credentials.basic(username, password))
                }
                okHttp.newCall(builder.build()).execute().use { response ->
                    when {
                        response.isSuccessful || response.code == 204 -> Unit
                        response.code == 401 || response.code == 403 ->
                            error("该源不允许删除，请检查账号权限")
                        response.code == 404 -> error("文件已不存在，可能已被其他设备删除")
                        else -> error("WebDAV 删除失败：HTTP ${response.code}")
                    }
                }
            }
        }

    private suspend fun list(path: String): List<AlistFileItem> {
        val url = directoryUrl(path) ?: error("WebDAV 地址为空或格式不正确")
        val response = propfind(url, depth = 1)
        // Compare decoded paths; encodedPath would not match a Chinese directory href.
        return parseMultiStatus(response, "/" + url.pathSegments.joinToString("/"))
    }

    private fun propfind(url: HttpUrl, depth: Int): String {
        val body = """<?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:">
              <d:prop>
                <d:resourcetype/>
                <d:getcontentlength/>
                <d:getlastmodified/>
              </d:prop>
            </d:propfind>""".trimIndent()
        val builder = Request.Builder()
            .url(url)
            .method("PROPFIND", body.toByteArray(Charsets.UTF_8).toRequestBody(XML))
            .header("Depth", depth.toString())
        if (username.isNotBlank() || password.isNotBlank()) {
            builder.header("Authorization", Credentials.basic(username, password))
        }
        okHttp.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful && response.code != 207) {
                error("WebDAV HTTP ${response.code}: ${text.take(180)}")
            }
            return text
        }
    }

    private fun parseMultiStatus(xml: String, selfPath: String): List<AlistFileItem> {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setInput(StringReader(xml))
        val result = mutableListOf<AlistFileItem>()
        var event = parser.eventType
        var inResponse = false
        var href = ""
        var isDir = false
        var size = 0L
        var modified = ""
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                when (parser.name.substringAfter(':').lowercase()) {
                    "response" -> {
                        inResponse = true; href = ""; isDir = false; size = 0L; modified = ""
                    }
                    "href" -> if (inResponse) href = parser.nextText()
                    "collection" -> if (inResponse) isDir = true
                    "getcontentlength" -> if (inResponse) size = parser.nextText().toLongOrNull() ?: 0L
                    "getlastmodified" -> if (inResponse) modified = parser.nextText()
                }
            } else if (event == org.xmlpull.v1.XmlPullParser.END_TAG &&
                parser.name.substringAfter(':').equals("response", true) && inResponse
            ) {
                val decoded = runCatching {
                    URLDecoder.decode(href.trimEnd('/'), "UTF-8")
                }.getOrDefault(href.trimEnd('/'))
                val name = decoded.substringAfterLast('/')
                if (name.isNotBlank() && decoded.trimEnd('/') != selfPath.trimEnd('/')) {
                    result += AlistFileItem(
                        name = name,
                        size = size,
                        isDir = isDir,
                        modified = modified,
                        provider = "webdav",
                    )
                }
                inResponse = false
            }
            event = parser.next()
        }
        return result
    }

    private fun directoryUrl(path: String): HttpUrl? = pathUrl(path)

    private fun fileUrl(path: String): HttpUrl? = pathUrl(path)

    private fun pathUrl(path: String): HttpUrl? {
        val base = rootUrl ?: return null
        val builder = base.newBuilder()
        path.trim('/').split('/').filter { it.isNotBlank() }.forEach { segment ->
            builder.addPathSegment(segment)
        }
        return builder.build()
    }

    private companion object {
        val XML = "application/xml; charset=utf-8".toMediaType()
    }
}
