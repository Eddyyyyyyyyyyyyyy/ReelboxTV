package com.reelix.app.player

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import java.util.concurrent.CopyOnWriteArraySet

/** Android framework MediaPlayer, used only after ExoPlayer and VLC both fail. */
internal class SystemMediaPlayerEngine(private val context: Context) : PlaybackEngine {
    private val mediaPlayer = android.media.MediaPlayer()
    private val listeners = CopyOnWriteArraySet<PlaybackEngine.Listener>()
    private var released = false
    private var prepared = false
    private var desiredPlay = true
    private var state = Player.STATE_IDLE
    private var positionAfterPrepare = 0L
    private var speed = 1f
    private var bufferingPercent = 0
    private var resolution: VideoResolution? = null
    private var view: AspectSurfaceView? = null
    private var hlsProxy: HlsStreamProxy? = null
    override val kind = EngineKind.SYSTEM
    override var networkMeter = NetworkSpeedMeter(0)
        private set
    override val currentPosition: Long get() = if (released || !prepared) positionAfterPrepare else
        runCatching { mediaPlayer.currentPosition.toLong() }.getOrDefault(positionAfterPrepare).coerceAtLeast(0)
    override val duration: Long get() = if (released || !prepared) 0L else
        runCatching { mediaPlayer.duration.toLong() }.getOrDefault(0L).coerceAtLeast(0)
    override val bufferedPosition: Long get() = duration * bufferingPercent / 100
    override val isPlaying: Boolean get() = !released && prepared && runCatching { mediaPlayer.isPlaying }.getOrDefault(false)
    override val playbackState: Int get() = state
    override var playWhenReady: Boolean
        get() = desiredPlay
        set(value) {
            desiredPlay = value
            if (prepared && !released) runCatching {
                if (value && !mediaPlayer.isPlaying) mediaPlayer.start()
                else if (!value && mediaPlayer.isPlaying) mediaPlayer.pause()
            }
        }
    override val audioTracks: List<AudioChoice> get() = emptyList()
    override val audioAutomatic: Boolean get() = true
    override val subtitleTracks: List<SubtitleChoice> get() = emptyList()
    override val videoResolution: VideoResolution? get() = resolution
    override val videoBitrate: Int? get() = null

    init {
        mediaPlayer.setOnPreparedListener {
            if (released) return@setOnPreparedListener
            prepared = true
            bufferingPercent = 0
            if (positionAfterPrepare > 0) {
                runCatching { mediaPlayer.seekTo(positionAfterPrepare.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) }
            }
            applySpeed()
            changeState(Player.STATE_READY)
            if (desiredPlay) runCatching { mediaPlayer.start() }
            listeners.forEach { it.onIsPlayingChanged(isPlaying) }
        }
        mediaPlayer.setOnCompletionListener {
            if (released) return@setOnCompletionListener
            positionAfterPrepare = duration
            listeners.forEach { it.onIsPlayingChanged(false) }
            changeState(Player.STATE_ENDED)
        }
        mediaPlayer.setOnBufferingUpdateListener { _, percent -> bufferingPercent = percent.coerceIn(0, 100) }
        mediaPlayer.setOnVideoSizeChangedListener { _, width, height ->
            resolution = videoResolutionOf(width, height)
            view?.requestLayout()
            listeners.forEach { it.onVideoSizeChanged() }
        }
        mediaPlayer.setOnInfoListener { _, what, _ ->
            when (what) {
                android.media.MediaPlayer.MEDIA_INFO_BUFFERING_START -> changeState(Player.STATE_BUFFERING)
                android.media.MediaPlayer.MEDIA_INFO_BUFFERING_END -> changeState(Player.STATE_READY)
            }
            true
        }
        mediaPlayer.setOnErrorListener { _, what, extra ->
            if (!released) {
                prepared = false
                changeState(Player.STATE_IDLE)
                val failure = IllegalStateException("MediaPlayer error $what/$extra")
                listeners.forEach {
                    it.onIsPlayingChanged(false)
                    it.onPlayerError(PlaybackException(
                        "Android MediaPlayer: $what/$extra", failure,
                        PlaybackException.ERROR_CODE_UNSPECIFIED,
                    ))
                }
            }
            true
        }
    }

    private fun changeState(next: Int) {
        if (state == next) return
        state = next
        listeners.forEach { it.onPlaybackStateChanged(next) }
    }

