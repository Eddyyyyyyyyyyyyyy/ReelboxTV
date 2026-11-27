package com.reelix.app.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import java.io.File

/**
 * 播放器工厂 —— 全应用唯一的 ExoPlayer 构建入口。
 *
 * 这里集中处理影视播放的四个实际痛点：
 *
 *  1. **请求头透传**：TVBox 源的播放地址几乎都要求 Referer/UA，
 *     用 ResolvingDataSource 在请求发起前注入，而不是靠调用方记着传。
 *
 *  2. **缓存**：电视盒子上反复拖进度条很常见，片头看几遍更是常态。
 *     给 m3u8/ts/mp4 做磁盘缓存，省带宽也省卡顿。
 *
 *  3. **缓冲参数**：TV 端网络抖动大，默认缓冲太小会频繁卡。
 *     2026-11-21 试运行值：min 30s / max 120s，起播 2.5s，卡顿后 5s。
 *
 *  4. **编码兼容**：TS 流里 AC3/EAC3 很常见，DefaultTsPayloadReaderFactory
 *     默认不识别部分流，这里放开。
 */
@UnstableApi
object PlayerFactory {

    private const val CACHE_DIR = "video_cache"
    private const val CACHE_SIZE_BYTES = 1024L * 1024 * 1024 // 1GB LRU cache

    @Volatile
    private var cache: SimpleCache? = null

    /**
     * 全局播放缓存。多实例共享同一个 SimpleCache 是必须的，
     * 否则会因文件锁冲突导致崩溃。
     */
    private fun cache(context: Context): SimpleCache {
        return cache ?: synchronized(this) {
            cache ?: SimpleCache(
                File(context.cacheDir, CACHE_DIR),
                LeastRecentlyUsedCacheEvictor(CACHE_SIZE_BYTES),
                StandaloneDatabaseProvider(context)
            ).also { cache = it }
        }
    }

    /**
     * 构建一个配置好的 ExoPlayer。
     * 调用方负责 release。
     */
    fun create(context: Context, decoderMode: DecoderMode = DecoderMode.AUTO): ExoPlayer {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 60_000,
                /* maxBufferMs = */ 120_000,
                /* bufferForPlaybackMs = */ 2_500,
                /* bufferForPlaybackAfterRebufferMs = */ 5_000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val selector = MediaCodecSelector { mime, secure, tunneling ->
            val available = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
            // Video preference must not accidentally remove an otherwise valid audio decoder.
            if (!mime.startsWith("video/")) available
            else available.filter { decoderMode.accepts(it.hardwareAccelerated, it.softwareOnly) }
        }
        val renderers = DefaultRenderersFactory(context)
            .setMediaCodecSelector(selector)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        return ExoPlayer.Builder(context, renderers)
            .setLoadControl(loadControl)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            // 影视内容基本都是立体声/环绕声，指定音频属性让系统走媒体音量
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true
            )
            // 播放中保持屏幕常亮
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
    }

    /**
     * 构建 MediaSource，按 URL 后缀选类型。
     *
     * 为什么不用 DefaultMediaSourceFactory：
     *  我们要在数据源层注入请求头 + 缓存，必须自己拼 DataSource.Factory 链。
     */
    fun mediaSource(
        context: Context,
        url: String,
        headers: Map<String, String> = emptyMap(),
        mimeHint: String? = null,
    ): MediaSource {
        val dataSourceFactory = dataSourceFactory(context, headers)
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
            )
            .setConstantBitrateSeekingEnabled(true)

        val type = resolveType(url, mimeHint)
        return when (type) {
            ContentType.HLS -> HlsMediaSource.Factory(dataSourceFactory)
                .setAllowChunklessPreparation(true)
                .createMediaSource(
                    androidx.media3.common.MediaItem.fromUri(url)
                )

            ContentType.DASH -> DashMediaSource.Factory(dataSourceFactory)
                .createMediaSource(
                    androidx.media3.common.MediaItem.fromUri(url)
                )

            ContentType.PROGRESSIVE -> ProgressiveMediaSource.Factory(dataSourceFactory, extractors)
                // Subtitle parsers must stay enabled: without them ExoPlayer never exposes
                // text tracks, so the player cannot list or disable soft subtitles.
                // Unsupported formats are rejected by DefaultSubtitleParserFactory itself.
                .setSubtitleParserFactory(androidx.media3.extractor.text.DefaultSubtitleParserFactory())
                .createMediaSource(
                    androidx.media3.common.MediaItem.fromUri(url)
                )
        }
    }

    /**
     * 数据源链：HTTP(注入头) → 磁盘缓存。
     *
     * 顺序很关键：
     *  缓存必须包在 HTTP 外层，否则缓存不到（但 headers 仍会透传）。
     */
    private fun dataSourceFactory(
        context: Context,
        headers: Map<String, String>,
    ): DataSource.Factory {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(DEFAULT_UA)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)
            .setKeepPostFor302Redirects(false)
        // 只给需要的请求注入头，避免污染所有请求
        val withHeaders: DataSource.Factory =
            if (headers.isEmpty()) http
            else ResolvingDataSource.Factory(http) { spec ->
                val newHeaders = spec.httpRequestHeaders.toMutableMap()
                headers.forEach { (k, v) -> newHeaders[k] = v }
                spec.buildUpon().setHttpRequestHeaders(newHeaders).build()
            }

        return CacheDataSource.Factory()
            .setCache(cache(context))
            .setCacheReadDataSourceFactory(androidx.media3.datasource.FileDataSource.Factory())
            .setUpstreamDataSourceFactory(androidx.media3.datasource.DefaultDataSource.Factory(context, withHeaders))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /** 内容类型判定 */
    enum class ContentType { HLS, DASH, PROGRESSIVE }

    /** Match the routing rule used by [mediaSource]; used to scope the local HLS proxy. */
    fun isHlsUrl(url: String): Boolean = resolveType(url, null) == ContentType.HLS

    private fun resolveType(url: String, mimeHint: String?): ContentType {
        mimeHint?.let {
            when {
                it.contains("mpegurl", true) -> return ContentType.HLS
                it.contains("dash", true) -> return ContentType.DASH
            }
        }
        val lower = url.substringBefore('?').lowercase()
        return when {
            lower.endsWith(".m3u8") || lower.contains(".m3u8") -> ContentType.HLS
            lower.endsWith(".mpd") -> ContentType.DASH
            // 没后缀的猜 HLS：大量 TVBox 源返回的是 xxx/index.m3u8?token=...
            !lower.substringAfterLast('/').contains('.') -> ContentType.HLS
            else -> ContentType.PROGRESSIVE
        }
    }

    /** 电视端通用 UA，很多源靠 UA 做白名单 */
    const val DEFAULT_UA =
        "Mozilla/5.0 (Linux; Android 14; SmartTV) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    /** 释放缓存（设置页清缓存用） */
    fun releaseCache() {
        synchronized(this) {
            cache?.release()
            cache = null
        }
    }
}
