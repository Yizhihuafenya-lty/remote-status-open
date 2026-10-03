package com.example.remotestatus.core

import android.content.res.ColorStateList
import android.widget.ProgressBar
import com.example.remotestatus.R

/** Shared appearance and animated updates for the usage gauges. */
object Gauges {
    fun colorResource(percent: Double): Int = when {
        percent >= 90.0 -> R.color.red
        percent >= 70.0 -> R.color.orange
        else -> R.color.green
    }

    /**
     * Applies a 0..100 percentage to a horizontal progress bar.
     * Progress is animated so a 1 s refresh reads as motion, not as a jump.
     * Pass [fixedColorResource] for gauges that are not a load percentage
     * (for example bandwidth relative to its session peak).
     */
    fun apply(bar: ProgressBar, percent: Double, fixedColorResource: Int? = null, animate: Boolean = true) {
        val value = percent.coerceIn(0.0, 100.0)
        val color = bar.context.getColor(fixedColorResource ?: colorResource(value))
        bar.progressTintList = ColorStateList.valueOf(color)
        bar.progressBackgroundTintList = ColorStateList.valueOf(bar.context.getColor(R.color.divider))

        val target = Math.round(value * 10).toInt()
        if (bar.max != MAX) bar.max = MAX
        if (animate && bar.progress != target) {
            bar.setProgress(target, true)
        } else {
            bar.progress = target
        }
    }

    private const val MAX = 1_000
}
