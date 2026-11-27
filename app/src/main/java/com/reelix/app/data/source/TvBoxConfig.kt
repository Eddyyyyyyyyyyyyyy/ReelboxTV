package com.reelix.app.data.source

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * 把"任意 JSON 值"读成字符串。
 *
 * 为什么需要：TVBox 的 `ext` 字段在真实配置里形态五花八门 ——
 * 字符串（`"https://..."`）、对象（`{"token":"x"}`）、数组、数字、null。
 * 用 `String?` 直接反序列化，遇到对象就抛
 * "Expected a string literal, but had '{'" —— 一个站点坏掉，
 * 整个 config 解析失败，所有源一起消失。
 */
object RawJsonStringSerializer : KSerializer<String?> {
    override val descriptor =
        PrimitiveSerialDescriptor("RawJsonString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String? {
        val input = decoder as? kotlinx.serialization.json.JsonDecoder ?: return null
        val element: JsonElement = input.decodeJsonElement()
        return when (element) {
            is JsonNull -> null
            is JsonPrimitive -> element.content
            else -> element.toString()
        }
    }

    override fun serialize(encoder: Encoder, value: String?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value)
    }
}

/**
 * TVBox 源配置模型 —— 直接兼容现有 TVBox / 影视仓的 json 配置格式。
 * 这是整个缝合方案的关键：能吃掉生态里的源，就不用从零造内容。
 *
 * 兼容点：
 *  - sites[]：站点列表，api 可以是 csp_xxx / 直链 http / js 路径
 *  - lives[]：直播源
 *  - parses[]：解析器（嗅探/代理）
 *  - flags[]：线路白名单
 */
@Serializable
data class TvBoxConfig(
    val spider: String? = null,
    val wallpaper: String? = null,
    val logo: String? = null,
    val sites: List<TvBoxSite> = emptyList(),
    val lives: List<TvBoxLive> = emptyList(),
    val parses: List<TvBoxParse> = emptyList(),
    val flags: List<String> = emptyList(),
    val rule: String? = null,
    val ads: List<String> = emptyList(),
) {
    companion object {
        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            encodeDefaults = true
        }

        /**
         * 解析配置。
         *
         * 真实世界里 stream 出来的 config.json 有一半带 BOM（Windows 记事本改过就会），
         * 而 kotlinx.serialization **不吞 BOM**，会直接抛
         * "Expected start of the object '{', but had '\uFEFF' instead"。
         * 这里统一清洗：去 BOM、去控制字符。
         */
        fun parse(text: String): TvBoxConfig =
            json.decodeFromString(serializer(), sanitize(text))

        /** 保存原始字节的稳定指纹（用于磁盘缓存的 key）。 */
        fun fingerprint(raw: String): String {
            val digest = java.security.MessageDigest.getInstance("SHA-1")
            val bytes = digest.digest(sanitize(raw).toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }

        /** 去掉 BOM 和零宽字符，这些在野生配置里非常常见 */
        fun sanitize(raw: String): String =
            raw.trimStart('\uFEFF', '\u200B', '\u200C', '\u200D', '\uFEFF')
                .trim()
    }
}

@Serializable
data class TvBoxSite(
    val key: String = "",
    val name: String = "",
    val type: Int = 0,
    val api: String = "",
    val searchable: Int = 0,
    val quickSearch: Int = 0,
    val filter: Int = 0,
    @SerialName("playerType") val playerType: Int = 0,
    val categories: List<String> = emptyList(),
    /**
     * 站点扩展参数。
     * TVBox 里 ext 可能是字符串、对象、数组甚至 null，
     * 这里一律以原始 JSON 文本保存，交给具体 Spider 自己再解析。
     */
    @Serializable(with = RawJsonStringSerializer::class)
    val ext: String? = null,
    val jar: String? = null,
    val changeable: Int = 0,
    val time: String? = null,
    /**
     * 站点样式。
     * 真实配置里既有 `"style": 0` 也有 `"style": {"type":"rect","ratio":1.33}`，
     * 所以不能声明成 Int —— 类型一变整个配置就解析失败。
     */
    @Serializable(with = RawJsonStringSerializer::class)
    val style: String? = null,
) {
    /** 是否是点播类站点（0=csp 1=json 3=jar 4=jars 等） */
    val isVod: Boolean get() = type != 3

    /**
     * 能否在当前版本里直接播。
     *
     * 真相（在你仓库的 _dbg/tvbox_config.json 上实测）：
     *  69 个站点里 63 个是 type=3（远程 jar / csp_），只有 4 个 type=1、2 个 type=0。
     * 也就是说**绝大多数源需要 jar/dex 才能跑**，纯 JVM 侧只能直连 type=0/1。
     * UI 上必须能区分这两类，否则用户看到一堆点了放不出来的源。
     */
    val isDirectlyPlayable: Boolean get() = type == 0 || type == 1
}

@Serializable
data class TvBoxLive(
    val name: String = "",
    val url: String = "",
    val ua: String? = null,
    val epg: String? = null,
    val logo: String? = null,
    val groups: List<String> = emptyList(),
)

@Serializable
data class TvBoxParse(
    val name: String = "",
    val type: Int = 0,
    val url: String = "",
    /**
     * 解析器扩展参数。
     *
     * 和 [TvBoxSite.ext] 一样，真实配置里可能是字符串也可能是对象：
     * ```json
     * "ext": {"header": {"User-Agent": "..."}}
     * ```
     * 声明成 String 会让**整个配置解析失败** —— 实测这个错误会直接
     * 把 63 个站点全部拖垮（JsonDecodingException at $.parses[1].ext）。
     */
    @Serializable(with = RawJsonStringSerializer::class)
    val ext: String? = null,
    val ua: String? = null,
    val flag: List<String> = emptyList(),
)

/** 站点类型枚举，方便 when 分发到不同 loader */
object SourceType {
    const val CSP = 0          // 内置 spider 类名 csp_xxx
    const val JSON_API = 1     // 直接是 json 接口地址
    const val JSON_EXT = 2     // json + ext
    const val REMOTE_JAR = 3   // 远程 jar
    const val BUILTIN = 4      // 内置专用站点
    const val ORIGINAL = 10    // 原始站点（需要自定义规则）
}
