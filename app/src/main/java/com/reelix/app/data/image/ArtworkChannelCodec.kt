package com.reelix.app.data.image

import org.json.JSONArray
import org.json.JSONObject

/**
 * 渠道配置的 JSON 编解码。
 *
 * 电视设置页、手机/电脑页面、本地存储共用同一份格式。
 * 只保留内置渠道真正需要的字段；未知 kind 直接丢弃。
 */
object ArtworkChannelCodec {

    data class DecodeResult(val channels: List<ArtworkChannel>, val droppedCount: Int)

    fun encode(channels: List<ArtworkChannel>): String = JSONArray().apply {
        channels.forEach { channel ->
            put(JSONObject().apply {
                put("kind", channel.kind)
                put("enabled", channel.enabled)
                put("baseUrl", channel.baseUrl)
            })
        }
    }.toString()

    /**
     * 解析失败返回默认表；未知类型跳过，避免旧配置把界面卡住。
     *
     * 0.3.42 的通用渠道格式没有适配器，这里按地址识别成内置类型；
     * 其余自定义渠道直接丢弃 —— 它们本来也拿不到可用的横图。
     */
    fun decode(raw: String?): List<ArtworkChannel> = decodeWithReport(raw).channels

    fun decodeWithReport(raw: String?): DecodeResult {
        if (raw.isNullOrBlank()) return DecodeResult(ArtworkChannel.defaults(), 0)
        var dropped = 0
        val decoded = runCatching {
            val array = JSONArray(raw)
            val known = buildList {
                for (index in 0 until array.length()) {
                    val obj = array.optJSONObject(index) ?: continue
                    val rawKind = obj.optString("kind").trim()
                    val url = obj.optString("baseUrl").trim().ifBlank { obj.optString("url").trim() }
                    val kind = when {
                        ArtworkChannelKind.entries.any { it.name == rawKind } -> rawKind
                        url.contains("streamx.qzz.io", ignoreCase = true) ->
                            ArtworkChannelKind.STREAMX.name
                        url.contains("tvmaze.com", ignoreCase = true) ->
                            ArtworkChannelKind.TVMAZE.name
                        else -> {
                            dropped++
                            continue
                        }
                    }
                    add(
                        ArtworkChannel(
                            kind = kind,
                            enabled = obj.optBoolean("enabled", false),
                            baseUrl = url,
                        ),
                    )
                }
            }.distinctBy { it.kind }
            // 旧配置里可能只有 Streamx，补齐 TVmaze 的默认项，保证设置页稳定。
            ArtworkChannelKind.entries.fold(known) { acc, type ->
                if (acc.any { it.kind == type.name }) acc
                else acc + ArtworkChannel(kind = type.name)
            }
        }.getOrDefault(ArtworkChannel.defaults())
        return DecodeResult(decoded, dropped)
    }
}
