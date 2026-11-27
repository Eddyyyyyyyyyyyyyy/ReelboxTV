package com.reelix.app.data.source.online

import com.reelix.app.data.model.Episode
import com.reelix.app.data.model.MediaDetail
import com.reelix.app.data.model.MediaItem
import com.reelix.app.data.model.MediaKind
import com.reelix.app.data.model.PlayRoute
import com.reelix.app.data.source.CategoryContent
import com.reelix.app.data.source.FilterGroup
import com.reelix.app.data.source.FilterOption
import com.reelix.app.data.source.HomeContent
import com.reelix.app.data.source.SourceCategory
import kotlinx.serialization.Serializable

/**
 * 在线快照的磁盘模型。
 *
 * 领域模型保持与 UI 一致，但磁盘格式单独冻结在这里：
 * 以后改 `MediaItem` 字段不会让旧缓存无法读取或悄悄错位。
 */
@Serializable
data class CachedMediaItem(
    val id: String = "",
    val title: String = "",
    val originalTitle: String? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val year: Int? = null,
    val rating: Double? = null,
    val kind: String = "",
    val overview: String? = null,
    val durationMin: Int? = null,
    val area: String? = null,
    val language: String? = null,
    val cast: List<String> = emptyList(),
    val director: String? = null,
    val sourceKey: String = "",
    val sourceId: String = "",
    val fromLibrary: Boolean = false,
    val posterRes: String? = null,
    val backdropRes: String? = null,
    val progress: Float = 0f,
    val lastEpisode: Int? = null,
    val addedAt: Long = 0L,
) {
    fun toMediaItem(): MediaItem = com.reelix.app.data.image.OnlineMetadataStore.apply(
        com.reelix.app.data.image.OnlineArtworkOverrides.apply(MediaItem(
        id = id,
        title = title,
        originalTitle = originalTitle,
        poster = poster,
        backdrop = backdrop,
        year = year,
        rating = rating,
        kind = MediaKind.entries.firstOrNull { it.name == kind } ?: MediaKind.UNKNOWN,
        overview = overview,
        durationMin = durationMin,
        area = area,
        language = language,
        cast = cast,
        director = director,
        sourceKey = sourceKey,
        sourceId = sourceId,
        fromLibrary = fromLibrary,
        posterRes = posterRes,
        backdropRes = backdropRes,
        progress = progress,
        lastEpisode = lastEpisode,
        addedAt = addedAt,
    )))

    companion object {
        fun from(item: MediaItem): CachedMediaItem = CachedMediaItem(
            id = item.id,
            title = item.title,
            originalTitle = item.originalTitle,
            poster = item.poster,
            backdrop = item.backdrop,
            year = item.year,
            rating = item.rating,
            kind = item.kind.name,
            overview = item.overview,
            durationMin = item.durationMin,
            area = item.area,
            language = item.language,
            cast = item.cast,
            director = item.director,
            sourceKey = item.sourceKey,
            sourceId = item.sourceId,
            fromLibrary = item.fromLibrary,
            posterRes = item.posterRes,
            backdropRes = item.backdropRes,
            progress = item.progress,
            lastEpisode = item.lastEpisode,
            addedAt = item.addedAt,
        )
    }
}

@Serializable
data class CachedEpisode(
    val index: Int = 0,
    val name: String = "",
    val flag: String = "",
    val playId: String = "",
    val url: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val durationSec: Long? = null,
    val originalName: String? = null,
) {
    fun toEpisode(): Episode = Episode(
        index = index,
        name = name,
        flag = flag,
        playId = playId,
        url = url,
        headers = headers,
        durationSec = durationSec,
        originalName = originalName,
    )

    companion object {
        fun from(episode: Episode): CachedEpisode = CachedEpisode(
            index = episode.index,
            name = episode.name,
            flag = episode.flag,
            playId = episode.playId,
            url = episode.url,
            headers = episode.headers,
            durationSec = episode.durationSec,
            originalName = episode.originalName,
        )
    }
}

@Serializable
data class CachedPlayRoute(
    val name: String = "",
    val episodes: List<CachedEpisode> = emptyList(),
    val directPlay: Boolean = true,
) {
    fun toRoute(): PlayRoute = PlayRoute(
        name = name,
        episodes = episodes.map { it.toEpisode() },
        directPlay = directPlay,
    )

    companion object {
        fun from(route: PlayRoute): CachedPlayRoute = CachedPlayRoute(
            name = route.name,
            episodes = route.episodes.map { CachedEpisode.from(it) },
            directPlay = route.directPlay,
        )
    }
}

