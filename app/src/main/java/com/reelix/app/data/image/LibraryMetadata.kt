package com.reelix.app.data.image

import com.reelix.app.data.model.MediaItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.jsoup.Jsoup

@Serializable
data class LibraryWorkIdentity(val provider: String, val id: String, val mediaType: String,
    val title: String, val originalTitle: String? = null, val year: Int? = null,
    val kind: String = "UNKNOWN", val siteKey: String = "")

@Serializable data class LibraryPersonCredit(val name: String, val role: String? = null, val avatar: String? = null)
@Serializable data class LibraryWorkMetadata(val identity: LibraryWorkIdentity, val overview: String? = null,
    val directors: List<String> = emptyList(), val cast: List<LibraryPersonCredit> = emptyList(),
    val poster: String? = null, val backdrop: String? = null, val retryAfter: Long = 0L, val policy: String = "",
    /** 评分 0..10；只有海报来源渠道自己的评分，不做跨渠道补搜。 */
    val rating: Double? = null,
    /** 评分来源：TMDB / DOUBAN / STREAMX / TVMAZE */
    val ratingSource: String? = null) {
    val hasContent: Boolean get() = !overview.isNullOrBlank() || directors.isNotEmpty() || cast.isNotEmpty() ||
        rating != null
    val complete: Boolean get() = !overview.isNullOrBlank() && directors.isNotEmpty() && cast.isNotEmpty()
}

fun sameLibraryWork(expected: LibraryWorkIdentity, actual: LibraryWorkIdentity): Boolean {
    if (expected.provider == actual.provider && expected.siteKey == actual.siteKey &&
        expected.mediaType == actual.mediaType) return expected.id == actual.id
    val expectedTitles = listOfNotNull(expected.title, expected.originalTitle)
        .flatMap { artworkTitleVariants(it, expected.year) }
    val titles = listOfNotNull(actual.title, actual.originalTitle)
        .flatMap { artworkTitleVariants(it, actual.year) }
    if (expectedTitles.none { it in titles }) return false
    if (expected.year != null && actual.year != null && expected.year != actual.year) return false
    if (expected.kind != "UNKNOWN" && actual.kind != "UNKNOWN" &&
        (expected.kind == "MOVIE") != (actual.kind == "MOVIE")) return false
    return (expected.year != null && actual.year == expected.year) ||
        (expected.kind != "UNKNOWN" && actual.kind != "UNKNOWN")
}

fun uniqueLibraryArtworkMatch(item: MediaItem, candidates: List<LibraryArtworkCandidate>): LibraryArtworkCandidate? =
    candidates.filter { libraryArtworkCandidates(item, listOf(it)).isNotEmpty() && !it.identity?.id.isNullOrBlank() }
        .distinctBy { it.identity }.singleOrNull()

fun libraryIdentityMatchesItem(item: MediaItem, identity: LibraryWorkIdentity): Boolean =
    artworkTitleVariants(item.title, item.year).any { title ->
        listOfNotNull(identity.title, identity.originalTitle)
            .any { artworkTitleVariants(it, identity.year).contains(title) }
    } &&
        (item.year == null || identity.year == null || item.year == identity.year) &&
        (item.kind.name == "UNKNOWN" || identity.kind == "UNKNOWN" || (item.kind.name == "MOVIE") == (identity.kind == "MOVIE"))

fun libraryMetadataApplies(item: MediaItem, metadata: LibraryWorkMetadata, binding: LibraryWorkIdentity?): Boolean =
    if (binding != null) sameLibraryWork(binding, metadata.identity) else libraryIdentityMatchesItem(item, metadata.identity)

fun mergeLibraryMetadata(current: LibraryWorkMetadata, extra: LibraryWorkMetadata): LibraryWorkMetadata {
    if (!sameLibraryWork(current.identity, extra.identity)) return current
    return current.copy(overview = current.overview?.takeIf { it.isNotBlank() } ?: extra.overview,
        directors = current.directors.ifEmpty { extra.directors },
        // 头像只补空缺，不把已有头像冲掉。
        cast = current.cast.ifEmpty { extra.cast },
        rating = current.rating ?: extra.rating,
        ratingSource = current.ratingSource ?: extra.ratingSource)
}

