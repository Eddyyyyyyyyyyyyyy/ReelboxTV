package com.reelix.app.player

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.view.View
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout

@UnstableApi
class VlcPlaybackEngine(context: Context, private val mode: DecoderMode) : PlaybackEngine {
    private val lib = LibVLC(context.applicationContext, arrayListOf("--no-video-title-show", "--network-caching=2500"))
    private val vlc = MediaPlayer(lib)
    private val listeners = linkedSetOf<PlaybackEngine.Listener>()
    private var released = false
    private var media: Media? = null
    private var state = Player.STATE_IDLE
    private var desiredPlay = true
    private var autoAudio = true
    private var pendingAudio: AudioPreference? = null
    private var pendingPosition: Long? = null
    private var speed = 1f
    private var initialAudioId: Int? = null
    private var appliedScale: String? = null
    private var logicalEnd = false
    private var hlsProxy: HlsStreamProxy? = null
    override val kind = EngineKind.VLC
    override var networkMeter = NetworkSpeedMeter(SystemClock.elapsedRealtime())
        private set
    override val currentPosition get() = if (released) 0L else if (logicalEnd) duration else vlc.time.coerceAtLeast(0)
    override val duration get() = if (released) 0L else vlc.length.coerceAtLeast(0)
    // libVLC's buffering percentage describes its start threshold, not the buffered video timeline.
    override val bufferedPosition get() = currentPosition
    override val isPlaying get() = !released && vlc.isPlaying
    override val playbackState get() = state
    override var playWhenReady: Boolean
        get() = desiredPlay
        set(value) { desiredPlay = value; if (value) play() else pause() }
    override val audioAutomatic get() = autoAudio
    override val subtitleTracks: List<SubtitleChoice> get() {
        val descriptions = vlc.spuTracks.orEmpty().filter { it.id >= 0 }
        if (descriptions.isEmpty()) return listOf(SubtitleChoice("off", "关闭字幕", true))
        return listOf(SubtitleChoice("off", "关闭字幕", vlc.spuTrack < 0)) + descriptions.map { description ->
            val label = listOfNotNull(description.name)
                .distinct().joinToString(" · ").ifBlank { "字幕轨 ${description.id}" }
            SubtitleChoice("vlc-spu:${description.id}", label, description.id == vlc.spuTrack)
        }
    }
    override val audioTracks: List<AudioChoice> get() = if (released) emptyList() else
        vlc.audioTracks.orEmpty().filter { it.id >= 0 }.map { description ->
            val info = media?.let { source -> (0 until source.trackCount).mapNotNull { source.getTrack(it) }
                .filterIsInstance<org.videolan.libvlc.interfaces.IMedia.AudioTrack>().firstOrNull { it.id == description.id } }
            AudioChoice("vlc:${description.id}", description.name.orEmpty(), info?.language.orEmpty(),
                info?.channels ?: 0, description.id == vlc.audioTrack)
        }
    override val videoResolution: VideoResolution?
        get() {
            if (released) return null
            val source = media ?: return null
            val tracks = (0 until source.trackCount).mapNotNull { source.getTrack(it) }
                .filterIsInstance<IMedia.VideoTrack>()
            val track = tracks.firstOrNull { it.id == vlc.videoTrack } ?: tracks.firstOrNull() ?: return null
            val ratio = if (track.sarNum > 0 && track.sarDen > 0) {
                track.sarNum.toFloat() / track.sarDen
            } else 1f
            return videoResolutionOf(track.width, track.height, ratio, track.orientation)
        }
    override val videoBitrate: Int?
        get() {
            if (released) return null
            val source = media ?: return null
            val tracks = (0 until source.trackCount).mapNotNull { source.getTrack(it) }
                .filterIsInstance<IMedia.VideoTrack>()
            val track = tracks.firstOrNull { it.id == vlc.videoTrack } ?: tracks.firstOrNull() ?: return null
            return track.bitrate.takeIf { it > 0 }
        }

    init {
        vlc.setEventListener { event ->
            if (!released) when (event.type) {
                MediaPlayer.Event.Opening -> changeState(Player.STATE_BUFFERING)
                MediaPlayer.Event.Buffering -> {
                    if (event.buffering < 100) changeState(Player.STATE_BUFFERING)
                    else if (state != Player.STATE_ENDED) changeState(Player.STATE_READY)
                }
                MediaPlayer.Event.Playing -> {
                    changeState(Player.STATE_READY)
                    pendingPosition?.let { target ->
                        if (vlc.isSeekable) { pendingPosition = null; vlc.setTime(target, false) }
                    }
                    vlc.setRate(speed)
                    if (initialAudioId == null && vlc.audioTrack >= 0) initialAudioId = vlc.audioTrack
                    pendingAudio?.let { preference ->
                        if (audioTracks.isNotEmpty()) {
                            pendingAudio = null
                            matchAudioPreference(audioTracks, preference)?.let(::selectAudio)
                        }
                    }
                    if (!desiredPlay) vlc.pause()
                    listeners.toList().forEach {
                        it.onIsPlayingChanged(desiredPlay)
                        it.onAudioChanged()
                        it.onSubtitlesChanged()
                        it.onVideoSizeChanged()
                    }
                }
                MediaPlayer.Event.Vout -> listeners.toList().forEach { it.onVideoSizeChanged() }
                MediaPlayer.Event.SeekableChanged -> {
                    if (vlc.isSeekable) pendingPosition?.let { pendingPosition = null; vlc.setTime(it, false) }
                }
                MediaPlayer.Event.Paused -> listeners.toList().forEach { it.onIsPlayingChanged(false) }
                MediaPlayer.Event.EndReached -> {
                    listeners.toList().forEach { it.onIsPlayingChanged(false) }
                    changeState(Player.STATE_ENDED)
                }
                MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESSelected -> {
                    pendingAudio?.let { preference ->
                        matchAudioPreference(audioTracks, preference)?.let { key -> pendingAudio = null; selectAudio(key) }
                    }
                    listeners.toList().forEach {
                        it.onAudioChanged()
                        it.onSubtitlesChanged()
                        it.onVideoSizeChanged()
                    }
                }
                MediaPlayer.Event.EncounteredError -> {
                    changeState(Player.STATE_IDLE)
                    listeners.toList().forEach {
                        it.onIsPlayingChanged(false)
                        it.onPlayerError(PlaybackException(context.getString(com.reelix.app.R.string.player_vlc_failed),
                            null, PlaybackException.ERROR_CODE_UNSPECIFIED))
                    }
                }
            }
        }
        android.util.Log.i("ReelixDecoder", "engine=VLC version=${LibVLC.version()} mode=$mode")
    }

