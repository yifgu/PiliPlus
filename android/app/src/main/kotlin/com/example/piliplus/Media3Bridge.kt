package com.example.piliplus

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory

internal class Media3Bridge : FlutterPlugin, ActivityAware {
    private lateinit var channel: MethodChannel
    private var activity: Activity? = null
    private var session: Media3Session? = null
    private var container: FrameLayout? = null

    private val viewFactory = object : PlatformViewFactory(StandardMessageCodec.INSTANCE) {
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

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(binding.binaryMessenger, "com.example.piliplus/media3")
        check(binding.platformViewRegistry.registerViewFactory(
            "com.example.piliplus/media3_view", viewFactory,
        )) { "Media3 platform view factory is already registered" }
        channel.setMethodCallHandler(::handle)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        detachActivity()
        channel.setMethodCallHandler(null)
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        onAttachedToActivity(binding)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        detachActivity()
    }

    override fun onDetachedFromActivity() {
        detachActivity()
    }

    private fun detachActivity() {
        session?.close()
        container = null
        activity = null
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
                val activity = activity
                if (activity == null) {
                    result.error("detached", "Flutter activity is not attached", null)
                    return
                }
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
            "lock" -> {
                val locked = call.arguments
                if (locked !is Boolean) {
                    result.error("invalid_lock", "Invalid controls lock state", null)
                    return
                }
                session?.setLocked(locked)
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
}
