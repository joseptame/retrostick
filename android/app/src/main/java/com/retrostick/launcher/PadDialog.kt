package com.retrostick.launcher

import android.app.AlertDialog
import android.app.Dialog
import android.view.KeyEvent

/**
 * Android reports face buttons by position (BUTTON_A = bottom, BUTTON_B = right). Nintendo confirms with
 * the right button (labelled A) and goes back with the bottom one (B), so those two are swapped.
 */
fun semanticPadKey(code: Int, style: PadStyle) = when {
    style != PadStyle.NINTENDO -> code
    code == KeyEvent.KEYCODE_BUTTON_A -> KeyEvent.KEYCODE_BUTTON_B
    code == KeyEvent.KEYCODE_BUTTON_B -> KeyEvent.KEYCODE_BUTTON_A
    else -> code
}

/**
 * Standard dialogs only see the raw key codes: on Android 9 the DualSense's Cross arrives as BUTTON_B, which
 * a dialog takes as "back" and closes. This makes the confirm button click (as DPAD_CENTER) and the back
 * button cancel, with the same mapping as the home screen.
 */
fun Dialog.padKeys() {
    setOnKeyListener { dialog, _, event ->
        if (!StickStatus.isGamepad(event.device)) return@setOnKeyListener false
        val key = semanticPadKey(StickStatus.standardKey(event.device, event.keyCode), StickStatus.padStyle(event.device))
        when (key) {
            KeyEvent.KEYCODE_BUTTON_A -> {
                window?.superDispatchKeyEvent(
                    KeyEvent(event.downTime, event.eventTime, event.action, KeyEvent.KEYCODE_DPAD_CENTER, event.repeatCount),
                )
                true
            }
            KeyEvent.KEYCODE_BUTTON_B -> {
                if (event.action == KeyEvent.ACTION_UP) dialog.cancel()
                true
            }
            else -> false
        }
    }
}

/** show() for dialogs used with a controller (see [padKeys]). */
fun AlertDialog.Builder.showForPad(): AlertDialog = create().also {
    it.padKeys()
    it.show()
}
