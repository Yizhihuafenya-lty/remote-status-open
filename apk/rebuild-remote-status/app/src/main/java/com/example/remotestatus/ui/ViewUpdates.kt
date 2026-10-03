package com.example.remotestatus.ui

import android.widget.TextView

/**
 * The dashboard refreshes every second. Writing identical text back into a
 * TextView still invalidates the view and allocates a String on every tick,
 * so text is only assigned when it really changed.
 */
fun TextView.setTextIfChanged(value: CharSequence) {
    if (text?.toString() != value.toString()) {
        text = value
    }
}