    private fun changeState(next: Int) {
        if (state == next) return
        state = next
        listeners.toList().forEach { it.onPlaybackStateChanged(next) }
    }

    override fun prepare(url: String, headers: Map<String, String>, resume: PlaybackResume?) {
        vlc.stop()
        hlsProxy?.close()
        hlsProxy = null
        media?.release()
        media = null
        initialAudioId = null
        logicalEnd = false
        autoAudio = true
        pendingAudio = resume?.audio
        pendingPosition = resume?.positionMs?.takeIf { it > 0 }
        desiredPlay = resume?.playWhenReady ?: true
        val remote = url.startsWith("http", true)
        val playbackUrl = if (remote && PlayerFactory.isHlsUrl(url)) {
            var created: HlsStreamProxy? = null
            runCatching {
                created = HlsStreamProxy(headers)
                created!!.url(url)
            }.onFailure { created?.close(); created = null }.getOrElse { url }.also { hlsProxy = created }
        } else url
        val playbackHeaders = headers
        networkMeter = NetworkSpeedMeter(SystemClock.elapsedRealtime()).apply {
            begin(SystemClock.elapsedRealtime(), observable = remote, local = !remote)
        }
        // No relay: TrafficStats measures the app process, so arbitrary headers are no longer worth a local proxy.
        val source = Media(lib, Uri.parse(playbackUrl)).apply {
            setHWDecoderEnabled(mode != DecoderMode.SOFTWARE, mode == DecoderMode.HARDWARE)
            playbackHeaders.forEach { (name, value) ->
                when (name.lowercase()) {
                    "user-agent" -> addOption(":http-user-agent=$value")
                    "referer", "referrer" -> addOption(":http-referrer=$value")
                }
            }
        }
        media = source
        vlc.media = source
        changeState(Player.STATE_BUFFERING)
        // VLC needs to start its demuxer before it can seek; Playing applies pause/resume preferences.
        vlc.play()
    }

    override fun play() { if (!released) { desiredPlay = true; vlc.play() } }
    override fun pause() { if (!released) { desiredPlay = false; vlc.pause() } }
    override fun seekTo(positionMs: Long) {
        if (released) return
        val target = positionMs.coerceAtLeast(0)
        if (duration > 0 && target >= duration) {
            // Native demuxers may clamp a seek at duration before EOF. Make outro completion deterministic.
            vlc.pause()
            logicalEnd = true
            listeners.toList().forEach { it.onIsPlayingChanged(false) }
            changeState(Player.STATE_ENDED)
        } else {
            logicalEnd = false
            if (state == Player.STATE_ENDED) changeState(Player.STATE_READY)
            if (vlc.isSeekable) vlc.setTime(target, false) else pendingPosition = target
        }
    }
    override fun setPlaybackSpeed(speed: Float) { this.speed = speed; if (!released) vlc.setRate(speed) }
    override fun selectAudio(key: String?) {
        if (released) return
        autoAudio = key == null
        val id = key?.removePrefix("vlc:")?.toIntOrNull() ?: initialAudioId ?: vlc.audioTracks.orEmpty().firstOrNull { it.id >= 0 }?.id
        if (id != null) vlc.audioTrack = id
        listeners.toList().forEach { it.onAudioChanged() }
    }
    override fun selectSubtitle(key: String?) {
        if (released) return
        vlc.spuTrack = if (key == null || key == "off") {
            -1
        } else {
            key.removePrefix("vlc-spu:").toIntOrNull() ?: -1
        }
        listeners.toList().forEach { it.onSubtitlesChanged() }
    }
    override fun createView(context: Context): View = VLCVideoLayout(context).apply {
        keepScreenOn = true
        setBackgroundColor(android.graphics.Color.BLACK)
        vlc.attachViews(this, null, true, false)
        appliedScale = null
    }
    override fun updateView(view: View, scale: String, controlsVisible: Boolean) {
        if (released || appliedScale == scale) return
        appliedScale = scale
        vlc.setVideoScale(when (scale) {
            "FILL" -> MediaPlayer.ScaleType.SURFACE_FILL
            "CROP" -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
            "ORIGINAL" -> MediaPlayer.ScaleType.SURFACE_ORIGINAL
            else -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
        })
    }
    override fun addListener(listener: PlaybackEngine.Listener) { listeners += listener }
    override fun removeListener(listener: PlaybackEngine.Listener) { listeners -= listener }
    override fun release() {
        if (released) return
        released = true
        listeners.clear()
        vlc.setEventListener(null)
        vlc.stop()
        vlc.detachViews()
        vlc.release()
        media?.release()
        media = null
        hlsProxy?.close()
        hlsProxy = null
        lib.release()
    }
}
