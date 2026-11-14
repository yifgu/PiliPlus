package com.example.piliplus

import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.Space
import kotlin.math.roundToInt

internal class Media3CutoutAvoidance(
    private val root: ViewGroup,
    buttons: List<Pair<View, FrameLayout>>,
    private val fixedControls: List<View>,
) : View.OnAttachStateChangeListener, ViewTreeObserver.OnGlobalLayoutListener {
    private class Target(val button: View, val container: FrameLayout) {
        val parent = button.parent as ViewGroup
        val params = button.layoutParams
        var spacer: Space? = null
        val anchor: View get() = spacer ?: button

        fun restore() {
            spacer?.let {
                container.removeView(button)
                val index = parent.indexOfChild(it)
                parent.removeView(it)
                button.translationX = 0f
                button.translationY = 0f
                parent.addView(button, index, params)
                spacer = null
            }
        }

        fun place(bounds: Media3ControlBounds, original: Media3ControlBounds) {
            if (bounds == original) return restore()
            if (spacer == null) {
                // A full-window parent keeps the moved button both visible and touchable.
                // The spacer preserves every sibling's original layout position.
                val placeholder = Space(button.context).apply {
                    minimumWidth = button.measuredWidth
                    minimumHeight = button.measuredHeight
                }
                val index = parent.indexOfChild(button)
                parent.removeView(button)
                parent.addView(placeholder, index, params)
                spacer = placeholder
                container.addView(
                    button,
                    FrameLayout.LayoutParams(params.width, params.height, Gravity.TOP or Gravity.LEFT),
                )
            }
            val location = IntArray(2)
            container.getLocationInWindow(location)
            button.translationX = (bounds.left - location[0]).toFloat()
            button.translationY = (bounds.top - location[1]).toFloat()
        }

        fun syncAnchor() {
            spacer?.let {
                val visibility = if (button.visibility == View.GONE) View.GONE else View.INVISIBLE
                if (it.visibility != visibility) it.visibility = visibility
                if (it.minimumWidth != button.measuredWidth) it.minimumWidth = button.measuredWidth
                if (it.minimumHeight != button.measuredHeight) it.minimumHeight = button.measuredHeight
            }
        }
    }

    private val targets = buttons.map { (button, container) -> Target(button, container) }
    private var cutouts = emptyList<Media3ControlBounds>()
    private val gap = (4 * root.resources.displayMetrics.density).roundToInt()
    private var observer: ViewTreeObserver? = null
    private var disposed = false

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            root.setOnApplyWindowInsetsListener { _, insets ->
                if (!disposed && root.isAttachedToWindow) {
                    cutouts = insets.displayCutout?.boundingRects.orEmpty().map {
                        Media3ControlBounds(it.left, it.top, it.right, it.bottom)
                    }
                    update()
                }
                insets
            }
            root.addOnAttachStateChangeListener(this)
            if (root.isAttachedToWindow) onViewAttachedToWindow(root)
        }
    }

    override fun onViewAttachedToWindow(view: View) {
        if (disposed || observer != null) return
        observer = root.viewTreeObserver.also { it.addOnGlobalLayoutListener(this) }
        root.requestApplyInsets()
    }

    override fun onViewDetachedFromWindow(view: View) {
        stopObserving()
        cutouts = emptyList()
    }

    private fun stopObserving() {
        val previous = observer ?: return
        observer = null
        if (previous.isAlive) previous.removeOnGlobalLayoutListener(this)
        // A floating observer can be merged into a different window's observer.
        val current = root.viewTreeObserver
        if (current !== previous && current.isAlive) current.removeOnGlobalLayoutListener(this)
    }

    override fun onGlobalLayout() = update()

    fun dispose() {
        if (disposed) return
        disposed = true
        stopObserving()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            root.removeOnAttachStateChangeListener(this)
            root.setOnApplyWindowInsetsListener(null)
        }
        cutouts = emptyList()
        targets.forEach { it.restore() }
    }

    private fun View.boundsInWindow(): Media3ControlBounds {
        val location = IntArray(2)
        getLocationInWindow(location)
        return Media3ControlBounds(location[0], location[1], location[0] + width, location[1] + height)
    }

    private fun update() {
        if (disposed || !root.isAttachedToWindow || root.width == 0 || root.height == 0) return
        targets.forEach { it.syncAnchor() }
        val window = root.boundsInWindow()
        val visibleTargets = targets.filter { it.button.isShown && it.button.width > 0 && it.button.height > 0 }
        val positions = visibleTargets.associateWith { it.anchor.boundsInWindow() }.toMutableMap()
        val fixed = fixedControls.filter { it.isShown }.map { it.boundsInWindow() }
        for (target in visibleTargets) {
            val original = positions.getValue(target)
            val occupied = fixed + positions.filterKeys { it != target }.values
            val position = media3CutoutPosition(original, window, cutouts, occupied, gap)
            if (position == null) {
                Log.w("PiliPlusMedia3", "No unobscured position for control ${target.button.id}")
                target.place(original, original)
                continue
            }
            target.place(position, original)
            positions[target] = position
        }
    }
}