/**
 * 换资料来源时的合并：[this] 是**新来源**的结果，[fallback] 是旧来源的缓存。
 *
 * 和 [mergeLibraryMetadata] 方向相反：
 * - 新来源有值 → 用新值（用户换了海报来源，简介/演职员要跟着换）；
 * - 新来源为空 → 退回旧值（用户确认：不能因为新来源缺字段就让界面变空）。
 */
fun LibraryWorkMetadata.withFallbackFrom(fallback: LibraryWorkMetadata): LibraryWorkMetadata =
    copy(
        overview = overview?.takeIf { it.isNotBlank() } ?: fallback.overview,
        directors = directors.ifEmpty { fallback.directors },
        cast = cast.ifEmpty { fallback.cast },
        rating = rating ?: fallback.rating,
        ratingSource = ratingSource ?: fallback.ratingSource,
        poster = poster?.takeIf { it.isNotBlank() } ?: fallback.poster,
        backdrop = backdrop?.takeIf { it.isNotBlank() } ?: fallback.backdrop,
    )

fun applyLibraryMetadata(item: MediaItem, metadata: LibraryWorkMetadata): MediaItem = item.copy(
    overview = metadata.overview?.takeIf { it.isNotBlank() } ?: item.overview,
    director = metadata.directors.takeIf { it.isNotEmpty() }?.joinToString(" / ") ?: item.director,
    cast = metadata.cast.takeIf { it.isNotEmpty() }?.map { it.name + (it.role?.takeIf(String::isNotBlank)?.let { role -> "（$role）" } ?: "") } ?: item.cast,
    castCredits = metadata.cast.takeIf { it.isNotEmpty() } ?: item.castCredits,
    rating = metadata.rating ?: item.rating,
    ratingSource = metadata.ratingSource ?: item.ratingSource,
    poster = metadata.poster ?: item.poster, backdrop = metadata.backdrop ?: item.backdrop,
)

private val metadataJson = Json { ignoreUnknownKeys = true; isLenient = true }
private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() && it != "null" }
private fun JsonObject.objects(key: String): List<JsonObject> = (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

fun parseTmdbLibraryMetadata(body: String, expected: LibraryWorkIdentity): LibraryWorkMetadata? {
    val obj = metadataJson.parseToJsonElement(body) as? JsonObject ?: return null
    if (obj.text("id") != expected.id) return null
    val tv = expected.mediaType == "tv"
    val title = obj.text(if (tv) "name" else "title") ?: return null
    val identity = expected.copy(title = title, originalTitle = obj.text(if (tv) "original_name" else "original_title"),
        year = obj.text(if (tv) "first_air_date" else "release_date")?.take(4)?.toIntOrNull(), kind = if (tv) "SERIES" else "MOVIE")
    val credits = obj[if (tv) "aggregate_credits" else "credits"] as? JsonObject
    val directors = credits?.objects("crew").orEmpty().filter {
        it.text("job") == "Director" || it.objects("jobs").any { job -> job.text("job") == "Director" }
    }.mapNotNull { it.text("name") }.distinct()
    val cast = credits?.objects("cast").orEmpty().take(20).mapNotNull { actor ->
        val name = actor.text("name") ?: return@mapNotNull null
        val role = actor.text("character") ?: actor.objects("roles").mapNotNull { it.text("character") }.distinct().joinToString(" / ").takeIf { it.isNotBlank() }
        LibraryPersonCredit(name, role, actor.text("profile_path")?.let { "https://image.tmdb.org/t/p/w185$it" })
    }
    val rating = obj["vote_average"]?.jsonPrimitive?.doubleOrNull?.takeIf { it > 0 }
    return LibraryWorkMetadata(identity, obj.text("overview"), directors, cast,
        rating = rating, ratingSource = if (rating != null) "TMDB" else null)
}