/** 缓存里只保留可读数据；线路必须至少有一条选集才算真的可用。 */
@Serializable
data class CachedMediaDetail(
    val item: CachedMediaItem,
    val routes: List<CachedPlayRoute> = emptyList(),
    val related: List<CachedMediaItem> = emptyList(),
) {
    fun toDetail(): MediaDetail = MediaDetail(
        item = item.toMediaItem(),
        routes = routes.map { it.toRoute() },
        related = related.map { it.toMediaItem() },
    )

    val hasPlayableRoute: Boolean
        get() = routes.any { route -> route.episodes.any { it.playId.isNotBlank() || !it.url.isNullOrBlank() } }

    companion object {
        fun from(detail: MediaDetail): CachedMediaDetail = CachedMediaDetail(
            item = CachedMediaItem.from(detail.item),
            routes = detail.routes.map { CachedPlayRoute.from(it) },
            related = detail.related.map { CachedMediaItem.from(it) },
        )
    }
}

@Serializable
data class CachedFilterOption(val value: String = "", val label: String = "") {
    fun toOption(): FilterOption = FilterOption(value = value, label = label)
    companion object {
        fun from(option: FilterOption): CachedFilterOption =
            CachedFilterOption(value = option.value, label = option.label)
    }
}

@Serializable
data class CachedFilterGroup(
    val key: String = "",
    val name: String = "",
    val options: List<CachedFilterOption> = emptyList(),
) {
    fun toGroup(): FilterGroup = FilterGroup(
        key = key,
        name = name,
        options = options.map { it.toOption() },
    )

    companion object {
        fun from(group: FilterGroup): CachedFilterGroup = CachedFilterGroup(
            key = group.key,
            name = group.name,
            options = group.options.map { CachedFilterOption.from(it) },
        )
    }
}

@Serializable
data class CachedCategory(
    val id: String = "",
    val name: String = "",
    val filters: List<CachedFilterGroup> = emptyList(),
) {
    fun toCategory(): SourceCategory = SourceCategory(
        id = id,
        name = name,
        filters = filters.map { it.toGroup() },
    )

    companion object {
        fun from(category: SourceCategory): CachedCategory = CachedCategory(
            id = category.id,
            name = category.name,
            filters = category.filters.map { CachedFilterGroup.from(it) },
        )
    }
}

@Serializable
data class CachedHomeContent(
    val categories: List<CachedCategory> = emptyList(),
    val recommendations: List<CachedMediaItem> = emptyList(),
    val heroes: List<CachedMediaItem> = emptyList(),
) {
    fun toHome(): HomeContent = HomeContent(
        categories = categories.map { it.toCategory() },
        recommendations = recommendations.map { it.toMediaItem() },
        heroes = heroes.map { it.toMediaItem() },
    )

    companion object {
        fun from(home: HomeContent): CachedHomeContent = CachedHomeContent(
            categories = home.categories.map { CachedCategory.from(it) },
            recommendations = home.recommendations.map { CachedMediaItem.from(it) },
            heroes = home.heroes.map { CachedMediaItem.from(it) },
        )
    }
}

@Serializable
data class CachedCategoryContent(
    val items: List<CachedMediaItem> = emptyList(),
    val page: Int = 1,
    val pageCount: Int = 1,
    val total: Int = 0,
) {
    fun toContent(): CategoryContent = CategoryContent(
        items = items.map { it.toMediaItem() },
        page = page,
        pageCount = pageCount,
        total = total,
    )

    companion object {
        fun from(content: CategoryContent): CachedCategoryContent = CachedCategoryContent(
            items = content.items.map { CachedMediaItem.from(it) },
            page = content.page,
            pageCount = content.pageCount,
            total = content.total,
        )
    }
}

@Serializable
data class CachedHomeSnapshot(
    val siteKey: String = "",
    val home: CachedHomeContent = CachedHomeContent(),
    val savedAt: Long = 0L,
)

@Serializable
data class CachedCategorySnapshot(
    val siteKey: String = "",
    val categoryId: String = "",
    val content: CachedCategoryContent = CachedCategoryContent(),
    val savedAt: Long = 0L,
)

@Serializable
data class CachedDetailSnapshot(
    val siteKey: String = "",
    val vodId: String = "",
    val detail: CachedMediaDetail,
    val savedAt: Long = 0L,
)

/** 订阅配置本身的离线副本：不能依赖当前 URL 做文件名，否则地址一变旧缓存就找不到了。 */
@Serializable
data class CachedSubscription(
    val source: String = "",
    val configUrl: String = "",
    val json: String = "",
    val savedAt: Long = 0L,
)

@Serializable
data class CachedCatalog(
    val version: Int = 1,
    val sites: List<OnlineSiteSnapshot> = emptyList(),
    val selectedSiteKey: String = "",
    val selectedCategoryId: String = "",
    val home: CachedHomeSnapshot? = null,
    val categories: List<CachedCategorySnapshot> = emptyList(),
    val details: List<CachedDetailSnapshot> = emptyList(),
    val subscription: CachedSubscription? = null,
)

/** Serialized site descriptor; deliberately excludes credentials and content. */
@Serializable
data class OnlineSiteSnapshot(
    val key: String = "",
    val name: String = "",
    val type: Int = 0,
    val api: String = "",
    val ext: String? = null,
    val searchable: Int = 1,
    val fromJar: Boolean = false,
)
