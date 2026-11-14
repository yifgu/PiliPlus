package com.example.piliplus

import android.app.Activity
import android.app.Application
import android.content.pm.ActivityInfo
import android.view.View
import android.widget.FrameLayout
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.FlutterException
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.StandardMethodCodec
import io.flutter.plugin.platform.PlatformViewFactory
import io.flutter.plugin.platform.PlatformViewRegistry
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.mockito.Mockito.`when`
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class Media3LifecycleTest {
    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val host = FrameLayout(activity).also { activity.setContentView(it) }
    private val messenger = Mockito.mock(BinaryMessenger::class.java)
    private val registry = Mockito.mock(PlatformViewRegistry::class.java)
    private val binding = Mockito.mock(FlutterPlugin.FlutterPluginBinding::class.java)
    private val events = mutableListOf<MethodCall>()
    private lateinit var playback: Media3Session
    private val bridge = Media3Bridge { owner, request, onEvent, onClosed ->
        // Exercise real session/control lifecycle without requiring a Dolby Vision decoder.
        val session = Media3Session(owner, request, onEvent, onClosed)
        val controls = Media3Controls(
            owner, session.view, session.view.findViewById(R.id.media3_player), request, onEvent,
        )
        ReflectionHelpers.setField(session, "controls", controls)
        Mockito.spy(session).also {
            Mockito.doNothing().`when`(it).start()
            playback = it
        }
    }
    private lateinit var handler: BinaryMessenger.BinaryMessageHandler
    private lateinit var factory: PlatformViewFactory

    private class Reply {
        var buffer: ByteBuffer? = null
        var count = 0

        fun value(): Any? = StandardMethodCodec.INSTANCE.decodeEnvelope(
            requireNotNull(buffer).duplicate().apply { flip() },
        )
    }

    private fun attachEngine() {
        `when`(binding.binaryMessenger).thenReturn(messenger)
        `when`(binding.platformViewRegistry).thenReturn(registry)
        `when`(registry.registerViewFactory(matching(String::class.java), matching(PlatformViewFactory::class.java)))
            .thenReturn(true)
        Mockito.doAnswer { invocation ->
            val message = invocation.getArgument<ByteBuffer>(1).duplicate().apply { flip() }
            events += StandardMethodCodec.INSTANCE.decodeMethodCall(message)
            null
        }.`when`(messenger).send(
            matching(String::class.java), matching(ByteBuffer::class.java), Mockito.isNull(),
        )
        bridge.onAttachedToEngine(binding)

        val handlers = ArgumentCaptor.forClass(BinaryMessenger.BinaryMessageHandler::class.java)
        Mockito.verify(messenger).setMessageHandler(equalTo(CHANNEL), capture(handlers))
        handler = handlers.value
        val factories = ArgumentCaptor.forClass(PlatformViewFactory::class.java)
        Mockito.verify(registry).registerViewFactory(equalTo("com.example.piliplus/media3_view"), capture(factories))
        factory = factories.value
    }

    private fun attachActivity(owner: Activity = activity, reattach: Boolean = false) {
        val binding = Mockito.mock(ActivityPluginBinding::class.java)
        `when`(binding.activity).thenReturn(owner)
        if (reattach) bridge.onReattachedToActivityForConfigChanges(binding)
        else bridge.onAttachedToActivity(binding)
    }

    private fun command(method: String, arguments: Any? = null): Reply {
        val reply = Reply()
        handler.onMessage(
            StandardMethodCodec.INSTANCE.encodeMethodCall(MethodCall(method, arguments)).apply { flip() },
        ) { buffer ->
            reply.buffer = buffer
            reply.count++
        }
        return reply
    }

    private fun open(): Reply = command("open", mapOf(
        "video" to "https://video.example/video.m4s",
        "audio" to "https://audio.example/audio.m4s",
        "positionMs" to 12345L,
        "speed" to 1.5,
        "headers" to mapOf("Referer" to "https://www.bilibili.com", "User-Agent" to "PiliPlus"),
        "title" to "Video title",
        "speeds" to listOf(0.75, 1.0, 1.5, 2.5),
        "seekStepMs" to 15000,
        "controlsTimeoutMs" to 30000,
        "quickDoubleTap" to false,
        "longPressSpeed" to 0.0,
        "accentColor" to 0xFFABCDEF,
    ))

    @Test
    fun cachedEngineReusesItsPluginInsteadOfRegisteringTheFactoryAgain() {
        val engine = Mockito.mock(FlutterEngine::class.java, Mockito.RETURNS_DEEP_STUBS)
        val plugins = engine.plugins
        var installed: FlutterPlugin? = null
        `when`(plugins.has(Media3Bridge::class.java)).thenAnswer { installed != null }
        `when`(plugins.get(Media3Bridge::class.java)).thenAnswer { installed }
        Mockito.doAnswer {
            installed = it.getArgument(0)
            null
        }.`when`(plugins).add(matching(FlutterPlugin::class.java))

        assertSame(Media3Bridge.forEngine(engine), Media3Bridge.forEngine(engine))
        Mockito.verify(plugins, Mockito.times(1)).add(matching(FlutterPlugin::class.java))
    }

    @Test
    fun platformViewReparentingKeepsTheSessionAndFlutterOwnsBackAndFullscreen() {
        attachEngine()
        attachActivity()
        val first = factory.create(activity, 1, null)
        host.addView(first.view)
        @Suppress("DEPRECATION")
        val systemUi = activity.window.decorView.systemUiVisibility
        val result = open()
        val view = playback.view
        assertSame(first.view, view.parent)
        assertEquals(0, result.count)

        val replacement = factory.create(activity, 2, null)
        host.addView(replacement.view)
        assertSame(replacement.view, view.parent)
        first.dispose()
        assertSame(replacement.view, view.parent)
        view.findViewById<View>(R.id.media3_fullscreen).performClick()
        view.findViewById<View>(R.id.media3_back).performClick()
        assertEquals(listOf("fullscreen", "back"), events.takeLast(2).map { it.method })
        assertFalse(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
        @Suppress("DEPRECATION")
        assertEquals(systemUi, activity.window.decorView.systemUiVisibility)
        assertSame(replacement.view, view.parent)
        assertEquals(0, result.count)
        command("close")
        assertEquals(1, result.count)
        assertNull(view.parent)
    }

    @Test
    fun flutterUnlockAndTheNativeButtonShareOrientationAndControlState() {
        attachEngine()
        attachActivity()
        host.addView(factory.create(activity, 1, null).view)
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        open()
        val player = playback.view.findViewById<PlayerView>(R.id.media3_player)
        val lock = playback.view.findViewById<View>(R.id.media3_lock)
        val fullscreen = playback.view.findViewById<View>(R.id.media3_fullscreen)
        lock.performClick()
        assertFalse(player.useController)
        assertFalse(fullscreen.isEnabled)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LOCKED, activity.requestedOrientation)

        assertNull(command("lock", false).value())
        assertTrue(player.useController)
        assertTrue(player.isControllerFullyVisible)
        assertTrue(fullscreen.isEnabled)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, activity.requestedOrientation)
        assertEquals(listOf(true, false), events.filter { it.method == "lock" }.takeLast(2).map { it.arguments })

        val invalid = command("lock", "false")
        assertEquals("invalid_lock", assertThrows(FlutterException::class.java) { invalid.value() }.code)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, activity.requestedOrientation)
        command("close")
    }

    @Test
    fun activityDetachClosesOnceRestoresOrientationAndKeepsEngineChannelForReattachment() {
        attachEngine()
        attachActivity()
        host.addView(factory.create(activity, 1, null).view)
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        val result = open()
        val view = playback.view
        val observer = view.viewTreeObserver
        val listenerCount = globalLayoutListenerCount(observer)
        command("lock", true)
        bridge.onDetachedFromActivityForConfigChanges()
        assertEquals(1, result.count)
        val state = result.value() as Map<*, *>
        assertEquals(12345L, state["positionMs"])
        assertEquals(1.5, state["speed"])
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR, activity.requestedOrientation)
        assertNull(view.parent)
        assertEquals(listenerCount - 1, globalLayoutListenerCount(observer))
        playback.close()
        playback.setLocked(true)
        assertEquals(1, result.count)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR, activity.requestedOrientation)

        val detached = open()
        assertEquals("detached", assertThrows(FlutterException::class.java) { detached.value() }.code)
        attachActivity(reattach = true)
        val next = open()
        assertNull(playback.view.parent)
        host.addView(factory.create(activity, 2, null).view)
        assertTrue(playback.view.isAttachedToWindow)
        bridge.onDetachedFromActivity()
        assertEquals(1, next.count)
        Mockito.verify(registry, Mockito.times(1))
            .registerViewFactory(matching(String::class.java), matching(PlatformViewFactory::class.java))
        bridge.onDetachedFromEngine(binding)
        Mockito.verify(messenger).setMessageHandler(CHANNEL, null)
    }

    companion object {
        private const val CHANNEL = "com.example.piliplus/media3"

        private fun <T> matching(type: Class<T>): T = Mockito.any(type)
        private fun <T> equalTo(value: T): T = Mockito.eq(value)
        private fun <T> capture(captor: ArgumentCaptor<T>): T = captor.capture()
    }
}
