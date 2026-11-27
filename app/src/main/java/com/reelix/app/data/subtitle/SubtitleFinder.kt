package com.reelix.app.data.subtitle

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 外挂字幕发现与加载。
 *
 * ## 为什么只做外挂，不碰内嵌
 *
 * 内嵌字幕（MKV 里的 ASS 轨道）之前踩过大坑：
 * ```
 * SsaParser.parse: IllegalStateException
 * ```
 * 115 网盘的片源几乎必崩，整个播放会中断。所以
 * `PlayerFactory` 里把内嵌字幕解析器设成了 `UNSUPPORTED`。
 *
 * 但**完全没字幕**也不行 —— 很多人看外语片需要字幕。
 *
 * 折中方案：**只做外挂字幕**。
 * ```
 * 视频：/115/电影/沙丘2/沙丘2.mkv
 * 字幕：/115/电影/沙丘2/沙丘2.chs.srt      ← 自动发现
 *       /115/电影/沙丘2/沙丘2.ass
 *       /115/电影/沙丘2/Subs/Chinese.srt
 * ```
 * 同目录下的字幕文件用 alist 列目录就能拿到，不需要解析视频容器，
 * 完全避开了 ASS 崩溃的问题。
 *
 * ## 命名约定（业界通用）
 *
 * ```
 * 片名.srt              最基础
 * 片名.chs.srt          简体
 * 片名.cht.srt          繁体
 * 片名.zh-CN.srt        语言标签
 * 片名.eng.srt          英文
 * 片名.forced.srt       强制字幕（只显示外语对白）
 * ```
 * 优先级：中文 > 通用 > 英文。
 */
object SubtitleFinder {

    private const val TAG = "SubtitleFinder"

    /** 认得的字幕扩展名 */
    private val SUB_EXTS = listOf("srt", "ass", "ssa", "sub", "vtt")

    /** 一个字幕文件 */
    data class Subtitle(
        val name: String,
        val url: String,
        /** 语言标签（chs/cht/eng…） */
        val lang: String,
        /** 优先级分数，越大越优先 */
        val score: Int,
    )

    /**
     * 从同目录文件列表里找出字幕。
     *
     * @param videoName 视频文件名（不含路径），如 `沙丘2.mkv`
     * @param siblings 同目录下的所有文件名
     * @param urlBuilder 把文件名转成可访问 URL 的函数
     */
    fun find(
        videoName: String,
        siblings: List<String>,
        urlBuilder: (String) -> String,
    ): List<Subtitle> {
        if (videoName.isBlank()) return emptyList()

        val base = videoName.substringBeforeLast('.', videoName)
        val lowerBase = base.lowercase()

        val result = mutableListOf<Subtitle>()

        for (file in siblings) {
            val ext = file.substringAfterLast('.', "").lowercase()
            if (ext !in SUB_EXTS) continue

            val fileBase = file.substringBeforeLast('.', file)
            val lowerFile = fileBase.lowercase()

            // 只收"和视频同名"或"在字幕目录里"的文件
            val related = lowerFile.startsWith(lowerBase) ||
                lowerFile.contains(lowerBase) ||
                // Subs/ 目录下的任意字幕都算（常见组织方式）
                file.contains("/", ignoreCase = false) && file.substringBeforeLast('/').contains("sub", true)

            if (!related) continue

            val lang = detectLang(lowerFile)
            result.add(
                Subtitle(
                    name = file,
                    url = urlBuilder(file),
                    lang = lang,
                    score = langScore(lang),
                )
            )
        }

        Log.d(TAG, "为 $videoName 找到 ${result.size} 个字幕")
        return result.sortedByDescending { it.score }
    }

    /**
     * 识别字幕语言。
     *
     * 只做最常见的几种，识别不出来就归为"通用"。
     */
    private fun detectLang(name: String): String = when {
        name.contains("chs") || name.contains("zh-cn") || name.contains("zh_cn") ||
            name.contains("简体") || name.contains("简中") -> "chs"

        name.contains("cht") || name.contains("zh-tw") || name.contains("zh_tw") ||
            name.contains("繁体") || name.contains("繁中") -> "cht"

        name.contains("eng") || name.contains("english") || name.contains("英文") -> "eng"

        name.contains("jpn") || name.contains("japanese") || name.contains("日文") -> "jpn"

        name.contains("kor") || name.contains("korean") || name.contains("韩文") -> "kor"

        name.contains("forced") || name.contains("强制") -> "forced"

        else -> "und"
    }

    /**
     * 语言优先级。
     *
     * 中文用户为主，所以中文 > 通用 > 其他语种。
     * forced（强制字幕）排最前 —— 它只显示外语对白部分，
     * 通常是用户最想要的（不会遮挡画面）。
     */
    private fun langScore(lang: String): Int = when (lang) {
        "forced" -> 100
        "chs" -> 90
        "cht" -> 85
        "und" -> 60
        "eng" -> 40
        "jpn" -> 30
        "kor" -> 30
        else -> 10
    }

    /**
     * 下载字幕内容。
     *
     * 为什么自己下载而不是交给 ExoPlayer：
     * 需要转成 ExoPlayer 能吃的格式（它要的是 `MediaItem.Subtitle`
     * 或 `SingleSampleMediaSource`）。先拿到字节，让上层决定怎么喂。
     */
    suspend fun download(url: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "字幕下载失败 HTTP ${resp.code}: $url")
                    return@use null
                }
                resp.body?.bytes()
            }
        }.onFailure {
            Log.e(TAG, "字幕下载异常: $url", it)
        }.getOrNull()
    }

    /**
     * 把字幕字节转成 UTF-8 字符串。
     *
     * ## 为什么需要
     *
     * 中文字幕文件编码极其混乱：
     * ```
     * UTF-8    现代工具产出
     * GBK      大量老片源（必转换，否则全是乱码）
     * UTF-8 BOM Excel/记事本另存
     * ```
     * 不处理的话用户看到的就是乱码。
     */
    fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""

        // UTF-8 BOM
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }

        // 先按 UTF-8 试，失败说明是 GBK
        val utf8 = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()
        if (utf8 != null && !utf8.contains('\uFFFD')) return utf8

        // 退回 GBK
        return runCatching {
            String(bytes, charset("GBK"))
        }.getOrElse {
            String(bytes, Charsets.ISO_8859_1)
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
}
