package com.reelix.app.debug

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.core.view.doOnLayout
import com.reelix.app.R
import com.reelix.app.data.alist.*
import com.reelix.app.data.repo.LibraryRepository
import com.reelix.app.ui.screen.library.LibraryView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Isolated account-free fixture: first refresh succeeds, second fails without deleting posters. */
@Composable
fun LibraryDiagnosticScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember {
        val source = object : LibraryDataSource {
            override var isLoggedIn = false
            var scans = 0
            override suspend fun login(username: String, password: String): Result<Unit> {
                isLoggedIn = true; return Result.success(Unit)
            }
            override suspend fun getFile(path: String): AlistFileData? = null
            override suspend fun scanVideos(root: String, maxDepth: Int, forceRefresh: Boolean,
                onProgress: (Int, String) -> Unit): List<AlistVideo> {
                scans++
                onProgress(3, "/fixture/drive")
                delay(7000)
                if (scans > 1) error("fixture scan failed")
                check(forceRefresh)
                return listOf(
                    AlistVideo("/fixture/电影A.2025.mp4", "电影A.2025.mp4", 100),
                    AlistVideo("/fixture/电影B.2026.mp4", "电影B.2026.mp4", 100),
                )
            }
        }
        LibraryRepository(mapOf("alist" to source), object : LibrarySnapshotStore {
            override fun read() = listOf(AlistVideo("/fixture/原有电影.mp4", "原有电影.mp4", 100))
            override fun write(videos: List<AlistVideo>) = Unit
        })
    }
    val state by repo.state.collectAsState()
    val stats by repo.stats.collectAsState()
    val progress by repo.scanProgress.collectAsState()
    var result by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(repo) { repo.load("fixture", "", allowScan = false) }
    val refresh by rememberUpdatedState<() -> Unit>({
        if (!busy) {
            busy = true; result = null
            scope.launch {
                try {
                    val scan = repo.load("fixture", "", force = true)
                    result = if (scan.isSuccess) context.getString(R.string.library_refresh_done, repo.allItems().size)
                        else context.getString(R.string.library_error, scan.exceptionOrNull()?.message.orEmpty())
                } finally { busy = false }
            }
        }
    })
    AndroidView(modifier = Modifier.fillMaxSize(), factory = {
        LibraryView(it, {}, { refresh() }, {}).also { view -> view.doOnLayout { view.focusFirst() } }
    },
        update = { it.render(state, stats, emptyMap(), 0, progress, result) })
}

@Composable
fun HomeDiagnosticScreen() {
    var history by remember { mutableStateOf((1..8).map { com.reelix.app.data.db.PlayProgressEntity(
        mediaId = "debug-history-$it", title = "历史影片$it", positionMs = 10000, durationMs = 60000) }) }
    AndroidView(modifier = Modifier.fillMaxSize(), factory = {
        com.reelix.app.ui.screen.home.HomeView(it, {}, {}, { id, completed ->
            history = history.filterNot { entry -> entry.mediaId == id }
            completed(true)
        }, {}, {}, {}).also { view ->
            view.render(history, false)
            view.doOnLayout { view.restoreFocus(0) }
        }
    }, update = { view -> view.render(history, false) })
}
