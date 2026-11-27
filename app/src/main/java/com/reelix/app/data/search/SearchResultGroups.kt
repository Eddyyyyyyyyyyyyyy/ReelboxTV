package com.reelix.app.data.search

import com.reelix.app.data.model.MediaItem

/** 搜索结果分类；顺序和空分类隐藏规则集中在这里，便于单测。 */
data class SearchResultGroup(
    val key: String,
    val label: String,
    val items: List<MediaItem>,
)

object SearchResultGroups {
    const val ALL = "__all__"
    const val LOCAL = "__local__"

    fun build(
        localItems: List<MediaItem>,
        siteGroups: List<SearchResultGroup>,
        homeSiteKey: String?,
    ): List<SearchResultGroup> = buildList {
        val home = siteGroups.firstOrNull { it.key == homeSiteKey }
        val others = siteGroups.filterNot { it.key == homeSiteKey }

        // 全部显示：首选源 → 本地 → 其他源。
        add(
            SearchResultGroup(
                ALL,
                "全部显示",
                home?.items.orEmpty() + localItems + others.flatMap { it.items },
            )
        )

        // 分类恢复为“每个站点一项”；本地紧跟在首选源站点下方。
        if (home != null) add(home)
        if (localItems.isNotEmpty()) add(SearchResultGroup(LOCAL, "本地", localItems))
        addAll(others)
    }

    /** 本地项没有 sourceKey，必须加 local: 前缀，避免和空 sourceKey 的在线项撞 key。 */
    fun itemKey(item: MediaItem): String =
        if (item.fromLibrary || item.sourceKey.isBlank()) {
            "local:${item.id}"
        } else {
            "${item.sourceKey}:${item.id}"
        }
}
