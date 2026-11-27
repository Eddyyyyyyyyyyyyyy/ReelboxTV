package com.reelix.app.player

import android.content.Context
import android.view.View
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException

/** Keep the control panel available after a native loader error so the user can select another engine. */
class UnavailablePlaybackEngine(override val kind: EngineKind, private val failure: Throwable) : PlaybackEngine {
    private val listeners = linkedSetOf<PlaybackEngine.Listener>()
    override val currentPosition = 0L
    override val duration = 0L
    override val bufferedPosition = 0L
    override val isPlaying = false
    override val playbackState = Player.STATE_IDLE
    override var playWhenReady = false
    override val audioTracks = emptyList<AudioChoice>()
    override val audioAutomatic = true
    override val subtitleTracks = emptyList<SubtitleChoice>()
    override val videoResolution: VideoResolution? = null
    override val videoBitrate: Int? = null
    override val networkMeter = NetworkSpeedMeter(0).apply { begin(0, observable = false) }
    override fun prepare(url: String, headers: Map<String, String>, resume: PlaybackResume?) {
        listeners.toList().forEach { it.onPlayerError(PlaybackException("$kind: ${failure.javaClass.simpleName}",
            failure, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)) }
    }
    override fun play() = Unit
    override fun pause() = Unit
    override fun seekTo(positionMs: Long) = Unit
    override fun setPlaybackSpeed(speed: Float) = Unit
    override fun selectAudio(key: String?) = Unit
    override fun selectSubtitle(key: String?) = Unit
    override fun createView(context: Context): View = View(context)
    override fun updateView(view: View, scale: String, controlsVisible: Boolean) = Unit
    override fun addListener(listener: PlaybackEngine.Listener) { listeners += listener }
    override fun removeListener(listener: PlaybackEngine.Listener) { listeners -= listener }
    override fun release() { listeners.clear() }
}
