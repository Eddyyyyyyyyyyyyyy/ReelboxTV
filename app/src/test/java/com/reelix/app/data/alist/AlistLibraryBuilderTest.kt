package com.reelix.app.data.alist

import com.reelix.app.data.model.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AlistLibraryBuilder 的分组/命名测试。
 *
 * 这块逻辑的错误代价比解析器更贵：
 *  分错组 → 一部 38 集的剧变成 38 个独立条目，片库直接不可用；
 *  命名错 → 用户搜不到、认不出。
 * 所以每一个真实目录结构都要有一条用例钉住。
 */
class AlistLibraryBuilderTest {

    @Test fun `网盘根目录的单电影不能命名为网盘或电影`() {
        for (root in listOf("", "/夸克", "/115", "/电影", "/阿里云盘/电影")) {
            val item = AlistLibraryBuilder.build(listOf(video("$root/流浪地球.2019.1080p.mkv"))).items.single().item
            assertEquals(root, "流浪地球", item.title)
        }
    }

    @Test fun `分类目录散放每个视频各一海报`() {
        val result = AlistLibraryBuilder.build(listOf(
            video("/夸克/电影/流浪地球.2019.mkv"),
            video("/夸克/电影/三体.S01E01.mkv"), video("/夸克/电影/三体.S01E02.mkv"),
            video("/夸克/电影/庆余年.S01E01.mkv"),
        )).items
        assertEquals(4, result.size)
        assertEquals(setOf("流浪地球", "三体", "庆余年"), result.map { it.item.title }.toSet())
        assertEquals(4, result.sumOf { it.files.size })
    }

    @Test fun `作品目录不同季不拆海报且播放索引唯一`() {
        val result = AlistLibraryBuilder.build(listOf(
            video("/夸克/三体/三体.S01E01.mkv"), video("/夸克/三体/三体.S02E01.mkv"),
        )).items
        assertEquals(1, result.size)
        assertEquals(2, result.single().episodes.map { it.index }.toSet().size)
        assertEquals(listOf(1, 1), result.single().episodes.map { it.episodeNumber })
    }

    @Test fun `无法识别的文件也应可访问`() {
        val entry = AlistLibraryBuilder.build(listOf(video("/夸克/电影/123456.mp4"))).items.single()
        assertEquals("123456", entry.item.title)
    }

    @Test fun `显式挂载根不能提供作品名`() {
        val file = video("/私人收藏/流浪地球.2019.mkv").copy(mountRoot = "/私人收藏")
        assertEquals("流浪地球", AlistLibraryBuilder.build(listOf(file)).items.single().item.title)
    }

    @Test fun `同片名同年份跨网盘分别展示并保留资源`() {
        val a = video("/夸克/流浪地球.2019.1080p.mkv").copy(mountRoot = "/夸克", sourceIdentity = "server|account")
        val b = video("/115/流浪地球.2019.2160p.mkv").copy(mountRoot = "/115", sourceIdentity = "server|account")
        val oldA = AlistLibraryBuilder.build(listOf(a)).items.single().item
        val separate = AlistLibraryBuilder.build(listOf(a, b)).items
        assertEquals(2, separate.size)
        assertNotNull(AlistLibraryBuilder.find(separate, oldA))
        assertEquals(setOf(a.path, b.path), separate.flatMap { it.episodes }.map { it.playId }.toSet())
    }

    @Test fun `同名不同年份不能合并`() {
        assertEquals(2, AlistLibraryBuilder.build(listOf(
            video("/电影/Dune.1984.mkv"), video("/电影/Dune.2021.mkv")
        )).items.size)
    }

    @Test fun `不同账号同路径仍是不同文件`() {
        val a = video("/挂载/未知电影.mkv").copy(mountRoot = "/挂载", sourceIdentity = "server|a")
        val b = a.copy(sourceIdentity = "server|b")
        val items = AlistLibraryBuilder.build(listOf(a, b)).items
        assertEquals(2, items.size)
        assertEquals(2, items.map { it.item.id }.toSet().size)
        assertEquals(items.last(), AlistLibraryBuilder.find(items, items.last().item))
    }

