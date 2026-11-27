package com.reelix.app.data.search

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 360kan 热门榜。
 *
 * 输入为空时展示“热门搜索”；有缓存先显示缓存，再在后台静默刷新。
 * 缓存 6 小时，避免每次进搜索页都打第三方接口。
 */
object HotSearch {

    private const val TAG = "HotSearch"
    private const val PREFS = "reelix_hot_search"
    private const val KEY_WORDS = "words"
    private const val KEY_SAVED_AT = "saved_at"
    private const val TTL_MS = 6 * 60 * 60 * 1000L
    private const val MAX_WORDS = 20

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    fun cached(context: Context): List<String> = runCatching {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_WORDS, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        json.parseToJsonElement(raw).jsonArray
            .map { it.jsonPrimitive.content }
            .filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    fun isFresh(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedAt = prefs.getLong(KEY_SAVED_AT, 0L)
        if (savedAt <= 0L) return false
        return System.currentTimeMillis() - savedAt in 0..TTL_MS
    }

    /** 拉取并落盘；失败返回空列表，不覆盖已有缓存。 */
    suspend fun refresh(context: Context): List<String> = withContext(Dispatchers.IO) {
        val fresh = runCatching { fetch() }.getOrElse {
            Log.d(TAG, "热门搜索刷新失败（保留缓存）: ${it.message}")
            emptyList()
        }
        if (fresh.isNotEmpty()) {
            runCatching {
                context.applicationContext
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(
                        KEY_WORDS,
                        json.encodeToString(fresh),
                    )
                    .putLong(KEY_SAVED_AT, System.currentTimeMillis())
                    .apply()
            }.onFailure { Log.w(TAG, "热门搜索缓存写入失败: ${it.message}") }
        }
        fresh
    }

    internal fun parseWords(body: String): List<String> {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return emptyList()
        val data = root["data"]?.jsonArray ?: return emptyList()
        return data.asSequence()
            .mapNotNull { it.jsonObject["title"]?.jsonPrimitive?.content }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_WORDS)
            .toList()
    }

    private fun fetch(): List<String> {
        val request = Request.Builder()
            .url("https://api.web.360kan.com/v1/rank?cat=1")
            .header("User-Agent", UA)
            .header("Referer", "https://www.360kan.com/rank/general")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            parseWords(response.body?.string().orEmpty())
        }
    }

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
}
