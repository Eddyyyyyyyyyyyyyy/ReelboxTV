package com.reelix.app.data.alist

import com.reelix.app.data.library.FileNameParser
import com.reelix.app.data.model.Episode
import com.reelix.app.data.model.MediaItem
import com.reelix.app.data.model.MediaKind
import com.reelix.app.data.model.PlayRoute

/**
 * Alist 片库构建器 —— 把扁平的文件列表变成「媒体库」。
 *
 * 这是私有片库方案的**核心逻辑**，也是最容易做得不像样的地方。
 *
 * 输入：`scanVideos()` 出来的扁平的 `List<AlistVideo>`，例如
 * ```
 * /115/我不是大师/我不是大师.S01E01.mkv
 * /115/我不是大师/我不是大师.S01E02.mkv
 * ...
 * /115/与晋长安 (2025)/Season 1/与晋长安.S01E01.第1集.2160p...mkv
 * /115/风林火山.mkv
 * /115/[长安的荔枝][高码][The.Lychee.Road.2025...]/The.Lychee.Road.2025...mp4
 * ```
 *
 * 输出：一部片子一条 [MediaItem] + 一集一条 [Episode]，同一个剧的多集自动合并。
 *
 * 先按来源和目录分区，再按解析出的片名、年份、季号拆分剧集；电影独立保留。
 * 根目录、挂载根和泛目录不作为片名。作品目录可帮助补全缺失或污染的文件名。
 * 跨目录合并要求片名、年份、类型一致；来源、季和编码版本保留为不同播放线路。
 */
// Frozen v0.2.6 identity adapter only. Active membership is in AlistLibraryBuilder.
internal object LegacyAlistLibraryBuilder {

    /** 构建结果 */
    data class Result(
        val items: List<LibraryEntry>,
        val skipped: Int,
    )

    /** 一个媒体条目 + 它对应的实际文件 */
    data class LibraryEntry(
        val item: MediaItem,
        val files: List<AlistVideo>,
        /** 已解析出的集列表（电影为单集） */
        val episodes: List<Episode>,
        val routes: List<PlayRoute> = emptyList(),
        val legacyIds: Set<String> = emptySet(),
        val season: Int? = null,
    )

    /**
     * 主入口。
     * @param videos 扫描出来的所有视频
     */
    fun build(videos: List<AlistVideo>): Result {
        val entries = mutableListOf<LibraryEntry>()
        for ((parent, files) in videos.distinctBy { it.sourceIdentity to it.path }
            .groupBy { it.sourceIdentity to it.path.substringBeforeLast('/') }) {
            val parentDir = parent.second
            val dirName = parentDir.substringAfterLast('/')
            val mount = files.first().mountRoot.ifBlank {
                "/" + files.first().path.trimStart('/').substringBefore('/')
            }
            val atMount = parentDir.isBlank() || parentDir == "/" || parentDir == mount
            val directoryTitle = if (atMount) "" else dirName
            val parsed = files.map { it to FileNameParser.parse(it.name) }
            val episodic = parsed.filter { it.second.episode != null }
            val groups = episodic.groupBy { (_, info) ->
                // Mixed folders are split before choosing a display title.
                Triple(identityTitle(info.title), info.year, info.season ?: seasonFrom(parentDir))
            }
            for ((key, group) in groups) {
                val uniqueTitles = groups.keys.map { it.first }.filter { it.isNotBlank() }.distinct()
                val folderIsWork = !atMount && !isGenericDir(dirName) && uniqueTitles.size <= 1
                val title = if (folderIsWork) directoryTitle else group.first().second.title
                val groupPath = if (groups.size == 1) parentDir
                    else "$parentDir/#" + listOf(key.first, key.second, key.third).joinToString(":")
                buildSeries(groupPath, title, group)?.let { entry ->
                    val finalTitle = entry.item.title.ifBlank { rawTitle(group.first().first.name) }
                    val season = key.third
                    entries += entry.copy(
                        item = entry.item.copy(title = finalTitle,
                            id = if (group.first().first.sourceIdentity.isBlank()) entry.item.id
                            else "alist_${stableId(group.first().first.sourceIdentity + "|" + groupPath)}"),
                        legacyIds = setOf("alist_${stableId(parentDir)}"),
                        season = season,
                    )
                }
            }
            // ★ A series in this folder must never swallow adjacent movie files.
            parsed.filter { it.second.episode == null }.forEach { (file, info) ->
                buildMovie(file, info, directoryTitle, files.size)?.let { entries += it }
            }
        }
        val merged = entries.groupBy { entry ->
            val title = identityTitle(entry.item.title)
            // Year is required for cross-folder merging: remakes/numeric names stay independent.
            if (entry.item.year == null || title.isBlank() || title.all { it.isDigit() })
                "path:${entry.item.id}"
            else "${entry.item.kind}:$title:${entry.item.year}"
        }.values.map { copies ->
            if (copies.size == 1) copies.single() else {
                val ordered = copies.sortedWith(compareBy<LibraryEntry> { it.season ?: 0 }
                    .thenBy { it.files.first().sourceIdentity }.thenBy { it.files.first().path })
                val first = ordered.first()
                val routes = ordered.flatMap { entry ->
                    versionRoutes(entry, numbered = true)
                }.mapIndexed { i, route -> route.copy(name = "${i + 1} · ${route.name}") }
                first.copy(files = ordered.flatMap { it.files }, routes = routes,
                    legacyIds = ordered.flatMap { it.legacyIds + it.item.id }.toSet())
            }
        }
        return Result(merged.sortedWith(compareByDescending<LibraryEntry> { it.episodes.size }
            .thenBy { it.item.title }), 0)
    }