    @Test fun `同集不同版本作为全部文件保留且索引唯一`() {
        val entry = AlistLibraryBuilder.build(listOf(
            video("/夸克/三体/三体.S01E01.1080p.x264.mkv"),
            video("/夸克/三体/三体.S01E01.1080p.x265.mkv"),
            video("/夸克/三体/三体.S01E02.1080p.x264.mkv"),
            video("/夸克/三体/三体.S01E02.1080p.x265.mkv"),
        )).items.single()
        val routes = AlistLibraryBuilder.toPlayRoutes(entry)
        assertEquals(1, routes.size)
        assertTrue(routes.all { route -> route.episodes.map { it.index }.toSet().size == route.episodes.size })
        assertEquals(4, routes.sumOf { it.episodes.size })
    }

    @Test fun `历史路径定位原网盘海报`() {
        val entries = AlistLibraryBuilder.build(listOf(
            video("/115/流浪地球.2019.mkv"), video("/夸克/流浪地球.2019.mkv")
        )).items
        val legacyId = LegacyAlistLibraryBuilder.build(entries.flatMap { it.files }).items.single().item.id
        val item = com.reelix.app.data.model.MediaItem(legacyId, "流浪地球", sourceId = "/夸克/流浪地球.2019.mkv")
        assertEquals("/夸克/流浪地球.2019.mkv", AlistLibraryBuilder.find(entries, item)?.episodes?.single()?.playId)
    }

    private fun video(path: String, size: Long = 800L * 1024 * 1024): AlistVideo {
        val name = path.substringAfterLast('/')
        return AlistVideo(path = path, name = name, sizeBytes = size)
    }

    // ---------- 常见网盘结构：父目录即作品 ----------

    @Test
    fun `同目录多集合并成一部剧`() {
        val videos = listOf(
            video("/115/我不是大师/我不是大师.S01E01.mkv"),
            video("/115/我不是大师/我不是大师.S01E02.mkv"),
            video("/115/我不是大师/我不是大师.S01E03.mkv"),
        )
        val result = AlistLibraryBuilder.build(videos)

        assertEquals("三个文件必须合成一条", 1, result.items.size)
        val entry = result.items.first()
        assertEquals("我不是大师", entry.item.title)
        assertEquals(MediaKind.SERIES, entry.item.kind)
        assertEquals(3, entry.episodes.size)
        assertEquals(listOf(1, 2, 3), entry.episodes.map { it.index })
        assertTrue("片库条目要标记 fromLibrary", entry.item.fromLibrary)
    }

    @Test
    fun `集号乱序也要按集号排序`() {
        val videos = listOf(
            video("/网盘/庆余年/庆余年 第03集.mkv"),
            video("/网盘/庆余年/庆余年 第01集.mkv"),
            video("/网盘/庆余年/庆余年 第02集.mkv"),
        )
        val entry = AlistLibraryBuilder.build(videos).items.single()
        assertEquals(listOf(1, 2, 3), entry.episodes.map { it.index })
    }

    @Test
    fun `Episode 的 url 必须留空由播放时现取`() {
        val videos = listOf(
            video("/网盘/某剧/某剧 第01集.mkv"),
            video("/网盘/某剧/某剧 第02集.mkv"),
        )
        val entry = AlistLibraryBuilder.build(videos).items.single()
        // 115/夸克的直链带时效签名，缓存下来播放必失败
        assertTrue(entry.episodes.all { it.url == null })
        assertTrue("playId 要带上完整路径，播放时用它换直链", entry.episodes.all { it.playId.startsWith("/网盘/某剧/") })
    }

    // ---------- 单文件电影 ----------

    @Test
    fun `单文件电影用目录名当片名`() {
        val videos = listOf(video("/115/风林火山/风林火山.2024.2160p.mkv"))
        val result = AlistLibraryBuilder.build(videos)

        assertEquals(1, result.items.size)
        val entry = result.items.first()
        assertEquals(MediaKind.MOVIE, entry.item.kind)
        assertEquals("风林火山", entry.item.title)
        assertEquals(1, entry.episodes.size)
        assertEquals("风林火山.2024.2160p.mkv", entry.episodes.first().name)
    }

