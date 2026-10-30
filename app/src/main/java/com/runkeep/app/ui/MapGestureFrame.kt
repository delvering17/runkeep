package com.runkeep.app.ui

import android.content.Context
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.widget.FrameLayout

/** Give map gestures to the map, never the surrounding Compose scrolling container. */
class MapGestureFrame(context: Context) : FrameLayout(context) {
    var hitTest: (Float, Float) -> Int? = { _, _ -> null }
    var selectedPoint: () -> Int? = { null }
    var onPointSelected: (Int) -> Unit = { }
    var segmentHitTest: (Float, Float) -> Int? = { _, _ -> null }
    var onSegmentSelected: (Int) -> Unit = { }
    var onMapTap: (Float, Float) -> Unit = { _, _ -> }
    var onPan: () -> Unit = { }
    var onPointDragged: (Int, Float, Float) -> Unit = { _, _, _ -> }
    var onPointDropped: (Int, Float, Float) -> Unit = { _, _, _ -> }
    var onDragCancelled: (Int) -> Unit = { }
    private var dragging: Int? = null
    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val index = hitTest(e.x, e.y)
            if (index != null) onPointSelected(index)
            else segmentHitTest(e.x, e.y)?.let(onSegmentSelected) ?: onMapTap(e.x, e.y)
            performClick(); return true
        }
        override fun onLongPress(e: MotionEvent) {
            val index = hitTest(e.x, e.y)
            if (index == null || index != selectedPoint()) return
            dragging = index
            val cancel = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
            cancelMapGesture(cancel); cancel.recycle()
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (dragging == null) onPan()
            return false
        }
    })
    private fun cancelMapGesture(event: MotionEvent) { super.dispatchTouchEvent(event) }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        detector.onTouchEvent(event)
        val index = dragging
        if (index != null) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> onPointDragged(index, event.x, event.y)
                MotionEvent.ACTION_UP -> { onPointDropped(index, event.x, event.y); dragging = null }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> { onDragCancelled(index); dragging = null }
            }
            if (dragging == null) parent?.requestDisallowInterceptTouchEvent(false)
            return true
        }
        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) parent?.requestDisallowInterceptTouchEvent(false)
        return handled
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
