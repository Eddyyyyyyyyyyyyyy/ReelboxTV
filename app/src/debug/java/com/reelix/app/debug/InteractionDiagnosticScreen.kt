package com.reelix.app.debug

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.viewinterop.AndroidView
import com.reelix.app.data.model.MediaDetail
import com.reelix.app.data.model.MediaItem
import com.reelix.app.data.search.SearchResultGroup
import com.reelix.app.data.search.SearchResultGroups
import com.reelix.app.ui.screen.detail.DetailView
import com.reelix.app.ui.screen.search.SearchResultView
import kotlinx.coroutines.delay

@Composable
fun DetailDiagnosticScreen(detail: MediaDetail) {
    AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
        DetailView(context, { _, _ -> }, {}).apply {
            render(detail, false, false)
            post { focusFirst() }
        }
    })
}

@Composable
fun SearchDiagnosticScreen() {
    var selected by remember { mutableStateOf(SearchResultGroups.ALL) }
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1500); revision++ } }
    val groups = (1..12).map { index -> SearchResultGroup("source-$index", "测试源$index",
        (1..(index + if (revision % 2 == 0) 0 else 1)).map {
            MediaItem("$index-$it", "源${index}影片$it", sourceKey = "source-$index")
        }) }.let { sources -> listOf(SearchResultGroup(SearchResultGroups.ALL, "全部显示", sources.flatMap { it.items })) + sources }
    AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
        SearchResultView(context, {}, {}, { key ->
            selected = key
            android.util.Log.i("ReelixInteraction", "searchSelection=$key")
        }).apply { post { focusFirst() } }
    }, update = { view -> view.render(groups, selected, emptyMap(), false) })
}
