package com.reelix.app.data.source

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.LinkedHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * TVBox 配置的几种私加密格式 —— 移植自 TVBox 分支的 `AES` / `Decoder`。
 *
 * ## 这个文件解决问题的形态
 *
 * 有些站长不希望配置被随意搬运，会把 JSON 加密后伪装成普通文件。
 * 目前生态里见到两种：
 *
 * ```
 * ① ;pk;KEY 后缀 —— 地址写成 http://x.com/cfg?x=1;pk;mykey
 *    响应体是 hex 字符串，AES-ECB(密钥=key 右补 "0" 到 16 位)
 *
 * ② "2423" 前缀 —— 响应体直接以 2423 开头
 *    格式：2423{hex密文}...$#{16位key}#$...{末尾13位iv}
 *    AES-CBC，key/iv 都藏在明文片段里
 * ```
 *
 * 这两种如果不解，表现就是「这个地址返回的不是 TVBox 配置」——
 * 用户看不出其实是能用的，只是被加密了。
 *
 * ## 为什么不用 javax.crypto 之外的东西
 *
 * `AES/ECB/PKCS7Padding`、`AES/CBC/PKCS7Padding` 在 Android 和 JVM
 * 上都可用，不需要 BouncyCastle；pkcs7 就是 pkcs5 的超集，Android 认得。
 */
object TvBoxCrypto {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 判断一段文本是不是合法 JSON 对象（用来提前短路，避免无谓解密） */
    fun isJsonObject(text: String): Boolean {
        val t = text.trim()
        if (t.length < 2 || !t.startsWith("{") || !t.endsWith("}")) return false
        return runCatching { json.parseToJsonElement(t) is JsonObject }.getOrDefault(false)
    }

