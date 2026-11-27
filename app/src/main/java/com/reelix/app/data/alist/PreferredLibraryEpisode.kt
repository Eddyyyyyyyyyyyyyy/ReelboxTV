package com.reelix.app.data.alist

import com.reelix.app.data.model.Episode

fun preferredLibraryEpisode(episodes: List<Episode>, searchPath: String?, savedPath: String?, watchedPath: String?): Episode? =
    listOfNotNull(searchPath, savedPath, watchedPath).firstNotNullOfOrNull { path -> episodes.firstOrNull { it.playId == path } }
