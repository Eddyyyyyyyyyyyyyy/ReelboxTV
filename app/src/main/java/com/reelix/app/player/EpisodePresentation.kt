package com.reelix.app.player

import com.reelix.app.data.model.Episode

fun episodeKey(episode: Episode): String = "${episode.flag}|${episode.playId}|${episode.index}"
fun episodePresentation(episodes: List<Episode>, reversed: Boolean): List<Episode> =
    if (reversed) episodes.asReversed() else episodes
fun episodePosition(episodes: List<Episode>, key: String?, fallback: Int): Int =
    episodes.indexOfFirst { episodeKey(it) == key }.takeIf { it >= 0 }
        ?: fallback.coerceIn(0, episodes.lastIndex.coerceAtLeast(0))

/** Shared only for this process/session; no global sort preference or change to playback order. */
object EpisodeOrderSession {
    private val orders = mutableMapOf<String, Boolean>()
    fun reversed(workId: String): Boolean = orders[workId] == true
    fun set(workId: String, reversed: Boolean) { orders[workId] = reversed }
}

/**
 * Remembers which episode card opened the player, so BACK can put focus there.
 *
 * 只对"从选集卡进播放器再返回"生效：播放页销毁后详情页会重建，实例字段留不住，
 * 所以按作品 id 记在进程内；`take` 是一次性的，下次重新进详情页仍回到「继续观看」。
 */
object EpisodeFocusSession {
    private val lastPlayed = mutableMapOf<String, String>()

    @Synchronized
    fun remember(workId: String, key: String) {
        lastPlayed[workId] = key
    }

    @Synchronized
    fun forget(workId: String) {
        lastPlayed.remove(workId)
    }

    @Synchronized
    fun last(workId: String): String? = lastPlayed[workId]
}