    /**
     * `;pk;KEY` 那种：AES-ECB 解密。
     *
     * @param hexData 响应体原文（hex 字符串）
     * @param key     用户填在 `;pk;` 后面的密钥
     * @return 解密后的明文；失败返回 null
     */
    fun ecbDecrypt(hexData: String, key: String): String? {
        val k = key.trim()
        if (k.isEmpty()) return null

        val bytes = hexToBytes(hexData.trim()) ?: return null
        if (bytes.isEmpty() || bytes.size % 16 != 0) return null

        return runCatching {
            val spec = SecretKeySpec(padKey(k).toByteArray(Charsets.UTF_8), "AES")

            // ★ 用 NoPadding 再手动去填充，而不是 PKCS7Padding ——
            //   后者在标准 JVM 上不存在（报 NoSuchAlgorithmException），
            //   只有 Android 的 Conscrypt 提供。手动去填充两边都能跑。
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, spec)
            val plain = cipher.doFinal(bytes)

            // 去掉 PKCS7 填充：最后一字节是填充长度
            val padLen = plain.lastOrNull()?.toInt()?.and(0xFF) ?: 0
            val end = if (padLen in 1..16 && padLen <= plain.size) plain.size - padLen else plain.size
            String(plain, 0, end, Charsets.UTF_8)
        }.getOrNull()
    }

    /**
     * `2423` 那种：从明文片段里抠出 key/iv，再做 AES-CBC 解密。
     *
     * 原始格式（TVBox 分支 `AES.CBC` 的等价实现）：
     * ```
     * 2423 <hex密文> ... $# <key> #$ ... <末尾13位=iv片段>
     *       ↑ 从 "2324" 之后开始、到长度-26 结束
     * ```
     * 注意：`2423` 是十六进制表示，转成文本后 `2324` 才是它的字符形态
     * （0x2324 = "#$"），所以这里先 hex→文本，再按 `$#` / `#$` 切分。
     *
     * @param raw 响应体原文
     */
    fun cbcDecrypt2423(raw: String): String? {
        val hex = raw.filterNot { it.isWhitespace() }
        if (!hex.startsWith("2423")) return null

        return runCatching {
            // ① 整体 hex → 文本，转小写：里面有 $#key#$ 和末尾 iv 片段
            val hexBytes = hexToBytes(hex) ?: return@runCatching null
            val text = String(hexBytes, Charsets.UTF_8).lowercase()

            val keyStart = text.indexOf("$#")
            val keyEnd = text.indexOf("#$")
            if (keyStart < 0 || keyEnd <= keyStart) return@runCatching null

            // ② key/iv 都用 "0" 右补齐到 16 位
            val key = padKey(text.substring(keyStart + 2, keyEnd))
            val iv = padKey(text.takeLast(13))

            // ③ 密文在原文 hex 里：从 "2324" 之后到末尾 26 个字符之前
            val cipherStart = hex.indexOf("2324")
            if (cipherStart < 0 || hex.length - 26 <= cipherStart + 4) return@runCatching null
            val cipherHex = hex.substring(cipherStart + 4, hex.length - 26)
            val cipherBytes = hexToBytes(cipherHex) ?: return@runCatching null
            if (cipherBytes.size % 16 != 0) return@runCatching null

            val cipher = Cipher.getInstance("AES/CBC/PKCS7Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"),
                IvParameterSpec(iv.toByteArray(Charsets.UTF_8)),
            )
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        }.getOrNull()
    }

    // ---------- JSON 结构分支 ----------

    /**
     * 解析结果的种类，用于决定下一步动作。
     */
    sealed interface Shape {
        /** 真正的配置（有 sites 数组） */
        data class Config(val json: String) : Shape

        /** 仓库入口；相对地址由调用方按当前响应 URL 补全。 */
        data class Depot(val entries: List<DepotEntry>) : Shape {
            val url: String get() = entries.firstOrNull()?.url.orEmpty()
            val name: String get() = entries.firstOrNull()?.name.orEmpty()
        }

        data class DepotEntry(val url: String, val name: String)

        /** 站点返回的人话错误提示（有 msg 字段） */
        data class Message(val text: String) : Shape

        /** 不是可识别的结构 */
        data object Unknown : Shape
    }

    /**
     * 判断一段已经确认是 JSON 的文本属于哪种形态。
     *
     * ## 为什么单独拆出来
     *
     * TVBox 生态里三种 JSON 长得都像「配置」，但处理方式完全不同：
     * ```
     * {"sites":[...]}                  → 配置，直接用
     * {"urls":[{"url":"...","name":""}]} → 仓库，要递归下载里面的地址
     * {"msg":"本站已停止维护"}          → 报错，要把这句话原样告诉用户
     * ```
     * 之前只认第一种，后两种都会被判成「不是配置」，
     * 用户拿到的是无意义的技术错误。
     */
    fun classify(text: String): Shape {
        val root = runCatching {
            json.parseToJsonElement(text.trim())
        }.getOrNull() as? JsonObject ?: return Shape.Unknown

        // ① 报错体：站长写的人话提示，原样透传
        root["msg"]?.let { m ->
            if (m is JsonPrimitive && m.isString) {
                val s = m.contentOrNull?.trim().orEmpty()
                if (s.isNotEmpty()) return Shape.Message(s)
            }
        }

        // ② 仓库入口可能分布在多个数组里。只提取结构，不在这里发请求。
        val entries = LinkedHashMap<String, Shape.DepotEntry>()
        for (field in listOf("urls", "storeHouse", "depots")) {
            val rows = root[field] as? JsonArray ?: continue
            for (row in rows.take(256 - entries.size)) {
                val item = row as? JsonObject ?: continue
                val url = sequenceOf("url", "sourceUrl", "api")
                    .mapNotNull { key -> (item[key] as? JsonPrimitive)?.contentOrNull?.trim() }
                    .firstOrNull { it.isNotEmpty() }
                    ?: continue
                val name = sequenceOf("name", "sourceName")
                    .mapNotNull { key -> (item[key] as? JsonPrimitive)?.contentOrNull?.trim() }
                    .firstOrNull { it.isNotEmpty() }
                    .orEmpty()
                if (isDepotAddress(url) && !entries.containsKey(url)) {
                    entries[url] = Shape.DepotEntry(url, name)
                }
            }
            if (entries.size >= 256) break
        }
        if (entries.isNotEmpty()) return Shape.Depot(entries.values.toList())

        // ③ 配置：必须有 sites 数组
        if (root["sites"] is JsonArray) return Shape.Config(text)

        return Shape.Unknown
    }

    private fun isDepotAddress(value: String): Boolean {
        if (value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true)
        ) return true
        if (value.contains(':')) return false
        return value.startsWith("./") || value.startsWith("../") || value.startsWith("/") ||
            value.matches(Regex("[^\\s]+\\.[a-zA-Z0-9]{1,8}(?:[?#].*)?"))
    }

    /**
     * 把配置里的相对路径就地补全成绝对地址。
     *
     * 这是 [ConfigPaths] 的入口包装：解析 → 归一化 → 序列化回去。
     * 解析失败时原样返回（宁可让下游报错，也不要吞掉配置）。
     */
    fun normalizePaths(text: String, baseUrl: String): String {
        if (baseUrl.isBlank()) return text
        val root: JsonElement = runCatching {
            json.parseToJsonElement(text)
        }.getOrNull() ?: return text

        // ★ 必须用返回值：kotlinx 的 JsonElement 不可变，
        //   原来写 `ConfigPaths.normalize(root, baseUrl)` 把新树丢掉了，
        //   导致路径补全从来没生效过。
        val fixed = ConfigPaths.normalize(root, baseUrl)
        return runCatching { fixed.toString() }.getOrDefault(text)
    }

    // ---------- 工具 ----------

    /** key/iv 右补 "0" 到 16 位；超长就截断（和 TVBox 行为一致） */
    private fun padKey(key: String): String {
        val k = key.trim()
        return when {
            k.length == 16 -> k
            k.length > 16 -> k.substring(0, 16)
            else -> k + "0".repeat(16 - k.length)
        }
    }

    /** hex 字符串 → 字节数组；遇到非 hex 或奇数长度返回 null */
    private fun hexToBytes(hex: String): ByteArray? {
        val h = hex.filterNot { it.isWhitespace() }
        if (h.isEmpty() || h.length % 2 != 0) return null
        if (!h.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return null

        return ByteArray(h.length / 2) { i ->
            ((Character.digit(h[i * 2], 16) shl 4) or Character.digit(h[i * 2 + 1], 16)).toByte()
        }
    }
}
