package com.example.piliplus

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory

internal class Media3Bridge(private val activity: Activity, messenger: BinaryMessenger) {
    private val channel = MethodChannel(messenger, "com.example.piliplus/media3")
    private var session: Media3Session? = null
    private var container: FrameLayout? = null

    val viewFactory = object : PlatformViewFactory(StandardMessageCodec.INSTANCE) {
        override fun create(context: Context, viewId: Int, args: Any?): PlatformView {
            val host = FrameLayout(context)
            container = host
            attach()
            return object : PlatformView {
                override fun getView(): View = host

                override fun dispose() {
                    host.removeAllViews()
                    if (container === host) container = null
                }
            }
        }
    }

    init {
        channel.setMethodCallHandler(::handle)
    }

    private fun attach() {
        val host = container ?: return
        val view = session?.view ?: return
        (view.parent as? ViewGroup)?.removeView(view)
        host.addView(view, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
    }

    private fun handle(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "open" -> {
                if (session != null) {
                    result.error("busy", "Media3 is already open", null)
                    return
                }
                val request = try {
                    Media3Request.parse(call.arguments)
                } catch (error: IllegalArgumentException) {
                    result.error("invalid_source", error.message, null)
                    return
                } catch (error: java.net.URISyntaxException) {
                    result.error("invalid_source", error.message, null)
                    return
                }
                val playback = Media3Session(activity, request, { method, arguments ->
                    channel.invokeMethod(method, arguments)
                }) { state ->
                    session = null
                    result.success(state)
                }
                session = playback
                attach()
                playback.start()
            }
            "close" -> {
                session?.close()
                result.success(null)
            }
            "pause" -> {
                pause()
                result.success(null)
            }
            "play" -> {
                session?.play()
                result.success(null)
            }
            "seek" -> {
                val position = call.arguments
                if (position !is Number || position.toLong() < 0 ||
                    (position !is Int && position !is Long)) {
                    result.error("invalid_position", "Invalid playback position", null)
                    return
                }
                session?.seek(position.toLong())
                result.success(null)
            }
            "speed" -> {
                val speed = (call.arguments as? Number)?.toFloat()
                if (speed == null || !speed.isFinite() || speed <= 0) {
                    result.error("invalid_speed", "Invalid playback speed", null)
                    return
                }
                session?.setSpeed(speed)
                result.success(null)
            }
            else -> result.notImplemented()
        }
    }

    fun pause() {
        session?.pause()
    }

    fun dispose() {
        session?.close()
        container = null
        channel.setMethodCallHandler(null)
    }
}
