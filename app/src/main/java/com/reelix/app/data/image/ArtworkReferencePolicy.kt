package com.reelix.app.data.image

fun artworkCanBeEvicted(url: String?, keepUrls: Set<String>): Boolean = !url.isNullOrBlank() && url !in keepUrls

/**
 * 收集"仍被引用"的图片 URL，竖图和横图同等对待。
 *
 * 删除历史、批量清理、7 天滞图清理必须用同一份名单：只要漏掉横图，
 * 删一条历史就可能把别的页面还在用的背景图一起带走。
 */
fun collectArtworkReferences(entries: Iterable<Pair<String?, String?>>): Set<String> =
    entries.flatMap { (poster, backdrop) -> listOfNotNull(poster, backdrop) }
        .filter { it.isNotBlank() }
        .toSet()

/**
 * 片名 → 海报地址映射能否丢弃。
 *
 * 删除历史时，只有"该片名已经没有历史、片库或在线结果引用"才清映射；
 * 否则下次同一部片会重新刮一次图，而它其实还在别的页面用着。
 */
fun titleCacheCanBeForgotten(title: String?, keepTitles: Set<String>): Boolean =
    !title.isNullOrBlank() && PosterFetcher.titleKey(title) !in keepTitles

/**
 * 把一组片名转成 [PosterFetcher] 的缓存键。
 *
 * 缓存键是归一化后的片名（去掉季数/年份/画质），所以引用名单也必须用同一套
 * 归一化，否则「三体 第一季」在历史里、「三体 第二季」在片库里时会被当成
 * 两部不同的片，映射被误清。
 */
fun artworkTitleKeys(titles: Iterable<String>): Set<String> =
    titles.map { PosterFetcher.titleKey(it) }.filter { it.isNotBlank() }.toSet()

fun manualArtworkCanBeReclaimed(name: String, references: Set<String>, inFlight: Set<String>): Boolean =
    name.matches(Regex("(?:poster|backdrop)-[^/\\\\]+\\.jpg")) && name !in references && name !in inFlight
