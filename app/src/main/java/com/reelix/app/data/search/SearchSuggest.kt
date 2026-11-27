package com.reelix.app.data.search

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 爱奇艺搜索联想。
 *
 * 腾讯 `s.video.qq.com/smartbox` 已失效，这里改用爱奇艺公开联想接口。
 * 失败只返回空列表，不阻塞搜索；搜索本身仍走各个 TVBox 源。
 */
object SearchSuggest {

    private const val TAG = "SearchSuggest"
    private const val TTL_MS = 5 * 60 * 1000L
    private const val MAX_RESULTS = 10

    private val client = OkHttpClient.Builder()
        .callTimeout(2, TimeUnit.SECONDS)
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val cache = ConcurrentHashMap<String, Cached>()

    private data class Cached(val at: Long, val words: List<String>)

    suspend fun suggest(prefix: String): List<String> = withContext(Dispatchers.IO) {
        val q = prefix.trim()
        if (q.isEmpty() || q.length > 12) return@withContext emptyList()

        val now = System.currentTimeMillis()
        cache[q]?.takeIf { now - it.at in 0..TTL_MS }?.let { return@withContext it.words }

        val words = runCatching {
            val url = "https://suggest.video.iqiyi.com/?if=mobile&key=" +
                URLEncoder.encode(q, "UTF-8")
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching emptyList()
                parseSuggestions(response.body?.string().orEmpty())
            }
        }.getOrElse {
            Log.d(TAG, "联想获取失败（忽略）: ${it.message}")
            emptyList()
        }

        // 空结果不缓存：接口临时超时后，下一次输入仍有机会重新请求。
        if (words.isNotEmpty() && cache.size < 200) cache[q] = Cached(now, words)
        words
    }

    internal fun parseSuggestions(body: String): List<String> {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return emptyList()
        val data = root["data"]?.jsonArray ?: return emptyList()
        return data.asSequence()
            .mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_RESULTS)
            .toList()
    }

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
}
