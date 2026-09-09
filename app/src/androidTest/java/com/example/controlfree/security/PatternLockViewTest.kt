package com.example.controlfree.security

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask

@RunWith(AndroidJUnit4::class)
class PatternLockViewTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun touchLifecycle_keepsParentFromInterceptingUntilGestureEnds() = onMainThread {
        val parent = RecordingParent(context)
        val view = createPatternView(parent)

        assertTrue(send(view, MotionEvent.ACTION_DOWN, 50f, 50f, 10L))
        assertTrue(send(view, MotionEvent.ACTION_MOVE, 50f, 150f, 20L))
        assertTrue(send(view, MotionEvent.ACTION_UP, 50f, 250f, 30L))

        assertEquals(listOf(true, false), parent.interceptionRequests)
        view.clearPattern()
    }

    @Test
    fun cancelledGesture_releasesParentAndDoesNotCompletePattern() = onMainThread {
        val parent = RecordingParent(context)
        val view = createPatternView(parent)
        val completedPatterns = mutableListOf<List<Int>>()
        view.onPatternComplete = completedPatterns::add

        send(view, MotionEvent.ACTION_DOWN, 50f, 50f, 10L)
        send(view, MotionEvent.ACTION_MOVE, 50f, 150f, 20L)
        assertTrue(send(view, MotionEvent.ACTION_CANCEL, 50f, 150f, 30L))

        assertEquals(listOf(true, false), parent.interceptionRequests)
        assertTrue(completedPatterns.isEmpty())
        assertFalse(send(view, MotionEvent.ACTION_UP, 50f, 250f, 40L))
    }

    @Test
    fun fastSegment_addsEveryCrossedNodeInPathOrder() = onMainThread {
        val parent = RecordingParent(context)
        val view = createPatternView(parent)
        var completedPattern: List<Int>? = null
        view.onPatternComplete = { completedPattern = it }

        send(view, MotionEvent.ACTION_DOWN, 50f, 50f, 10L)
        send(view, MotionEvent.ACTION_MOVE, 50f, 299f, 20L)
        send(view, MotionEvent.ACTION_UP, 50f, 299f, 30L)

        assertEquals(listOf(0, 3, 6), completedPattern)
        view.clearPattern()
    }

    @Test
    fun historicalSamples_preserveBentGesturePath() = onMainThread {
        val parent = RecordingParent(context)
        val view = createPatternView(parent)
        var completedPattern: List<Int>? = null
        view.onPatternComplete = { completedPattern = it }

        send(view, MotionEvent.ACTION_DOWN, 50f, 50f, 10L)
        val move = MotionEvent.obtain(DOWN_TIME, 20L, MotionEvent.ACTION_MOVE, 250f, 50f, 0).apply {
            addBatch(30L, 250f, 250f, 1f, 1f, 0)
        }
        try {
            assertEquals(1, move.historySize)
            view.onTouchEvent(move)
        } finally {
            move.recycle()
        }
        send(view, MotionEvent.ACTION_UP, 250f, 250f, 40L)

        assertEquals(listOf(0, 1, 2, 5, 8), completedPattern)
        view.clearPattern()
    }

    @Test
    fun detachingDuringGesture_releasesParentAndClearsGesture() = onMainThread {
        val parent = RecordingParent(context)
        val view = createPatternView(parent)
        val completedPatterns = mutableListOf<List<Int>>()
        view.onPatternComplete = completedPatterns::add

        send(view, MotionEvent.ACTION_DOWN, 50f, 50f, 10L)
        PatternLockView::class.java.getDeclaredMethod("onDetachedFromWindow").run {
            isAccessible = true
            invoke(view)
        }

        assertEquals(listOf(true, false), parent.interceptionRequests)
        assertFalse(send(view, MotionEvent.ACTION_UP, 50f, 250f, 20L))
        assertTrue(completedPatterns.isEmpty())
    }

    private fun createPatternView(parent: FrameLayout): PatternLockView =
        PatternLockView(parent.context).also { view ->
            parent.addView(view, FrameLayout.LayoutParams(VIEW_SIZE, VIEW_SIZE))
            view.measure(exactly(VIEW_SIZE), exactly(VIEW_SIZE))
            view.layout(0, 0, VIEW_SIZE, VIEW_SIZE)
        }

    private fun send(
        view: PatternLockView,
        action: Int,
        x: Float,
        y: Float,
        eventTime: Long
    ): Boolean {
        val event = MotionEvent.obtain(DOWN_TIME, eventTime, action, x, y, 0)
        return try {
            view.onTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun exactly(size: Int): Int =
        View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)

    private fun <T> onMainThread(block: () -> T): T {
        val task = FutureTask(Callable { block() })
        InstrumentationRegistry.getInstrumentation().runOnMainSync(task)
        return task.get()
    }

    private class RecordingParent(context: Context) : FrameLayout(context) {
        val interceptionRequests = mutableListOf<Boolean>()

        override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
            interceptionRequests += disallowIntercept
            super.requestDisallowInterceptTouchEvent(disallowIntercept)
        }
    }

    private companion object {
        const val VIEW_SIZE = 300
        const val DOWN_TIME = 1L
    }
}
