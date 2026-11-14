package com.example.piliplus

import android.app.Activity
import android.graphics.Rect
import android.view.DisplayCutout
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class Media3CutoutAvoidanceTest {
    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val host = FrameLayout(activity).also { activity.setContentView(it) }
    private val root = FrameLayout(activity)
    private val row = LinearLayout(activity)
    private val button = View(activity)
    private val sibling = View(activity)
    private val originalParams = LinearLayout.LayoutParams(48, 48)
    private val avoidance: Media3CutoutAvoidance

    init {
        row.addView(button, originalParams)
        row.addView(sibling, LinearLayout.LayoutParams(48, 48))
        root.addView(row, FrameLayout.LayoutParams(400, 48))
        avoidance = Media3CutoutAvoidance(root, listOf(button to root), listOf(sibling))
    }

    private fun attach(parent: ViewGroup = host) {
        parent.addView(root)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 400, 240)
    }

    private fun cutoutInsets(): WindowInsets {
        val location = IntArray(2)
        row.getLocationInWindow(location)
        val cutout = Rect(location[0], location[1], location[0] + 48, location[1] + 48)
        return WindowInsets.Builder()
            .setDisplayCutout(DisplayCutout(Rect(0, cutout.bottom, 0, 0), listOf(cutout)))
            .build()
    }

    @Test
    fun cutoutsMoveOnlyTheObstructedButtonAndRestoreItsOriginalSlot() {
        attach()
        val insets = cutoutInsets()
        assertSame(insets, root.dispatchApplyWindowInsets(insets))
        assertSame(root, button.parent)
        assertSame(row, sibling.parent)
        assertEquals(48, sibling.left)
        assertFalse(button.translationX == 0f && button.translationY == 0f)
        assertEquals(0, root.paddingLeft + root.paddingTop + root.paddingRight + root.paddingBottom)
        assertEquals(0, row.paddingLeft + row.paddingTop + row.paddingRight + row.paddingBottom)

        root.dispatchApplyWindowInsets(WindowInsets.Builder().build())
        assertSame(row, button.parent)
        assertEquals(0, row.indexOfChild(button))
        assertSame(originalParams, button.layoutParams)
        assertEquals(2, row.childCount)
        assertEquals(0f, button.translationX, 0f)
        assertEquals(0f, button.translationY, 0f)
        avoidance.dispose()
    }

    @Test
    fun detachmentRemovesTheWindowListenerAndReparentingRegistersOnTheNewWindow() {
        attach()
        val previous = root.viewTreeObserver
        val attachedCount = globalLayoutListenerCount(previous)
        assertTrue(attachedCount > 0)
        root.dispatchApplyWindowInsets(cutoutInsets())
        host.removeView(root)
        assertEquals(attachedCount - 1, globalLayoutListenerCount(previous))
        button.translationX = 0f
        button.translationY = 0f
        root.dispatchApplyWindowInsets(cutoutInsets())
        previous.dispatchOnGlobalLayout()
        assertEquals(0f, button.translationX, 0f)
        assertEquals(0f, button.translationY, 0f)

        val other = Robolectric.buildActivity(Activity::class.java).setup().get()
        val nextHost = FrameLayout(other).also { other.setContentView(it) }
        val current = nextHost.viewTreeObserver
        val nextCount = globalLayoutListenerCount(current)
        attach(nextHost)
        assertNotSame(previous, root.viewTreeObserver)
        assertEquals(nextCount + 1, globalLayoutListenerCount(current))
        root.dispatchApplyWindowInsets(cutoutInsets())
        assertSame(root, button.parent)
        avoidance.dispose()
        assertEquals(nextCount, globalLayoutListenerCount(current))
    }

    @Test
    fun disposalRestoresReparentedButtonsAndDoesNotRegisterAgainAfterReattachment() {
        assertEquals(0, globalLayoutListenerCount(root.viewTreeObserver))
        attach()
        val observer = root.viewTreeObserver
        val attachedCount = globalLayoutListenerCount(observer)
        root.dispatchApplyWindowInsets(cutoutInsets())
        assertSame(root, button.parent)

        avoidance.dispose()
        avoidance.dispose()
        assertEquals(attachedCount - 1, globalLayoutListenerCount(observer))
        assertSame(row, button.parent)
        assertSame(originalParams, button.layoutParams)
        assertEquals(2, row.childCount)
        root.dispatchApplyWindowInsets(cutoutInsets())
        assertSame(row, button.parent)
        host.removeView(root)
        val count = globalLayoutListenerCount(host.viewTreeObserver)
        attach()
        assertEquals(count, globalLayoutListenerCount(root.viewTreeObserver))
        root.dispatchApplyWindowInsets(cutoutInsets())
        assertSame(row, button.parent)
    }
}

internal fun globalLayoutListenerCount(observer: ViewTreeObserver): Int {
    val listeners = ReflectionHelpers.getField<Any?>(observer, "mOnGlobalLayoutListeners") ?: return 0
    return ReflectionHelpers.callInstanceMethod(listeners, "size")
}
