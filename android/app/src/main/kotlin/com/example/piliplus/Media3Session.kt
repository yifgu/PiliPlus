package com.example.piliplus

import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import java.io.File

@androidx.annotation.OptIn(UnstableApi::class)
internal class Media3Session(
    private val activity: Activity,
    private val request: Media3Request,
    private val onEvent: (String, Any?) -> Unit,
    private val onClosed: (Map<String, Any?>) -> Unit,
) {
    val view = LayoutInflater.from(ContextThemeWrapper(activity, R.style.Media3PlayerTheme))
        .inflate(R.layout.media3_player, null) as ViewGroup
    private var player: ExoPlayer? = null
    private val playerView = view.findViewById<PlayerView>(R.id.media3_player)
    private var controls: Media3Controls? = null
    private var positionMs = request.positionMs
    private var durationMs = 0L
    private var speed = request.speed
    private var completed = false
    private var returning = false
    private var playbackError: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val previousColorMode = if (Build.VERSION.SDK_INT >= 26) activity.window.colorMode else 0
    private val progress = object : Runnable {
        override fun run() {
            if (returning) return
            publishState()
            handler.postDelayed(this, 500)
        }
    }

    fun start() {
        if (Build.VERSION.SDK_INT < 24) {
            fail(activity.getString(R.string.media3_player_android_required))
            return
        }
        @Suppress("DEPRECATION")
        val hdrTypes = activity.windowManager.defaultDisplay.hdrCapabilities?.supportedHdrTypes
        if (hdrTypes?.contains(Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION) != true) {
            fail(activity.getString(R.string.media3_player_display_required))
            return
        }
        if (Build.VERSION.SDK_INT >= 26) activity.window.colorMode = ActivityInfo.COLOR_MODE_HDR
        try {
            controls = Media3Controls(activity, view, playerView, request, onEvent)
            if (MediaCodecUtil.getDecoderInfos(MimeTypes.VIDEO_DOLBY_VISION, false, false).isEmpty()) {
                fail(activity.getString(R.string.media3_player_codec_required))
                return
            }
            // Do not silently decode the Profile 5 base layer as ordinary HEVC.
            val selector = MediaCodecSelector { mimeType, secure, tunneling ->
                if (MimeTypes.isVideo(mimeType) && mimeType != MimeTypes.VIDEO_DOLBY_VISION) {
                    emptyList()
                } else {
                    MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)
                }
            }
            val renderers = DefaultRenderersFactory(activity).setMediaCodecSelector(selector)
            val exoPlayer = ExoPlayer.Builder(activity, renderers).build()
            player = exoPlayer
            exoPlayer.setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            exoPlayer.setHandleAudioBecomingNoisy(true)
            exoPlayer.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    fail("${error.errorCodeName}: ${error.message}", error)
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    playerView.keepScreenOn = isPlaying
                }

                override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                    controls?.updateSpeed()
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (!playWhenReady) controls?.endSpeedBoost()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        completed = true
                        close()
                    }
                }

                override fun onEvents(player: Player, events: Player.Events) {
                    publishState()
                }

                override fun onTracksChanged(tracks: Tracks) {
                    for (group in tracks.groups) {
                        if (group.type != C.TRACK_TYPE_VIDEO) continue
                        for (index in 0 until group.length) {
                            if (!group.isTrackSelected(index)) continue
                            val format = group.getTrackFormat(index)
                            if (format.sampleMimeType != MimeTypes.VIDEO_DOLBY_VISION ||
                                format.codecs?.let { PROFILE_5.matches(it) } != true) {
                                fail(activity.getString(R.string.media3_player_wrong_profile))
                                return
                            }
                        }
                    }
                }
            })
            exoPlayer.addAnalyticsListener(object : AnalyticsListener {
                override fun onVideoDecoderInitialized(
                    eventTime: AnalyticsListener.EventTime,
                    decoderName: String,
                    initializedTimestampMs: Long,
                    initializationDurationMs: Long,
                ) {
                    controls?.setDecoderName(decoderName)
                }
            })
            val http = DefaultHttpDataSource.Factory().setDefaultRequestProperties(request.headers)
            val dataSources = DefaultDataSource.Factory(activity, http)
            val sources = ProgressiveMediaSource.Factory(dataSources)
            fun mediaItem(source: String): MediaItem {
                val uri = if (source.startsWith('/')) Uri.fromFile(File(source)) else Uri.parse(source)
                return MediaItem.fromUri(uri)
            }
            val videoSource = sources.createMediaSource(mediaItem(request.video))
            val audio = request.audio
            val source = if (audio.isNullOrEmpty()) videoSource else MergingMediaSource(
                true,
                videoSource,
                sources.createMediaSource(mediaItem(audio)),
            )
            playerView.player = exoPlayer
            exoPlayer.setMediaSource(source)
            exoPlayer.seekTo(positionMs)
            exoPlayer.setPlaybackSpeed(speed)
            controls?.updateSpeed()
            exoPlayer.playWhenReady = true
            exoPlayer.prepare()
            if (playerView.useController) playerView.showController()
            handler.post(progress)
        } catch (error: MediaCodecUtil.DecoderQueryException) {
            fail(activity.getString(R.string.media3_player_codec_required), error)
        } catch (error: IllegalArgumentException) {
            fail(error.message ?: "Invalid Media3 source", error)
        } catch (error: IllegalStateException) {
            fail(error.message ?: "Unable to initialize Media3", error)
        }
    }

    private fun capturePosition() {
        controls?.endSpeedBoost()
        player?.let {
            positionMs = it.currentPosition.coerceAtLeast(0)
            if (it.duration != C.TIME_UNSET) durationMs = it.duration.coerceAtLeast(0)
            speed = it.playbackParameters.speed
        }
    }

    fun play() {
        player?.play()
    }

    fun pause() {
        controls?.onStop()
        player?.pause()
    }

    fun seek(positionMs: Long) {
        player?.seekTo(positionMs)
    }

    fun setSpeed(speed: Float) {
        controls?.endSpeedBoost()
        player?.setPlaybackSpeed(speed)
    }

    fun setLocked(locked: Boolean) {
        controls?.setLocked(locked)
    }

    private fun snapshot(): Map<String, Any?> = mapOf(
        "positionMs" to positionMs,
        "durationMs" to durationMs,
        "speed" to speed.toDouble(),
        "completed" to completed,
        "error" to playbackError,
    )

    private fun publishState() {
        if (returning) return
        player?.let {
            positionMs = it.currentPosition.coerceAtLeast(0)
            if (it.duration != C.TIME_UNSET) durationMs = it.duration.coerceAtLeast(0)
            onEvent("state", snapshot() + mapOf(
                "speed" to it.playbackParameters.speed.toDouble(),
                "playing" to it.playWhenReady,
                "buffering" to (it.playbackState == Player.STATE_BUFFERING),
            ))
        }
    }

    fun close() {
        if (returning) return
        returning = true
        handler.removeCallbacks(progress)
        controls?.dispose()
        capturePosition()
        playerView.player = null
        player?.release()
        player = null
        playerView.keepScreenOn = false
        (view.parent as? ViewGroup)?.removeView(view)
        if (Build.VERSION.SDK_INT >= 26) activity.window.colorMode = previousColorMode
        onClosed(snapshot())
    }

    private fun fail(message: String, cause: Throwable? = null) {
        Log.e("PiliPlusMedia3", message, cause)
        playbackError = message
        close()
    }

    companion object {
        private val PROFILE_5 = Regex("(?i)(dvhe|dvh1)\\.0?5\\..+")
    }
}
