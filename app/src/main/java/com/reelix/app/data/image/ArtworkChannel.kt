package com.reelix.app.data.image

import com.reelix.app.data.streamx.StreamxClient
import com.reelix.app.data.tvmaze.TvmazeClient
import com.reelix.app.data.douban.DoubanClient
import kotlinx.serialization.Serializable

/**
 * 一个内置刮削渠道的配置。
 *
 * 每个渠道都有自己的适配器，不做通用 JSON 映射 —— 新增渠道意味着新增代码。
 * 这样配置界面只需暴露该渠道真正需要的字段，不会出现"这个源该填什么"的问题。
 */
@Serializable
data class ArtworkChannel(
    /** 渠道类型；枚举名，见 [ArtworkChannelKind] */
    val kind: String = ArtworkChannelKind.STREAMX.name,
    val enabled: Boolean = false,
    /** 实例地址；空表示用内置默认地址 */
    val baseUrl: String = "",
) {
    /** 稳定标识，同时用作设置里的排序键 */
    val id: String get() = kind.lowercase()

    val displayName: String get() = when (kind) {
        ArtworkChannelKind.STREAMX.name -> "Streamx"
        ArtworkChannelKind.TVMAZE.name -> "TVmaze"
        ArtworkChannelKind.DOUBAN.name -> "豆瓣"
        else -> kind
    }

    val defaultUrl: String get() = when (kind) {
        ArtworkChannelKind.STREAMX.name -> StreamxClient.DEFAULT_BASE_URL
        ArtworkChannelKind.TVMAZE.name -> TvmazeClient.DEFAULT_BASE_URL
        ArtworkChannelKind.DOUBAN.name -> DoubanClient.DEFAULT_BASE_URL
        else -> ""
    }

    val effectiveUrl: String get() = baseUrl.trim().ifBlank { defaultUrl }

    companion object {
        fun streamx(enabled: Boolean = false, baseUrl: String = "") =
            ArtworkChannel(ArtworkChannelKind.STREAMX.name, enabled, baseUrl)

        fun tvmaze(enabled: Boolean = false, baseUrl: String = "") =
            ArtworkChannel(ArtworkChannelKind.TVMAZE.name, enabled, baseUrl)

        fun douban(enabled: Boolean = false, baseUrl: String = "") =
            ArtworkChannel(ArtworkChannelKind.DOUBAN.name, enabled, baseUrl)

        /**
         * 内置渠道默认值。
         *
         * 有设置入口时全部关闭，由用户逐个开启；
         * 无设置入口时默认开启三个公开渠道（豆瓣 / Streamx / TVmaze）。
         */
        fun defaults(): List<ArtworkChannel> =
            if (com.reelix.app.BuildConfig.PUBLIC_BUILD) {
                listOf(douban(true), streamx(true), tvmaze(true))
            } else {
                listOf(douban(), streamx(), tvmaze())
            }
    }
}

enum class ArtworkChannelKind {
    /** streamx-api：单次搜索请求，返回竖图 + 横图，可选详情补简介 */
    STREAMX,

    /** TVmaze：搜索后还要查 /shows/{id}/images 才有横图 */
    TVMAZE,

    /** 豆瓣：HTML 搜索 → Rexxar subject / photos / credits；有严格限流，只用于详情与手动换图。 */
    DOUBAN,
}