    @Test
    fun `同名目录同名文件不会出现重复后缀`() {
        val videos = listOf(video("/夸克/长安的荔枝/长安的荔枝.2025.1080p.mp4"))
        val entry = AlistLibraryBuilder.build(videos).items.single()
        assertTrue("片名不该带扩展名，实际=${entry.item.title}", !entry.item.title.contains(".mp4"))
    }

    // ---------- 多文件目录（合集）：不能互相吞并 ----------

    @Test
    fun `同目录多部无集号电影各自独立`() {
        val videos = listOf(
            video("/Movies/复仇者联盟4.mkv", 4L * 1024 * 1024 * 1024),
            video("/Movies/钢铁侠3.mkv", 2L * 1024 * 1024 * 1024),
            video("/Movies/雷神2.mkv"),
        )
        val result = AlistLibraryBuilder.build(videos)
        // 没有集号的多个文件，应当各算一部电影
        assertEquals(3, result.items.size)
        assertTrue(result.items.all { it.item.kind == MediaKind.MOVIE })
        assertTrue("三部电影要能区分开", result.items.map { it.item.title }.toSet().size == 3)
    }

    // ---------- 多级目录：Season 1 ----------

    @Test
    fun `Season子目录应回到父目录取片名`() {
        val videos = listOf(
            video("/115/与晋长安 (2025)/Season 1/与晋长安.S01E01.第1集.2160p.mkv"),
            video("/115/与晋长安 (2025)/Season 1/与晋长安.S01E02.第2集.2160p.mkv"),
        )
        val entry = AlistLibraryBuilder.build(videos).items.single()
        assertTrue("片名应含与晋长安，实际=${entry.item.title}", entry.item.title.contains("与晋长安"))
        assertTrue("不该是 Season 1，实际=${entry.item.title}", !entry.item.title.contains("Season", true))
        assertTrue("不该带年份括号，实际=${entry.item.title}", !entry.item.title.contains("2025"))
    }

    // ---------- id 稳定性 ----------

    @Test
    fun `重复构建产生相同 id`() {
        val videos = listOf(
            video("/网盘/某剧/某剧 第01集.mkv"),
            video("/网盘/某剧/某剧 第02集.mkv"),
        )
        val a = AlistLibraryBuilder.build(videos).items.single().item.id
        val b = AlistLibraryBuilder.build(videos).items.single().item.id
        assertEquals("id 必须稳定，否则重扫后继续观看会丢", a, b)
    }

    @Test
    fun `不同作品 id 不同`() {
        val a = AlistLibraryBuilder.build(listOf(video("/网盘/某剧/某剧 第01集.mkv"))).items.single().item.id
        val b = AlistLibraryBuilder.build(listOf(video("/网盘/别的剧/别的剧 第01集.mkv"))).items.single().item.id
        assertTrue(a != b)
    }

    // ---------- 统计 ----------

    @Test
    fun `统计区分剧集与电影`() {
        val videos = listOf(
            video("/A/剧甲/剧甲 第01集.mkv"),
            video("/A/剧甲/剧甲 第02集.mkv"),
            video("/B/电影乙/电影乙.mkv"),
        )
        val stats = AlistLibraryBuilder.stats(AlistLibraryBuilder.build(videos).items)
        assertEquals(2, stats.totalItems)
        assertEquals(1, stats.seriesCount)
        assertEquals(1, stats.movieCount)
        assertEquals(3, stats.episodeCount)
    }

    @Test
    fun `空输入不崩`() {
        val result = AlistLibraryBuilder.build(emptyList())
        assertTrue(result.items.isEmpty())
        assertEquals(0, result.skipped)
    }

    // ---------- 排序 ----------

    @Test
    fun `最近活动优先而不是集数多优先`() {
        val videos = listOf(
            video("/A/单集/单集 第01集.mkv").copy(changedAt = 2000),
            video("/B/长剧/长剧 第01集.mkv"),
            video("/B/长剧/长剧 第02集.mkv"),
            video("/B/长剧/长剧 第03集.mkv"),
        )
        val items = AlistLibraryBuilder.build(videos).items
        assertEquals("单集", items.first().item.title)
        assertEquals(1, items.first().episodes.size)
    }
}
