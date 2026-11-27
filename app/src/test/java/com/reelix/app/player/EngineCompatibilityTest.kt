package com.reelix.app.player

import org.junit.Assert.*
import org.junit.Test

class EngineCompatibilityTest {
    @Test fun `VLC的三字母语言代码与Exo的语言代码等价`() {
        assertEquals("vlc:3", matchAudioPreference(listOf(AudioChoice("vlc:3", "Track 2", "chi", 2)),
            AudioPreference("exo:0:1", "中文", "zh-CN", 2)))
    }
    @Test fun `跨内核音轨恢复按语言声道匹配而不是错误套用轨道下标`() {
        val tracks = listOf(AudioChoice("vlc:2", "英语", "en", 2), AudioChoice("vlc:3", "国语", "zh", 6))
        assertEquals("vlc:3", matchAudioPreference(tracks, AudioPreference("exo:0:0", "中文", "zh", 6)))
        assertNull(matchAudioPreference(tracks, AudioPreference("exo:0:0", "法语", "fr", 2)))
        assertNull(matchAudioPreference(tracks, null))
    }
    @Test fun `HLS子清单音轨分片和密钥均保留远端基准路径`() {
        val text = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"../key?k=1\"\n#EXT-X-MEDIA:TYPE=AUDIO,URI=\"https://audio.example/a.m3u8\"\n../seg/1.ts?x=2"
        val result = rewriteHls(text, "https://example.test/a/index.m3u8") { "proxy:$it" }
        assertTrue(result.contains("URI=\"proxy:https://example.test/key?k=1\""))
        assertTrue(result.contains("URI=\"proxy:https://audio.example/a.m3u8\""))
        assertTrue(result.endsWith("proxy:https://example.test/seg/1.ts?x=2"))
    }
    @Test fun `DASH继承BaseURL且保留分片模板占位符`() {
        val text = """<MPD xmlns="urn:mpeg:dash:schema:mpd:2011"><BaseURL>../media/</BaseURL><Period><AdaptationSet><SegmentTemplate media="s${'$'}Number${'$'}.m4s" initialization="init.mp4"/></AdaptationSet></Period></MPD>"""
        val result = rewriteDash(text, "https://example.test/manifests/main.mpd") { "https://proxy.test/?u=$it" }
        assertTrue(result.contains("https://example.test/media/s${'$'}Number${'$'}.m4s"))
        assertTrue(result.contains("https://example.test/media/init.mp4"))
    }
    @Test fun `内部代理不冒充外部网络速度`() {
        assertTrue(isLoopbackUrl("http://127.0.0.1:9978/proxy"))
        assertTrue(isLoopbackUrl("http://[::1]/proxy"))
        assertFalse(isLoopbackUrl("http://192.168.1.111:5244/movie.mp4"))
    }
}
