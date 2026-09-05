package com.lineup.app.ui

import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator

/**
 * Subtle scale-down-on-press feedback for CTAs, layered on top of the MaterialButton ripple
 * (per the design brief's motion spec). Purely additive -- OnTouchListener returns false so
 * the view's own click/ripple handling still fires normally.
 */
fun View.applyPressScale(pressedScale: Float = 0.96f) {
    setOnTouchListener { v, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.animate().scaleX(pressedScale).scaleY(pressedScale).setDuration(90).start()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.animate().scaleX(1f).scaleY(1f).setDuration(150)
                    .setInterpolator(OvershootInterpolator()).start()
            }
        }
        false
    }
}

/** Staggered fade + slide-up entrance, used for list/grid items appearing on screen. */
fun View.animateStaggeredEntrance(index: Int, staggerMs: Long = 45L) {
    alpha = 0f
    translationY = 24f
    animate()
        .alpha(1f)
        .translationY(0f)
        .setStartDelay(index * staggerMs)
        .setDuration(260)
        .start()
}
