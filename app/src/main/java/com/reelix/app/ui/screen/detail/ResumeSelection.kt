package com.reelix.app.ui.screen.detail

import com.reelix.app.data.model.PlayRoute

internal fun restoredRouteIndex(routes: List<PlayRoute>, routeName: String?): Int {
    if (routeName.isNullOrBlank()) return 0
    return routes.indexOfFirst { it.name == routeName }.takeIf { it >= 0 } ?: 0
}

internal fun selectedLibrarySectionId(openedIds: List<String>, availableIds: List<String>, choice: Int): String? =
    openedIds.getOrNull(choice - 1)?.takeIf { it in availableIds }

internal enum class SectionPopupReturnTarget { ANCHOR, ARTWORK, PLAY, NONE }

internal fun sectionPopupReturnTarget(
    samePage: Boolean,
    anchorAvailable: Boolean,
    artworkAvailable: Boolean,
    playAvailable: Boolean,
): SectionPopupReturnTarget = when {
    !samePage -> SectionPopupReturnTarget.NONE
    anchorAvailable -> SectionPopupReturnTarget.ANCHOR
    artworkAvailable -> SectionPopupReturnTarget.ARTWORK
    playAvailable -> SectionPopupReturnTarget.PLAY
    else -> SectionPopupReturnTarget.NONE
}
