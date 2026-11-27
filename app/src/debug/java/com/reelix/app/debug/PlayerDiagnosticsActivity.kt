package com.reelix.app.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import com.reelix.app.data.model.*
import com.reelix.app.ui.screen.player.PlayerScreen
import com.reelix.app.ui.screen.player.ResumePoint
import com.reelix.app.ui.screen.player.SkipInfo
import com.reelix.app.ui.screen.player.SkipStore
import com.reelix.app.player.DecoderMode
import com.reelix.app.player.EngineKind
import com.reelix.app.ui.theme.ReelboxTheme

/** Debug-only fixture: exercise real player controls without remote sources or account data. */
class PlayerDiagnosticsActivity : ComponentActivity() {
    private var networkFixture: LocalMediaFixture? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val url = if (intent.getBooleanExtra("networkFixture", false)) {
            LocalMediaFixture(java.io.File(filesDir, "player-multiaudio.mp4"))
                .also { networkFixture = it }.url
        } else intent.getStringExtra("uri") ?: "file://${filesDir.absolutePath}/player-test.mp4"
        val count = intent.getIntExtra("episodes", 120).coerceIn(1, 1000)
        val headers = if (intent.getBooleanExtra("requiredHeaders", false)) mapOf("Cookie" to "fixture=ok") else emptyMap()
        SkipStore.save(this, "debug-player-fixture", SkipInfo(outroStartMs = intent.getLongExtra("outroMs", 0)))
        val drmScheme = intent.getStringExtra("drmScheme")?.takeIf { it.isNotBlank() }
        val detail = MediaDetail(MediaItem(id = "debug-player-fixture", title = "播放器回归测试"),
            listOf("测试版本 A", "测试版本 B").map { name -> PlayRoute(name,
                (1..count).map { Episode(it, "第${it}集", playId = "$name/$it", url = url,
                    headers = headers, drmScheme = drmScheme) }) })
        setContent { ReelboxTheme {
            if (intent.getStringExtra("screen") == "library") {
                LibraryDiagnosticScreen()
            } else if (intent.getStringExtra("screen") == "home") {
                HomeDiagnosticScreen()
            } else if (intent.getStringExtra("screen") == "detail") {
                DetailDiagnosticScreen(detail)
            } else if (intent.getStringExtra("screen") == "search") {
                SearchDiagnosticScreen()
            } else if (intent.getStringExtra("screen") == "hls-proxy") {
                HlsProxyDiagnosticScreen()
            } else PlayerScreen(detail, onBack = { finish() },
                defaultDecoder = DecoderMode.fromStored(intent.getStringExtra("decoder")),
                initialEngine = if (intent.getStringExtra("engine") == "VLC") EngineKind.VLC else EngineKind.EXO,
                loadResumePosition = { ResumePoint(1, intent.getLongExtra("startMs", 0)) })
        } }
    }

    override fun onDestroy() {
        networkFixture?.close()
        super.onDestroy()
    }
}