    private fun rawTitle(name: String) = name.substringBeforeLast('.', name)

    private fun identityTitle(title: String) = title.lowercase()
        .replace(Regex("[\\s._\\-（）()\\[\\]]+"), "").trim()

    private fun seasonFrom(path: String): Int? =
        Regex("(?i)(?:season[ ._-]*|/s)(\\d{1,2})(?:/|$)").find(path)?.groupValues?.get(1)?.toIntOrNull()

    private fun versionRoutes(entry: LibraryEntry, numbered: Boolean): List<PlayRoute> {
        if (entry.routes.isNotEmpty()) return entry.routes
        val filesByPath = entry.files.associateBy { it.path }
        fun codec(file: AlistVideo) = Regex("(?i)(?:x26[45]|h[.]?26[45]|hevc|av1)")
            .find(file.name)?.value.orEmpty().lowercase()
        val routes = entry.episodes.groupBy { episode ->
            val file = filesByPath.getValue(episode.playId)
            listOf(file.sourceIdentity, file.path.substringBeforeLast('/'),
                FileNameParser.parse(file.name).resolution.orEmpty(), codec(file)).joinToString("|")
        }.values.flatMap { episodes ->
            val byIndex = episodes.sortedBy { it.playId }.groupBy { it.index }.toSortedMap()
            // Unknown edition tags may still produce duplicate indexes: retain separate lanes.
            (0 until byIndex.values.maxOf { it.size }).map { lane ->
                val variant = byIndex.values.mapNotNull { it.getOrNull(lane) }
                val file = filesByPath.getValue(variant.first().playId)
                val quality = FileNameParser.parse(file.name).resolution.orEmpty()
                val source = file.mountRoot.substringAfterLast('/').ifBlank {
                    file.path.trimStart('/').substringBefore('/')
                }
                val label = listOf(source, entry.season?.let { "S$it" }.orEmpty(), quality, codec(file))
                    .filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "片库" }
                PlayRoute(label + if (byIndex.values.any { it.size > 1 }) " · ${lane + 1}" else "", variant, true)
            }
        }
        return if (!numbered && entry.season == null && routes.size == 1)
            listOf(routes.single().copy(name = "片库")) else routes
    }

    fun toPlayRoutes(entry: LibraryEntry, preferredPath: String = ""): List<PlayRoute> {
        val routes = versionRoutes(entry, false)
        if (preferredPath.isBlank()) return routes
        return routes.sortedByDescending { route -> route.episodes.any {
            it.playId == preferredPath || it.playId.startsWith(preferredPath.trimEnd('/') + "/")
        } }
    }

    fun find(entries: List<LibraryEntry>, item: MediaItem): LibraryEntry? =
        entries.firstOrNull { it.item.id == item.id } ?: entries.firstOrNull { matches(it, item) }

    fun matches(entry: LibraryEntry, item: MediaItem): Boolean =
        entry.item.id == item.id || item.id in entry.legacyIds ||
            (item.sourceId.isNotBlank() && entry.files.any { it.path == item.sourceId })

    /** 构建一部剧集 */
    private fun buildSeries(
        parentDir: String,
        dirName: String,
        parsedFiles: List<Pair<AlistVideo, FileNameParser.Parsed>>,
    ): LibraryEntry? {
        // 父目录（用于 "与晋长安/Season 1/xxx.mkv" 这种多一层结构）
        val grandParentName = parentDir.trimEnd('/')
            .substringBeforeLast('/')
            .substringAfterLast('/')

        val titleFromFiles = parsedFiles
            .map { it.second.title }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key

        val title = pickTitle(titleFromFiles, dirName, grandParentName)
        if (title.isBlank()) return null

        val year = parsedFiles.mapNotNull { it.second.year }
            .groupingBy { it }.eachCount()
            .maxByOrNull { it.value }?.key

        val season = parsedFiles.mapNotNull { it.second.season }
            .groupingBy { it }.eachCount()
            .maxByOrNull { it.value }?.key

        // 集列表：按集号排序
        val episodes = parsedFiles
            .filter { it.second.episode != null }
            .sortedBy { it.second.episode }
            .mapIndexed { idx, (file, parsed) ->
                Episode(
                    index = parsed.episode ?: (idx + 1),
                    name = parsed.episode?.toString() ?: file.name,
                    flag = "alist",
                    playId = file.path,
                    sizeBytes = file.sizeBytes,
                    // url 留空：播放前由 AlistClient.getFile() 现取带签名的直链
                    url = null,
                )
            }

        if (episodes.isEmpty()) return null

        val item = MediaItem(
            id = "alist_${stableId(parentDir)}",
            title = title,
            originalTitle = parsedFiles.mapNotNull { it.second.title }
                .firstOrNull { it.any { c -> c.code in 0x41..0x7A } },
            year = year,
            kind = MediaKind.SERIES,
            fromLibrary = true,
            sourceKey = "alist",
            sourceId = parentDir,
            // 副标题展示季号
            overview = buildString {
                append("私有片库 · 共 ${episodes.size} 集")
                season?.let { append(" · 第${it}季") }
                val totalMb = parsedFiles.sumOf { it.first.sizeBytes } / 1024.0 / 1024.0
                if (totalMb > 1024) append(String.format(" · %.1fGB", totalMb / 1024))
                else append(String.format(" · %.0fMB", totalMb))
            },
        )

        return LibraryEntry(item, parsedFiles.map { it.first }, episodes)
    }

    /** 构建一部电影（单个文件） */
    private fun buildMovie(
        file: AlistVideo,
        parsed: FileNameParser.Parsed,
        dirNameIfUseful: String,
        filesInDir: Int,
    ): LibraryEntry? {
        // 目录里只有一个文件时，目录名往往比文件名更干净
        val title = if (filesInDir == 1 && dirNameIfUseful.isNotBlank() && !isGenericDir(dirNameIfUseful)) {
            pickTitle(parsed.title, dirNameIfUseful)
        } else {
            parsed.title.ifBlank { rawTitle(file.name) }
        }
        if (title.isBlank()) return null

        val episode = Episode(
            index = 1,
            name = "正片",
            flag = "alist",
            playId = file.path,
            sizeBytes = file.sizeBytes,
            url = null,
        )

        val item = MediaItem(
            id = "alist_${stableId(if (file.sourceIdentity.isBlank()) file.path else file.sourceIdentity + "|" + file.path)}",
            title = title,
            originalTitle = parsed.title.takeIf {
                it.isNotBlank() && it.any { c -> c.code in 0x41..0x7A }
            },
            year = parsed.year,
            kind = MediaKind.MOVIE,
            fromLibrary = true,
            sourceKey = "alist",
            sourceId = file.path,
            overview = buildString {
                append("私有片库")
                parsed.resolution?.let { append(" · $it") }
                append(" · ${file.sizeText}")
            },
        )

        return LibraryEntry(item, listOf(file), listOf(episode), legacyIds = setOf("alist_${stableId(file.path)}"))
    }

    /**
     * 选更好的片名 —— 这是媒体库质量的关键一步。
     *
     * 实测真实片库的文件名长这样：
     * ```
     * /夸克/炼气十万年/火 东丨S01E350.mp4        ← 文件名里混了无关前缀
     * /夸克/凡人修仙传动漫/第93话.mp4             ← 文件名里根本没片名
     * /夸克/现在就出发第四季/2026.10.17_第2期上_4K_60fps.mp4
     * /115/我不是大师/我不是大师.S01E01.mkv       ← 这个正常
     * ```
     *
     * 结论：**文件名根本不可靠，目录名才是人类整理过的真相**。
     *
     * 判定优先级：
     *  1. 目录名清洗后仍含中文 → 直接信目录名（"炼气十万年"、"凡人修仙传动漫"）
     *  2. 目录名是纯英文/数字（可能是 "Season 1"、"1080p" 这种）→ 用文件名解析结果
     *  3. 目录名清洗后为空 → 用文件名解析结果
     *  4. 两者都空 → 用原始目录名兜底
     */
    private fun pickTitle(fromFile: String?, dirName: String, parentDirName: String? = null): String {
        val cleanDir = cleanDirName(dirName)
        val f = fromFile?.trim().orEmpty()

        // 父目录名也没用时（Season 1 这种情况）往上找一层
        val usefulParent = parentDirName
            ?.takeIf { it.isNotBlank() && !isGenericDir(it) }
            ?.let { cleanDirName(it) }

        // ① 目录名有中文 → 最可信
        if (!isGenericDir(dirName) && cleanDir.any { it.code > 0x4E00 }) return cleanDir

        // ② 目录名是通用名（Season 1 / S01 / 1080p）→ 用父目录
        if (isGenericDir(dirName)) {
            if (!usefulParent.isNullOrBlank() && usefulParent.any { it.code > 0x4E00 }) {
                return usefulParent
            }
            if (f.isNotBlank()) return f
            if (!usefulParent.isNullOrBlank()) return usefulParent
        }

        // ③ 目录名纯英文但有意义（"The.Lychee.Road"）→ 用目录名
        if (cleanDir.isNotBlank() && cleanDir.length >= 2) {
            // 但文件名解析出的结果更完整时优先（如 "长安的荔枝" 在文件名里）
            if (f.any { it.code > 0x4E00 } && cleanDir.none { it.code > 0x4E00 }) {
                return f
            }
            return cleanDir
        }

        // ④ 全都空 → 兜底
        return f.ifBlank { dirName }.ifBlank { "未命名" }
    }

    /** 判断目录名是不是"通用壳"（不含实际片名信息） */
    private fun isGenericDir(name: String): Boolean {
        val n = name.trim().lowercase()
        if (n.isEmpty()) return true
        // Season 1 / S01 / 第一季 / 全集 / 正片 / 1080p 这类
        val generic = listOf(
            "season", "s0", "s1", "s2", "s3", "s4", "s5",
            "1080p", "2160p", "720p", "4k", "8k", "hdr", "uhd",
            "全集", "正片", "更新中", "完结", "合集", "下载", "video", "movies", "tv",
            "电影", "电视剧", "剧集", "动漫", "综艺", "视频", "网盘", "夸克", "115", "阿里云盘", "百度网盘", "天翼云盘", "根目录"
        )
        return generic.any { n == it || n.startsWith("$it ") || n.matches(Regex("""^${Regex.escape(it)}\d*$""")) }
    }

    /** 去掉目录名里的年份括号、清晰度等噪声 */
    private fun cleanDirName(raw: String): String {
        var s = raw
        // 去掉 [xxx] 整段（常见于资源组标记）
        s = s.replace(Regex("""\[[^\]]*]"""), " ")
        // 去掉 (2025) 这种年份括号，但保留年份信息给 year 字段
        s = s.replace(Regex("""\s*[\\(（]\s*(19|20)\d{2}\s*[\\)）]\s*"""), " ")
        // 去掉常见技术后缀
        s = s.replace(
            Regex(
                """\b(2160p|1080p|720p|4K|HDR|DV|WEB-DL|BluRay|H\.?26[45]|HEVC|DDP|DTS|AAC|HQ|60FPS|IMAX)\b""",
                RegexOption.IGNORE_CASE
            ),
            " "
        )
        return s.replace(Regex("""\s{2,}"""), " ").trim().trim('-', '_', ' ')
    }

    /**
     * 稳定 id：路径的哈希。
     * 为什么不用自增：片库重扫后 id 必须不变，否则"继续观看"会丢。
     */
    private fun stableId(path: String): String {
        var h = 1125899906842597L
        for (c in path) h = 31 * h + c.code
        return h.toULong().toString(16)
    }

    /**
     * 把 LibraryEntry 转成详情页需要的 PlayRoute。
     * 兼容旧调用，返回按来源、季和版本生成的首条线路；完整列表使用 toPlayRoutes。
     */
    fun toPlayRoute(entry: LibraryEntry, client: AlistClient): PlayRoute {
        return toPlayRoutes(entry).first()
    }

    /** 统计信息，给片库页顶部展示 */
    data class Stats(
        val totalItems: Int,
        val seriesCount: Int,
        val movieCount: Int,
        val episodeCount: Int,
    )

    fun stats(entries: List<LibraryEntry>): Stats = Stats(
        totalItems = entries.size,
        seriesCount = entries.count { it.item.kind == MediaKind.SERIES },
        movieCount = entries.count { it.item.kind == MediaKind.MOVIE || it.item.kind == MediaKind.UNKNOWN },
        episodeCount = entries.sumOf { it.episodes.size },
    )
}
