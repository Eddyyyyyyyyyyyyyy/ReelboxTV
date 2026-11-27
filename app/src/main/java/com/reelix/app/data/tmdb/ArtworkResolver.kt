package com.reelix.app.data.tmdb

import android.util.Log

/**
 * 图片刮削总入口 —— 三层降级。
 *
 * ## 为什么需要这一层
 *
 * 来源越优先，质量越好：
 * ```
 * ① 源自己给的 backdrop（vod_pic_slide）  横图，最佳，但极少有
 * ② TMDB backdrop_path                    横图，质量高，需要 key
 * ③ 源站/在线搜索给出的竖图               竖图，兜底
 * ```
 *
 * 各页面（Hero / 详情页 / 海报墙）都调这里，不各自拼逻辑 ——
 * 否则改一处漏一处，行为会不一致。
 *
 * ## 关于「竖图模糊」
 *
 * 三层都拿不到横图时，UI 层会把竖图 blur 放大当背景。
 * 那是 `BackdropQuality.COMPOSITE` 的降级策略，不在这一层做。
 */
object ArtworkResolver {

    private const val TAG = "ArtworkResolver"

    /** 部分公共服务要求可识别的 UA。 */
    private val userAgent: String
        get() = "Reelbox/${com.reelix.app.BuildConfig.VERSION_NAME} (Android TV)"

    /** 刮削结果 */
    data class Resolved(
        /** 横版大图（可能为空） */
        val backdrop: String? = null,
        /** 竖版海报（可能为空） */
        val poster: String? = null,
        /** 简介（TMDB 才提供） */
        val overview: String? = null,
        /** 原名 */
        val originalTitle: String? = null,
        /** 评分 */
        val rating: Double? = null,
        /** 来源，日志用 */
        val from: String = "",
    ) {
        val hasAnything: Boolean
            get() = backdrop != null || poster != null || overview != null
    }

    /**
     * 刮一张图。
     *
     * @param tmdbKey TMDB key，空则跳过 TMDB
     * @param tmdbEnabled 用户是否启用 TMDB
     * @param title 片名
     * @param year 年份
     * @param language TMDB 语言
     * @param needOverview 是否也想要简介（Hero 需要，海报墙不需要）
     */
    suspend fun resolve(
        tmdbKey: String,
        tmdbEnabled: Boolean,
        title: String,
        year: Int? = null,
        language: String = "zh-CN",
        needOverview: Boolean = false,
        channels: List<com.reelix.app.data.image.ArtworkChannel> = emptyList(),
        order: List<String> = DEFAULT_ORDER,
    ): Resolved {
        val enabledChannels = channels.filter { it.enabled }.associateBy { it.kind }
        val sequence = order.filter { it in DEFAULT_ORDER }.ifEmpty { DEFAULT_ORDER }

        for (key in sequence) {
            when {
                key == "TMDB" -> {
                    // TMDB 只有配了 key 才走。
                    if (!tmdbEnabled || tmdbKey.isBlank()) continue
                    val hit = TmdbClient.match(tmdbKey, title, year, language)
                    if (hit != null) {
                        val backdrop = hit.backdropUrlW1280
                        val poster = hit.posterUrlW500
                        if (backdrop != null || poster != null) {
                            Log.d(
                                TAG,
                                "TMDB 命中: $title -> backdrop=${backdrop != null} poster=${poster != null}"
                            )
                            return Resolved(
                                backdrop = backdrop,
                                poster = poster,
                                overview = hit.overview,
                                originalTitle = hit.originalTitle,
                                rating = hit.rating,
                                from = "tmdb",
                            )
                        }
                    }
                }

                key == com.reelix.app.data.image.ArtworkChannelKind.STREAMX.name ||
                    key == com.reelix.app.data.image.ArtworkChannelKind.TVMAZE.name -> {
                    val channel = enabledChannels[key] ?: continue
                    val found = runCatching {
                        when (channel.kind) {
                            com.reelix.app.data.image.ArtworkChannelKind.STREAMX.name ->
                                com.reelix.app.data.streamx.StreamxClient.search(
                                    baseUrl = channel.effectiveUrl,
                                    title = title,
                                    withOverview = needOverview && language.startsWith("en"),
                                    userAgent = userAgent,
                                )
                            com.reelix.app.data.image.ArtworkChannelKind.TVMAZE.name ->
                                com.reelix.app.data.tvmaze.TvmazeClient.search(
                                    baseUrl = channel.effectiveUrl,
                                    title = title,
                                    englishTitle = null,
                                    year = year,
                                    userAgent = userAgent,
                                )
                            else -> emptyList()
                        }
                    }.getOrDefault(emptyList())
                    val wanted = com.reelix.app.data.image.artworkTitle(title)
                    val hit = found.firstOrNull { candidate ->
                        // 必须同名（含原名/别名）；年份冲突时直接放弃，宁可没有横图也不贴错片。
                        candidate.backdrop != null &&
                            listOfNotNull(candidate.title, candidate.originalTitle)
                                .any { com.reelix.app.data.image.artworkTitle(it) == wanted } &&
                            (year == null || candidate.year == null || candidate.year == year)
                    }
                    if (hit != null) {
                        Log.d(TAG, "渠道 ${channel.displayName} 命中: $title -> backdrop=${hit.backdrop != null}")
                        return Resolved(
                            backdrop = hit.backdrop,
                            poster = null,
                            overview = hit.overview,
                            from = channel.id,
                        )
                    }
                }

                key == com.reelix.app.data.image.ArtworkChannelKind.DOUBAN.name -> {
                    val channel = enabledChannels[key] ?: continue
                    val found = runCatching {
                        com.reelix.app.data.douban.DoubanClient.searchCandidates(
                            baseUrl = channel.effectiveUrl,
                            title = title,
                            kind = com.reelix.app.data.model.MediaKind.UNKNOWN,
                        )
                    }.getOrDefault(emptyList())
                    val wanted = com.reelix.app.data.image.artworkTitle(title)
                    val hit = found.firstOrNull { candidate ->
                        candidate.backdrop != null &&
                            listOfNotNull(candidate.title, candidate.originalTitle)
                                .any { com.reelix.app.data.image.artworkTitle(it) == wanted } &&
                            (year == null || candidate.year == null || candidate.year == year)
                    }
                    if (hit != null) {
                        return Resolved(
                            backdrop = hit.backdrop,
                            poster = hit.poster,
                            overview = hit.overview,
                            originalTitle = hit.originalTitle,
                            rating = null,
                            from = "douban",
                        )
                    }
                }
            }
        }

        return Resolved(from = "none")
    }

    private val DEFAULT_ORDER = listOf("TMDB", "DOUBAN", "STREAMX", "TVMAZE")
}
