package com.reelix.app.player

import android.content.Context
import android.os.SystemClock
import android.view.View
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout

@UnstableApi
class ExoPlaybackEngine(private val context: Context, mode: DecoderMode) : PlaybackEngine {
    private val exo = PlayerFactory.create(context, mode)
    private val listeners = linkedSetOf<PlaybackEngine.Listener>()
    private var released = false
    private var restoreAudio: AudioPreference? = null
    private var hlsProxy: HlsStreamProxy? = null
    override val kind = EngineKind.EXO
    override var networkMeter = NetworkSpeedMeter(SystemClock.elapsedRealtime())
        private set
    override val currentPosition get() = if (released) 0L else exo.currentPosition
    override val duration get() = if (released) 0L else exo.duration
    override val bufferedPosition get() = if (released) 0L else exo.bufferedPosition
    override val isPlaying get() = !released && exo.isPlaying
    override val playbackState get() = if (released) Player.STATE_IDLE else exo.playbackState
    override var playWhenReady: Boolean
        get() = !released && exo.playWhenReady
        set(value) { if (!released) exo.playWhenReady = value }
    override val audioAutomatic get() = exo.trackSelectionParameters.overrides.values.none { it.type == C.TRACK_TYPE_AUDIO }
    override val subtitleTracks: List<SubtitleChoice> get() {
        val groups = exo.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        val disabled = exo.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        return listOf(SubtitleChoice("off", "关闭字幕", disabled)) +
            groups.flatMapIndexed { groupIndex, group ->
                (0 until group.length).filter { group.isTrackSupported(it) }.map { index ->
                    val format = group.getTrackFormat(index)
                    val label = listOfNotNull(format.label, format.language?.takeIf { it.isNotBlank() })
                        .distinct().joinToString(" · ").ifBlank { "字幕轨" }
                    SubtitleChoice("exo-text:$groupIndex:$index", label,
                        !disabled && group.isTrackSelected(index))
                }
            }
    }
    override val audioTracks: List<AudioChoice> get() = exo.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        .flatMapIndexed { groupIndex, group ->
            (0 until group.length).filter { group.isTrackSupported(it) }.map { index ->
                val f = group.getTrackFormat(index)
                AudioChoice("exo:$groupIndex:$index", f.label.orEmpty(), f.language.orEmpty(), f.channelCount,
                    group.isTrackSelected(index))
            }
        }
    override val videoResolution: VideoResolution?
        get() = if (released) null else exo.videoSize.let {
            videoResolutionOf(it.width, it.height, it.pixelWidthHeightRatio, it.unappliedRotationDegrees)
        }
    override val videoBitrate: Int?
        get() {
            if (released) return null
            val group = exo.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO } ?: return null
            val index = (0 until group.length).firstOrNull { group.isTrackSelected(it) }
                ?: (0 until group.length).firstOrNull { group.isTrackSupported(it) }
                ?: return null
            return group.getTrackFormat(index).bitrate.takeIf { it > 0 }
        }

    init {
        exo.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                restoreAudio?.takeIf { audioTracks.isNotEmpty() }?.let { preference ->
                    restoreAudio = null
                    matchAudioPreference(audioTracks, preference)?.let(::selectAudio)
                }
                listeners.toList().forEach { it.onAudioChanged() }
                listeners.toList().forEach { it.onSubtitlesChanged() }
            }
            override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {
                listeners.toList().forEach { it.onAudioChanged() }
                listeners.toList().forEach { it.onSubtitlesChanged() }
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                listeners.toList().forEach { it.onVideoSizeChanged() }
            }
            override fun onIsPlayingChanged(playing: Boolean) { listeners.toList().forEach { it.onIsPlayingChanged(playing) } }
            override fun onPlaybackStateChanged(state: Int) { listeners.toList().forEach { it.onPlaybackStateChanged(state) } }
            override fun onPlayerError(error: PlaybackException) { listeners.toList().forEach { it.onPlayerError(error) } }
        })
        exo.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(time: AnalyticsListener.EventTime, name: String, at: Long, elapsed: Long) {
                android.util.Log.i("ReelixDecoder", "mode=$mode video=$name")
            }
            override fun onAudioDecoderInitialized(time: AnalyticsListener.EventTime, name: String, at: Long, elapsed: Long) {
                android.util.Log.i("ReelixDecoder", "audio=$name")
            }
        })
    }

    override fun prepare(url: String, headers: Map<String, String>, resume: PlaybackResume?) {
        exo.stop()
        hlsProxy?.close()
        hlsProxy = null
        val playbackUrl = if (url.startsWith("http", true) && PlayerFactory.isHlsUrl(url)) {
            var created: HlsStreamProxy? = null
            runCatching {
                created = HlsStreamProxy(headers)
                created!!.url(url)
            }.onFailure { created?.close(); created = null }.getOrElse { url }.also { hlsProxy = created }
        } else url
        // Keep headers on the local request too, so an unsupported playlist's 307 direct fallback
        // retains the original authentication headers when Exo follows it.
        val playbackHeaders = headers
        networkMeter = NetworkSpeedMeter(SystemClock.elapsedRealtime()).apply {
            // 回环地址（插件自带的本地代理）同样在应用进程内下载，TrafficStats 能统计到，
            // 所以只要地址是 http(s) 就测速，只有 file/content 才算本地播放。
            begin(SystemClock.elapsedRealtime(), observable = url.startsWith("http", true),
                local = !url.startsWith("http", true))
        }
        restoreAudio = resume?.audio
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_AUDIO).build()
        exo.setMediaSource(PlayerFactory.mediaSource(context, playbackUrl, playbackHeaders), resume?.positionMs ?: 0)
        exo.prepare()
        exo.playWhenReady = resume?.playWhenReady ?: true
    }
    override fun selectAudio(key: String?) {
        val builder = exo.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
        if (key != null) {
            exo.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.forEachIndexed { gi, group ->
                (0 until group.length).firstOrNull { "exo:$gi:$it" == key }?.let {
                    builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, it))
                }
            }
        }
        exo.trackSelectionParameters = builder.build()
    }
    override fun selectSubtitle(key: String?) {
        val builder = exo.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, key == null)
        if (key != null && key != "off") {
            exo.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.forEachIndexed { gi, group ->
                (0 until group.length).firstOrNull { "exo-text:$gi:$it" == key }?.let {
                    builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, it))
                }
            }
        }
        exo.trackSelectionParameters = builder.build()
    }
    override fun play() { if (!released) exo.play() }
    override fun pause() { if (!released) exo.pause() }
    override fun seekTo(positionMs: Long) { if (!released) exo.seekTo(positionMs.coerceAtLeast(0)) }
    override fun setPlaybackSpeed(speed: Float) { if (!released) exo.setPlaybackSpeed(speed) }
    override fun createView(context: Context): View = PlayerView(context).apply {
        useController = false
        player = exo
        keepScreenOn = true
        setBackgroundColor(android.graphics.Color.BLACK)
    }
    override fun updateView(view: View, scale: String, controlsVisible: Boolean) {
        (view as PlayerView).resizeMode = when (scale) {
            "FILL" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            "CROP" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            "ORIGINAL" -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        view.subtitleView?.setBottomPaddingFraction(if (controlsVisible) 0.22f else 0.08f)
    }
    override fun addListener(listener: PlaybackEngine.Listener) { listeners += listener }
    override fun removeListener(listener: PlaybackEngine.Listener) { listeners -= listener }
    override fun release() {
        if (!released) {
            released = true
            listeners.clear()
            exo.release()
            hlsProxy?.close()
            hlsProxy = null
        }
    }
}

internal fun isLoopbackUrl(url: String): Boolean = runCatching {
    val host = java.net.URI(url).host?.lowercase().orEmpty()
    host == "localhost" || host == "::1" || host == "[::1]" || host.startsWith("127.")
}.getOrDefault(false)
