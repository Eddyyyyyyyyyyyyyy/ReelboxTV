package com.reelix.app.player

import com.reelix.app.data.model.Episode
import org.junit.Assert.*
import org.junit.Test

class RouteEpisodeMatchTest {
    private fun ep(label: String, index: Int = 1, original: String? = null) =
        Episode(index, label, playId = "$label-$index", originalName = original)

    @Test fun `reordered route matches actual number not index`() {
        val expected = ep("第3集", 99)
        assertEquals(RouteEpisodeMatch.Unique(expected),
            matchRouteEpisode(ep("EP03", 2), listOf(ep("第2集", 3), expected, ep("第1集", 1))))
    }

    @Test fun `season evidence must agree including missing season`() {
        assertEquals(RouteEpisodeMatch.Absent, matchRouteEpisode(ep("S02E03"), listOf(ep("S01E03"))))
        assertEquals(RouteEpisodeMatch.Absent, matchRouteEpisode(ep("S02E03"), listOf(ep("第3集"))))
        val target = ep("第二季 第三集")
        assertEquals(RouteEpisodeMatch.Unique(target), matchRouteEpisode(ep("S02E03"), listOf(target)))
    }

    @Test fun `dates and parts are independent from route order`() {
        val target = ep("2026年11月08日 下期", 8)
        assertEquals(RouteEpisodeMatch.Unique(target), matchRouteEpisode(ep("20261108下", 2),
            listOf(ep("2026-11-08上"), ep("2026-11-07下"), target)))
        assertEquals(RouteEpisodeMatch.Absent, matchRouteEpisode(ep("20261108下"), listOf(ep("20261108"))))
    }

    @Test fun `numbered variety upper and lower cannot collapse`() {
        val target = ep("第十二期（下）", 7)
        assertEquals(RouteEpisodeMatch.Unique(target),
            matchRouteEpisode(ep("第12期下"), listOf(ep("第12期上"), target)))
    }

    @Test fun `specials do not match regular episodes or other specials`() {
        assertEquals(RouteEpisodeMatch.Absent, matchRouteEpisode(ep("第3集预告"), listOf(ep("第3集"))))
        assertEquals(RouteEpisodeMatch.Absent, matchRouteEpisode(ep("SP01"), listOf(ep("OVA01"))))
        assertEquals(RouteEpisodeMatch.Absent, matchRouteEpisode(ep("番外篇 上"), listOf(ep("番外篇 下"))))
    }

    @Test fun `duplicate candidates are ambiguous even when quality differs`() {
        val targets = listOf(ep("第3集 1080P", 5), ep("EP03 4K", 9))
        assertEquals(RouteEpisodeMatch.Ambiguous(targets), matchRouteEpisode(ep("第3集"), targets))
    }

    @Test fun `original name takes precedence over generated display number`() {
        assertEquals(RouteEpisodeMatch.Absent, matchRouteEpisode(ep("第1集", original = "20261108下"),
            listOf(ep("第1集", original = "20261107下"))))
    }

    @Test fun `missing and generic labels never use index or single item fallback`() {
        for (label in listOf("", "正片", "播放", "1080P")) {
            assertEquals(RouteEpisodeMatch.Absent, matchRouteEpisode(ep(label), listOf(ep(label))))
        }
        assertEquals(RouteEpisodeMatch.Absent, matchRouteEpisode(ep("第8集"), listOf(ep("第1集", 8))))
    }

    @Test fun `exact meaningful names ignore quality and language not titles`() {
        val target = ep("重逢 国语 4K")
        assertEquals(RouteEpisodeMatch.Unique(target),
            matchRouteEpisode(ep("重逢 粤语 1080P"), listOf(target)))
        assertEquals(RouteEpisodeMatch.Absent, matchRouteEpisode(ep("重逢"), listOf(ep("离别"))))
    }

