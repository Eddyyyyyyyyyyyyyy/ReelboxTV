package com.reelix.app.data.alist

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Alist API 数据模型。
 *
 * 为什么这里要用心处理：
 *  Alist 的 `/api/fs/list` 和 `/api/fs/get` **必须用 UTF-8 原始字节提交 JSON**，
 *  中文路径如果先经过 `ConvertTo-Json` 之类的转义处理会直接返回 500。
 *  所以 [AlistClient] 里所有请求体都手工拼 JSON 字符串再转 UTF-8 字节。
 */
@Serializable
data class AlistResp<T>(
    val code: Int = 0,
    val message: String = "",
    val data: T? = null,
) {
    val ok: Boolean get() = code == 200
}

@Serializable
data class AlistLoginReq(val username: String, val password: String)

@Serializable
data class AlistLoginData(val token: String = "")

@Serializable
data class AlistFileItem(
    val name: String = "",
    val size: Long = 0,
    @SerialName("is_dir") val isDir: Boolean = false,
    val modified: String = "",
    val sign: String = "",
    val thumb: String = "",
    val type: Int = 0,
    @SerialName("raw_url") val rawUrl: String = "",
    val provider: String = "",
) {
    /** 是否是视频文件 */
    val isVideo: Boolean
        get() = !isDir && name.substringAfterLast('.', "").lowercase() in VIDEO_EXTS

    companion object {
        val VIDEO_EXTS = setOf(
            "mkv", "mp4", "avi", "ts", "m2ts", "mov", "wmv",
            "flv", "rmvb", "webm", "m4v", "mpg", "mpeg", "iso"
        )
    }
}

@Serializable
data class AlistListData(
    val content: List<AlistFileItem> = emptyList(),
    val total: Int = 0,
)

@Serializable
data class AlistFileData(
    val name: String = "",
    val size: Long = 0,
    @SerialName("is_dir") val isDir: Boolean = false,
    @SerialName("raw_url") val rawUrl: String = "",
    val provider: String = "",
) {
    val isVideo: Boolean
        get() = !isDir && name.substringAfterLast('.', "").lowercase() in AlistFileItem.VIDEO_EXTS
}

internal val AlistJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}
