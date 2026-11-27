package com.reelix.app.data.alist

enum class LibraryLoadAction { RESTORE, SCAN, IDLE }

fun libraryLoadAction(hasCache: Boolean, autoScan: Boolean, force: Boolean): LibraryLoadAction =
    when {
        force -> LibraryLoadAction.SCAN
        hasCache -> LibraryLoadAction.RESTORE
        !autoScan -> LibraryLoadAction.IDLE
        else -> LibraryLoadAction.SCAN
    }
