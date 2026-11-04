package com.example.piliplus

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class Media3FullscreenLayoutTest {
    private val android = "http://schemas.android.com/apk/res/android"
    private val app = "http://schemas.android.com/apk/res-auto"

    private fun resource(path: String): Element =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(File("src/main/res/$path")).documentElement

    private fun Element.elements(tag: String): List<Element> =
        getElementsByTagName(tag).let { nodes ->
            List(nodes.length) { nodes.item(it) as Element }
        }

    private fun assertFullWindow(view: Element) {
        assertEquals("match_parent", view.getAttributeNS(android, "layout_width"))
        assertEquals("match_parent", view.getAttributeNS(android, "layout_height"))
        assertNotEquals("true", view.getAttributeNS(android, "fitsSystemWindows"))
        for (attribute in listOf(
            "padding", "paddingLeft", "paddingTop", "paddingRight", "paddingBottom",
            "paddingStart", "paddingEnd", "paddingHorizontal", "paddingVertical",
            "layout_margin", "layout_marginLeft", "layout_marginTop", "layout_marginRight",
            "layout_marginBottom", "layout_marginStart", "layout_marginEnd",
            "layout_marginHorizontal", "layout_marginVertical",
        )) {
            assertFalse("Full-window containers must not reserve $attribute", view.hasAttributeNS(android, attribute))
        }
    }

    @Test
    fun nativePlaybackIsEmbeddedInsteadOfLaunchingAnotherActivity() {
        val bridge = File(
            "src/main/kotlin/com/example/piliplus/Media3Bridge.kt",
        ).readText()
        val session = File("src/main/kotlin/com/example/piliplus/Media3Session.kt").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(bridge.contains("PlatformViewFactory"))
        assertTrue(bridge.contains("host.addView(view"))
        assertFalse(bridge.contains("startActivity"))
        assertFalse(manifest.contains("Media3PlayerActivity"))
        assertFalse(session.contains("setContentView"))
        assertFalse(session.contains("systemUiVisibility"))
        assertFalse(session.contains("hide(WindowInsets.Type.systemBars())"))
    }

    @Test
    fun fullscreenControlIsAvailableWithoutReplacingTheNativeSession() {
        val controls = resource("layout/media3_controls.xml")
        val fullscreen = controls.elements("ImageButton").single {
            it.getAttributeNS(android, "id") == "@+id/media3_fullscreen"
        }
        assertEquals("@string/media3_player_fullscreen", fullscreen.getAttributeNS(android, "contentDescription"))
        val code = File("src/main/kotlin/com/example/piliplus/Media3Controls.kt").readText()
        assertTrue(code.contains("onEvent(\"fullscreen\", null)"))
        assertTrue(code.contains("onEvent(\"back\", null)"))
        assertFalse(code.contains("activity.finish()"))
        assertFalse(code.contains("activity.findViewById"))
    }

    @Test
    fun cachedEngineOwnsTheBridgeAndFactoryAcrossActivityReattachment() {
        val activity = File("src/main/kotlin/com/example/piliplus/MainActivity.kt").readText()
        val bridge = File("src/main/kotlin/com/example/piliplus/Media3Bridge.kt").readText()
        assertTrue(activity.contains("if (!flutterEngine.plugins.has(Media3Bridge::class.java))"))
        assertTrue(activity.contains("flutterEngine.plugins.get(Media3Bridge::class.java)"))
        assertFalse(activity.contains("registerViewFactory"))
        assertFalse(activity.contains("media3Bridge?.dispose()"))
        assertTrue(bridge.contains("Media3Bridge : FlutterPlugin, ActivityAware"))
        val attachEngine = bridge.substringAfter("override fun onAttachedToEngine(")
            .substringBefore("override fun onDetachedFromEngine(")
        assertTrue(attachEngine.contains("registerViewFactory("))
        assertTrue(attachEngine.contains("channel.setMethodCallHandler(::handle)"))
        val reattach = bridge.substringAfter("override fun onReattachedToActivityForConfigChanges(")
            .substringBefore("override fun onDetachedFromActivityForConfigChanges(")
        assertTrue(reattach.contains("onAttachedToActivity(binding)"))
        val detach = bridge.substringAfter("private fun detachActivity()")
            .substringBefore("private fun attach()")
        assertTrue(detach.contains("session?.close()"))
        assertTrue(detach.contains("container = null"))
        assertTrue(detach.contains("activity = null"))
        assertFalse(detach.contains("setMethodCallHandler(null)"))
        assertTrue(bridge.contains("result.error(\"detached\""))
    }

    @Test
    fun flutterUnlockUsesTheSameNativeControlAndOrientationPathAsTheLockButton() {
        val bridge = File("src/main/kotlin/com/example/piliplus/Media3Bridge.kt").readText()
        val session = File("src/main/kotlin/com/example/piliplus/Media3Session.kt").readText()
        val controls = File("src/main/kotlin/com/example/piliplus/Media3Controls.kt").readText()
        val command = bridge.substringAfter("\"lock\" ->").substringBefore("\"seek\" ->")
        assertTrue(command.contains("locked !is Boolean"))
        assertTrue(command.contains("session?.setLocked(locked)"))
        assertTrue(session.contains("controls?.setLocked(locked)"))
        assertTrue(controls.contains("setLocked(!gestures.locked)"))
        val setter = controls.substringAfter("fun setLocked(locked: Boolean)")
            .substringBefore("private fun applyLock()")
        assertTrue(setter.contains("endSpeedBoost()"))
        assertTrue(setter.contains("gestures.locked = locked"))
        assertTrue(setter.contains("applyLock()"))
        assertTrue(setter.contains("if (!locked) playerView.showController()"))
        val applyLock = controls.substringAfter("private fun applyLock()")
            .substringBefore("private fun showLock()")
        assertTrue(applyLock.contains("playerView.useController = !locked"))
        assertTrue(applyLock.contains("restoreOrientation()"))
        assertTrue(applyLock.contains("onEvent(\"lock\", locked)"))
    }

    @Test
    fun videoDoesNotReserveASystemOrCutoutSafeZone() {
        val root = resource("layout/media3_player.xml")
        assertFullWindow(root)
        val player = root.elements("androidx.media3.ui.PlayerView").single()
        assertEquals(root, player.parentNode)
        assertFullWindow(player)
        assertEquals("surface_view", player.getAttributeNS(app, "surface_type"))
        assertEquals("@layout/media3_controls", player.getAttributeNS(app, "controller_layout_id"))
    }

    @Test
    fun nativeFullscreenThemeAllowsShortEdgeCutoutsOnAndroid9And10() {
        val theme = resource("values/styles.xml").elements("style").single {
            it.getAttribute("name") == "Media3PlayerTheme"
        }
        val items = theme.elements("item").associate { it.getAttribute("name") to it.textContent }
        assertEquals("true", items["android:windowFullscreen"])
        assertEquals("shortEdges", items["android:windowLayoutInDisplayCutoutMode"])
    }

    @Test
    fun controlsAndLockUseTheSameFullWindowBoundsAsTheVideo() {
        val controls = resource("layout/media3_controls.xml")
        assertEquals("@+id/media3_controls", controls.getAttributeNS(android, "id"))
        assertFullWindow(controls)

        val root = resource("layout/media3_player.xml")
        val lockContainer = root.elements("FrameLayout").single {
            it.getAttributeNS(android, "id") == "@+id/media3_lock_container"
        }
        assertEquals(root, lockContainer.parentNode)
        assertFullWindow(lockContainer)
        assertEquals(
            "@+id/media3_lock",
            lockContainer.elements("ImageButton").single().getAttributeNS(android, "id"),
        )
        assertEquals(0, lockContainer.elements("androidx.media3.ui.PlayerView").size)
    }

    @Test
    fun cutoutAvoidanceDoesNotInsetWholeControlRows() {
        val controls = File("src/main/kotlin/com/example/piliplus/Media3Controls.kt").readText()
        val avoidance = File("src/main/kotlin/com/example/piliplus/Media3CutoutAvoidance.kt").readText()
        assertTrue(controls.contains("Media3CutoutAvoidance("))
        assertTrue(avoidance.contains("insets.displayCutout?.boundingRects"))
        assertFalse(
            "Control spacing belongs in the layout, not runtime safe-area padding",
            (controls + avoidance).contains("setPadding("),
        )
        assertFalse(avoidance.contains("systemBars()"))
    }
}
