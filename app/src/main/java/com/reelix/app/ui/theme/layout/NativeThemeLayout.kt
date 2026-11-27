package com.reelix.app.ui.theme.layout

import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

enum class NativeThemeId { ORIGINAL, GALLERY, SIDEBAR, TOPBAR }

data class PlayButtonStyle(
    val normalFill: Int = 0xFFFFFFFF.toInt(),
    val normalText: Int = 0xFF121212.toInt(),
    val focusedFill: Int,
    val focusedText: Int,
    val focusRing: Int,
    val focusStrokePx: Int = 0,
    val progressFill: Int = 0xFF4C7DFF.toInt(),
    val progressTrack: Int = 0x33FFFFFF.toInt(),
)

data class NativeThemePalette(
    val background: Int,
    val surface: Int,
    val raisedSurface: Int,
    val primaryText: Int,
    val secondaryText: Int,
    val accent: Int,
    val onAccent: Int,
    val focusRing: Int,
    val play: PlayButtonStyle? = null,
)

enum class HistoryArrangement { POSTER_GRID, POSTER_RAIL, WIDE_RAIL }

data class HistoryPresentation(
    val arrangement: HistoryArrangement,
    val preferredColumns: Int,
    val cardWidthDp: Int,
    val cardHeightDp: Int,
    val gapDp: Int,
    val cardStyle: HistoryCardStyle? = null,
    val posterSizeApplied: Boolean = false,
)

data class HistoryCardStyle(
    val imageWidthPx: Int,
    val imageHeightPx: Int,
    val gapPx: Int,
    val titleTextPx: Float,
    val subtitleTextPx: Float,
    val titleGapPx: Int = 15,
    val subtitleGapPx: Int = 5,
    val overlayTitle: Boolean = false,
    val cornerRadiusPx: Int = 10,
    val focusStrokePx: Int = 4,
    val focusOutsetPx: Int = 5,
    val progressHeightPx: Int = 5,
) {
    val focusClearancePx: Int get() = focusOutsetPx + focusStrokePx
    val cellWidthPx: Int get() = imageWidthPx + 2 * focusClearancePx
    val cellGapPx: Int get() = (gapPx - 2 * focusClearancePx).coerceAtLeast(0)
    fun cellHeightPx(fontScale: Float = 1f): Int = height(fontScale) + 2 * focusClearancePx
    fun forPosterColumns(columns: Int): HistoryCardStyle {
        val scale = 6f / columns.coerceIn(4, 8)
        return if (scale == 1f) this else copy(
            imageWidthPx = (imageWidthPx * scale).roundToInt().coerceAtLeast(1),
            imageHeightPx = (imageHeightPx * scale).roundToInt().coerceAtLeast(1))
    }
    fun height(fontScale: Float = 1f): Int = imageHeightPx +
        (if (overlayTitle) 0 else titleGapPx + (titleTextPx * 1.4f * fontScale).toInt()) +
        subtitleGapPx + (subtitleTextPx * 1.4f * fontScale).toInt()
}

data class DetailActionPresentation(
    val playWidthPx: Int,
    val playHeightPx: Int,
    val playTextPx: Float = 25f,
    val toolWidthsPx: List<Int> = listOf(250, 220),
    val toolHeightPx: Int = 61,
    val toolTextPx: Float = 23f,
    val gapPx: Int = 16,
    val toolsBelow: Boolean = false,
    val toolGroupGapPx: Int = gapPx,
)

data class EpisodeRailPresentation(
    val widthPx: Int,
    val heightPx: Int,
    val gapPx: Int,
    val titleTextPx: Float = 24f,
    val subtitleTextPx: Float = 18f,
    val horizontalPaddingPx: Int = 22,
    val verticalPaddingPx: Int = 20,
    val cornerRadiusPx: Int = 8,
    val focusStrokePx: Int = 4,
    val focusOutsetPx: Int = 5,
    val captionHeightPx: Int = 0,
    val captionGapPx: Int = 0,
    val captionTextPx: Float = 24f,
    val qualityWidthPx: Int = 108,
    val qualityTextPx: Float = 22f,
    val progressHeightPx: Int = 6,
) {
    val focusClearancePx: Int get() = focusStrokePx + focusOutsetPx
    val cellWidthPx: Int get() = widthPx + 2 * focusClearancePx
    val cellHeightPx: Int get() = heightPx + captionHeightPx + captionGapPx + 2 * focusClearancePx
    val cellGapPx: Int get() = (gapPx - 2 * focusClearancePx).coerceAtLeast(0)
}
data class HomeThemeSlots(
    val root: FrameLayout,
    val artwork: ImageView,
    val foregroundPoster: ImageView,
    val title: TextView,
    val metadata: TextView,
    val play: View,
    val detail: View,
    val progress: View,
    val historyHeader: View,
    val history: View,
    val emptyState: View,
    val configureHistory: (HistoryPresentation) -> Unit,
    val overview: TextView? = null,
    val resumeLabel: TextView? = null,
    val resumeTime: TextView? = null,
    val collectionHeader: View? = null,
    val recentHeader: View? = null,
    val recentItems: View? = null,
    val collectionEmpty: View? = null,
    val configureRecent: ((HistoryPresentation) -> Unit)? = null,
    val historyTitle: TextView? = null,
    val historyLink: TextView? = null,
    val recentTitle: TextView? = null,
)

data class DetailThemeSlots(
    val root: FrameLayout,
    val backdrop: View,
    val foregroundPoster: ImageView,
    val scroll: ScrollView,
    val title: TextView,
    val metadata: TextView,
    val overview: TextView,
    val primaryActions: View,
    val routeHeader: View,
    val routes: View,
    val episodeHeader: View,
    val episodes: View,
    val selectedResource: View,
    val episodeOrder: View,
    val loading: View,
    val supplemental: View,
    val backToTop: View,
    val sourceInfo: TextView? = null,
    val configureActions: ((DetailActionPresentation) -> Unit)? = null,
    val configureEpisodes: ((EpisodeRailPresentation) -> Unit)? = null,
)

data class HomeThemeState(
    val hasHistory: Boolean,
    val loading: Boolean,
    val historyExpanded: Boolean,
    val hasBackdrop: Boolean,
    val hasPoster: Boolean,
    val hasLibrary: Boolean = false,
    val posterColumns: Int = 6,
)

data class DetailThemeState(
    val fromLibrary: Boolean,
    val hasBackdrop: Boolean,
    val hasPoster: Boolean,
    val episodeRegionFocused: Boolean,
    val routesLoading: Boolean,
)

interface ThemeLayoutBinding<State> {
    fun update(state: State)
    fun dispose()
}

interface NativeThemeLayout {
    val id: NativeThemeId
    val palette: NativeThemePalette
    fun installHome(slots: HomeThemeSlots): ThemeLayoutBinding<HomeThemeState>
    fun installDetail(slots: DetailThemeSlots): ThemeLayoutBinding<DetailThemeState>
}