    override fun prepare(url: String, headers: Map<String, String>, resume: PlaybackResume?) {
        check(!released) { "Android MediaPlayer is released" }
        runCatching { mediaPlayer.reset() }
        hlsProxy?.close()
        hlsProxy = null
        prepared = false
        desiredPlay = resume?.playWhenReady ?: true
        positionAfterPrepare = resume?.positionMs?.coerceAtLeast(0) ?: 0L
        bufferingPercent = 0
        resolution = null
        networkMeter = NetworkSpeedMeter(SystemClock.elapsedRealtime()).apply {
            begin(SystemClock.elapsedRealtime(), observable = url.startsWith("http", true), local = !url.startsWith("http", true))
        }
        val playbackUrl = if (url.startsWith("http", true) && PlayerFactory.isHlsUrl(url)) {
            var created: HlsStreamProxy? = null
            runCatching {
                created = HlsStreamProxy(headers)
                created!!.url(url)
            }.onFailure { created?.close(); created = null }.getOrElse { url }.also { hlsProxy = created }
        } else url
        val playbackHeaders = headers
        runCatching {
            mediaPlayer.setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
            )
            mediaPlayer.setDataSource(context, Uri.parse(playbackUrl), playbackHeaders)
            // Fallback can swap engines before the new SurfaceView is created. Passing its
            // not-yet-valid SurfaceHolder makes MediaPlayer throw "surface has been released".
            view?.holder?.takeIf { it.surface.isValid }?.let(mediaPlayer::setDisplay)
            changeState(Player.STATE_BUFFERING)
            mediaPlayer.prepareAsync()
        }.onFailure { failure ->
            changeState(Player.STATE_IDLE)
            listeners.forEach {
                it.onPlayerError(PlaybackException("Android MediaPlayer: ${failure.message.orEmpty()}", failure,
                    PlaybackException.ERROR_CODE_UNSPECIFIED))
            }
        }
    }

    override fun play() { playWhenReady = true }
    override fun pause() { playWhenReady = false }
    override fun seekTo(positionMs: Long) {
        val target = positionMs.coerceAtLeast(0)
        if (!prepared || released) positionAfterPrepare = target
        else runCatching { mediaPlayer.seekTo(target.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) }
    }
    override fun setPlaybackSpeed(speed: Float) {
        this.speed = speed.coerceIn(0.25f, 4f)
        if (prepared && !released) applySpeed()
    }
    private fun applySpeed() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching { mediaPlayer.playbackParams = mediaPlayer.playbackParams.setSpeed(speed) }
        }
    }
    override fun selectAudio(key: String?) = Unit
    override fun selectSubtitle(key: String?) = Unit

    override fun createView(context: Context): View = FrameLayout(context).apply {
        setBackgroundColor(android.graphics.Color.BLACK)
        val surface = AspectSurfaceView(context)
        view = surface
        addView(surface, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (!released && holder.surface.isValid) runCatching { mediaPlayer.setDisplay(holder) }
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                if (!released) runCatching { mediaPlayer.setDisplay(null) }
            }
        })
    }

    override fun updateView(view: View, scale: String, controlsVisible: Boolean) {
        (view as? FrameLayout)?.let { frame ->
            (frame.getChildAt(0) as? AspectSurfaceView)?.scaleMode = scale
        }
    }
    override fun addListener(listener: PlaybackEngine.Listener) { listeners += listener }
    override fun removeListener(listener: PlaybackEngine.Listener) { listeners -= listener }
    override fun release() {
        if (released) return
        released = true
        listeners.clear()
        runCatching { mediaPlayer.setDisplay(null) }
        mediaPlayer.setOnPreparedListener(null)
        mediaPlayer.setOnCompletionListener(null)
        mediaPlayer.setOnBufferingUpdateListener(null)
        mediaPlayer.setOnVideoSizeChangedListener(null)
        mediaPlayer.setOnInfoListener(null)
        mediaPlayer.setOnErrorListener(null)
        runCatching { mediaPlayer.reset() }
        mediaPlayer.release()
        hlsProxy?.close()
        hlsProxy = null
        view = null
    }

    private inner class AspectSurfaceView(context: Context) : SurfaceView(context) {
        var scaleMode: String = "FIT"
            set(value) {
                field = value
                applyScale()
            }

        init {
            keepScreenOn = true
            setBackgroundColor(android.graphics.Color.BLACK)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            applyScale()
        }

        fun applyScale() {
            val w = width.toFloat()
            val h = height.toFloat()
            val video = resolution ?: return
            if (w <= 0f || h <= 0f) return
            val viewportRatio = w / h
            val videoRatio = video.width.toFloat() / video.height.coerceAtLeast(1)
            when (scaleMode) {
                "FILL" -> { scaleX = 1f; scaleY = 1f }
                "CROP" -> if (videoRatio > viewportRatio) {
                    scaleX = videoRatio / viewportRatio; scaleY = 1f
                } else {
                    scaleX = 1f; scaleY = viewportRatio / videoRatio
                }
                else -> if (videoRatio > viewportRatio) {
                    scaleX = 1f; scaleY = viewportRatio / videoRatio
                } else {
                    scaleX = videoRatio / viewportRatio; scaleY = 1f
                }
            }
        }
    }
}
