package com.reelix.app.player

import android.content.Context
import android.view.View
import androidx.media3.common.PlaybackException
import kotlin.math.roundToInt

/** SYSTEM is an automatic last resort and is not offered as a normal engine choice. */
enum class EngineKind { EXO, VLC, SYSTEM }

internal fun nextFallbackEngine(current: EngineKind, attempted: Set<EngineKind>): EngineKind? {
    val candidates = when (current) {
        EngineKind.EXO -> listOf(EngineKind.VLC, EngineKind.SYSTEM)
        EngineKind.VLC -> listOf(EngineKind.EXO, EngineKind.SYSTEM)
        EngineKind.SYSTEM -> emptyList()
    }
    return candidates.firstOrNull { it !in attempted }
}

data class VideoResolution(val width: Int, val height: Int) {
    val label: String get() = "${width}×${height}"
}

data class AudioChoice(val key: String, val label: String, val language: String = "",
    val channels: Int = 0, val selected: Boolean = false)
data class AudioPreference(val key: String, val label: String, val language: String, val channels: Int)
data class PlaybackResume(val positionMs: Long, val playWhenReady: Boolean, val audio: AudioPreference?)

/** Keep the last usable progress when a fallback engine errors before it can report a position. */
internal fun mergeFallbackResume(previous: PlaybackResume?, current: PlaybackResume?): PlaybackResume? {
    current ?: return previous
    previous ?: return current
    return current.copy(
        positionMs = if (current.positionMs > 0L || previous.positionMs <= 0L)
            current.positionMs else previous.positionMs,
        audio = current.audio ?: previous.audio,
    )
}

/** Common controls own episode/skip/progress logic; native engines own only playback and surfaces. */
interface PlaybackEngine {
    val kind: EngineKind
    val currentPosition: Long
    val duration: Long
    val bufferedPosition: Long
    val isPlaying: Boolean
    val playbackState: Int
    var playWhenReady: Boolean
    val audioTracks: List<AudioChoice>
    val audioAutomatic: Boolean
    val subtitleTracks: List<SubtitleChoice>
    val videoResolution: VideoResolution?
    /** Selected video track bitrate in bits/s; null when the container does not declare one. */
    val videoBitrate: Int?
    val networkMeter: NetworkSpeedMeter
    fun prepare(url: String, headers: Map<String, String>, resume: PlaybackResume? = null)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setPlaybackSpeed(speed: Float)
    fun selectAudio(key: String?)
    fun selectSubtitle(key: String?)
    fun createView(context: Context): View
    fun updateView(view: View, scale: String, controlsVisible: Boolean)
    fun addListener(listener: Listener)
    fun removeListener(listener: Listener)
    fun release()

    fun snapshot() = PlaybackResume(currentPosition.coerceAtLeast(0), playWhenReady,
        if (audioAutomatic) null else audioTracks.firstOrNull { it.selected }?.let {
            AudioPreference(it.key, it.label, it.language, it.channels)
        })

    interface Listener {
        fun onAudioChanged() {}
        fun onSubtitlesChanged() {}
        fun onIsPlayingChanged(playing: Boolean) {}
        fun onPlaybackStateChanged(state: Int) {}
        fun onPlayerError(error: PlaybackException) {}
        fun onVideoSizeChanged() {}
    }
}

/** Convert coded dimensions and sample aspect ratio into the displayed picture size. */
fun videoResolutionOf(
    width: Int,
    height: Int,
    pixelWidthHeightRatio: Float = 1f,
    unappliedRotationDegrees: Int = 0,
): VideoResolution? {
    if (width <= 0 || height <= 0) return null
    val ratio = pixelWidthHeightRatio.takeIf { it.isFinite() && it > 0f } ?: 1f
    val displayWidth = (width * ratio).roundToInt().coerceAtLeast(1)
    val rotation = ((unappliedRotationDegrees % 360) + 360) % 360
    return if (rotation % 180 != 0) VideoResolution(height, displayWidth)
    else VideoResolution(displayWidth, height)
}

fun matchAudioPreference(tracks: List<AudioChoice>, preference: AudioPreference?): String? {
    if (preference == null) return null
    return tracks.firstOrNull { it.key == preference.key && it.language == preference.language && it.label == preference.label }?.key
        ?: tracks.firstOrNull { preference.language.isNotBlank() &&
            audioLanguage(it.language) == audioLanguage(preference.language) && it.channels == preference.channels }?.key
        ?: tracks.firstOrNull { preference.label.isNotBlank() && it.label == preference.label }?.key
}

private fun audioLanguage(value: String): String = when (val code = value.lowercase().substringBefore('-').substringBefore('_')) {
    "chi", "zho", "cmn" -> "zh"
    "eng" -> "en"
    "jpn" -> "ja"
    "kor" -> "ko"
    "fre", "fra" -> "fr"
    "ger", "deu" -> "de"
    "spa" -> "es"
    "rus" -> "ru"
    else -> code
}
