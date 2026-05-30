package com.srfn.simwheel

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.max

/**
 * Reads a Bluetooth (or USB) game controller.
 *
 * Analog triggers become the pedals: the right trigger is reported as
 * [rightTrigger] and the left trigger as [leftTrigger], each 0.0 .. 1.0.
 * Different controllers expose triggers on different axes, so we take the max
 * of the common candidates (LTRIGGER/RTRIGGER and BRAKE/GAS).
 *
 * Face/shoulder/menu buttons are packed into an 8-bit [buttons] mask matching
 * the receiver's layout (bit 0 = A, bit 1 = B, ... bit 7 = Select).
 */
class GamepadManager {

    @Volatile
    var rightTrigger: Float = 0f
        private set

    @Volatile
    var leftTrigger: Float = 0f
        private set

    @Volatile
    var buttons: Int = 0
        private set

    @Volatile
    var lastDeviceName: String? = null
        private set

    /** Feed a generic motion event. Returns true if it came from a gamepad. */
    fun onMotion(event: MotionEvent): Boolean {
        if (!isGamepad(event.device)) return false

        val rt = max(
            event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
            event.getAxisValue(MotionEvent.AXIS_GAS)
        )
        val lt = max(
            event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
            event.getAxisValue(MotionEvent.AXIS_BRAKE)
        )
        rightTrigger = rt.coerceIn(0f, 1f)
        leftTrigger = lt.coerceIn(0f, 1f)
        event.device?.name?.let { lastDeviceName = it }
        return true
    }

    /** Feed a key event. Returns true if it was a recognised gamepad button. */
    fun onKey(keyCode: Int, event: KeyEvent): Boolean {
        if (!isGamepad(event.device) && !KeyEvent.isGamepadButton(keyCode)) return false
        val bit = bitFor(keyCode) ?: return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> buttons = buttons or (1 shl bit)
            KeyEvent.ACTION_UP -> buttons = buttons and (1 shl bit).inv()
        }
        event.device?.name?.let { lastDeviceName = it }
        return true
    }

    private fun isGamepad(device: InputDevice?): Boolean {
        val sources = device?.sources ?: return false
        val joystick = sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
        val gamepad = sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD
        return joystick || gamepad
    }

    /** Map a controller key to its bit in the receiver's button mask. */
    private fun bitFor(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> 0
        KeyEvent.KEYCODE_BUTTON_B -> 1
        KeyEvent.KEYCODE_BUTTON_X -> 2
        KeyEvent.KEYCODE_BUTTON_Y -> 3
        KeyEvent.KEYCODE_BUTTON_L1 -> 4
        KeyEvent.KEYCODE_BUTTON_R1 -> 5
        KeyEvent.KEYCODE_BUTTON_START -> 6
        KeyEvent.KEYCODE_BUTTON_SELECT -> 7
        else -> null
    }
}
