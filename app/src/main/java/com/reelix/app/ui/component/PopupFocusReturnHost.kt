package com.reelix.app.ui.component

import android.view.View

/** A page can identify nearby controls that a generic dialog cannot safely infer. */
interface PopupFocusReturnHost {
    fun popupFocusReturnTarget(origin: View): (() -> Boolean)?
}