    @Test fun `ranges decimals invalid dates and conflicting numbers refuse guessing`() {
        for (label in listOf("第1-3集", "EP1.5", "20260230", "S01E02E03")) {
            assertEquals(label, RouteEpisodeMatch.Absent, matchRouteEpisode(ep(label), listOf(ep(label))))
        }
    }

    @Test fun `additional numeric and part evidence cannot be discarded`() {
        assertEquals(RouteEpisodeMatch.Absent,
            matchRouteEpisode(ep("EP03 Part 1"), listOf(ep("EP03 Part 2"))))
        assertEquals(RouteEpisodeMatch.Absent,
            matchRouteEpisode(ep("20261108上半期"), listOf(ep("20261108下半期"))))
        assertEquals(RouteEpisodeMatch.Absent,
            matchRouteEpisode(ep("第3集 (1)"), listOf(ep("第3集 (2)"))))
        assertEquals(RouteEpisodeMatch.Absent,
            matchRouteEpisode(ep("1.5"), listOf(ep("第15集"))))
        assertEquals(RouteEpisodeMatch.Absent,
            matchRouteEpisode(ep("第一至三集"), listOf(ep("第一至三集"))))
    }

    @Test fun `edits and bonus variants cannot match ordinary numbered episode`() {
        for (variant in listOf("解说版", "抢先版", "导演剪辑版", "合集", "花絮", "会员版", "纯享版")) {
            assertEquals(variant, RouteEpisodeMatch.Absent,
                matchRouteEpisode(ep("第3集 $variant"), listOf(ep("第3集"))))
        }
    }

    @Test fun `same resource IDs cannot override conflicting source evidence`() {
        val source = ep("第3集").copy(flag = "shared", playId = "same")
        val target = ep("第4集").copy(flag = "shared", playId = "same")
        assertEquals(RouteEpisodeMatch.Absent, matchRouteEpisode(source, listOf(target)))
    }

    @Test fun `returned objects order and original names remain untouched`() {
        val source = ep("第03集 国语")
        val target = ep("显示3", 77, "EP03 粤语 2160P")
        val candidates = listOf(ep("预告", 9), target)
        val before = candidates.toList()
        val result = matchRouteEpisode(source, candidates) as RouteEpisodeMatch.Unique
        assertSame(target, result.episode)
        assertEquals(before, candidates)
        assertEquals("EP03 粤语 2160P", target.originalName)
    }

    @Test fun `parts before descriptive titles retain their distinction`() {
        val target = ep("2026-11-08 下期 欢乐时光")
        assertEquals(RouteEpisodeMatch.Unique(target),
            matchRouteEpisode(ep("20261108 下"), listOf(ep("2026-11-08 上期 欢乐时光"), target)))
        assertEquals(RouteEpisodeMatch.Absent,
            matchRouteEpisode(ep("EP03 Part A"), listOf(ep("EP03 Part B"))))
        assertEquals(RouteEpisodeMatch.Absent,
            matchRouteEpisode(ep("第3集A"), listOf(ep("第3集B"))))
    }

    @Test fun `legacy labels and full width labels still match conservatively`() {
        val target = ep("第3話")
        assertEquals(RouteEpisodeMatch.Unique(target), matchRouteEpisode(ep("ＥＰ０３"), listOf(target)))
        val date = ep("2024-02-29")
        assertEquals(RouteEpisodeMatch.Unique(date), matchRouteEpisode(ep("20240229"), listOf(date)))
        assertEquals(RouteEpisodeMatch.Absent, matchRouteEpisode(ep("20230229"), listOf(ep("20230229"))))
    }

    @Test fun `malformed Chinese numbers do not collapse to their last digit`() {
        assertEquals(RouteEpisodeMatch.Absent,
            matchRouteEpisode(ep("第一二集"), listOf(ep("第2集"))))
        assertEquals(RouteEpisodeMatch.Absent,
            matchRouteEpisode(ep("第一百百集"), listOf(ep("第200集"))))
        val target = ep("EP103")
        assertEquals(RouteEpisodeMatch.Unique(target),
            matchRouteEpisode(ep("第一百零三集"), listOf(target)))
    }
}
