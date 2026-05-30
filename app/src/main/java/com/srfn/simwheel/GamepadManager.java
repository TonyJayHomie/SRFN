package com.srfn.simwheel;

import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

/**
 * Reads a Bluetooth (or USB) game controller.
 *
 * Analog triggers become the pedals: the right trigger is {@link #getRightTrigger()}
 * and the left trigger {@link #getLeftTrigger()}, each 0..1. Different
 * controllers expose triggers on different axes, so we take the max of the
 * common candidates (LTRIGGER/RTRIGGER and BRAKE/GAS).
 *
 * Face/shoulder/menu buttons are packed into an 8-bit mask
 * (bit0=A .. bit7=Select).
 */
public final class GamepadManager {

    private volatile float rightTrigger = 0f;
    private volatile float leftTrigger = 0f;
    private volatile int buttons = 0;
    private volatile String lastDeviceName = null;

    public float getRightTrigger() { return rightTrigger; }
    public float getLeftTrigger() { return leftTrigger; }
    public int getButtons() { return buttons; }
    public String getLastDeviceName() { return lastDeviceName; }

    /** Feed a generic motion event. Returns true if it came from a gamepad. */
    public boolean onMotion(MotionEvent event) {
        if (!isGamepad(event.getDevice())) return false;

        float rt = Math.max(
                event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
                event.getAxisValue(MotionEvent.AXIS_GAS));
        float lt = Math.max(
                event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
                event.getAxisValue(MotionEvent.AXIS_BRAKE));
        rightTrigger = clamp01(rt);
        leftTrigger = clamp01(lt);
        InputDevice d = event.getDevice();
        if (d != null && d.getName() != null) lastDeviceName = d.getName();
        return true;
    }

    /** Feed a key event. Returns true if it was a recognised gamepad button. */
    public boolean onKey(int keyCode, KeyEvent event) {
        if (!isGamepad(event.getDevice()) && !KeyEvent.isGamepadButton(keyCode)) return false;
        int bit = bitFor(keyCode);
        if (bit < 0) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            buttons |= (1 << bit);
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            buttons &= ~(1 << bit);
        }
        InputDevice d = event.getDevice();
        if (d != null && d.getName() != null) lastDeviceName = d.getName();
        return true;
    }

    private static boolean isGamepad(InputDevice device) {
        if (device == null) return false;
        int sources = device.getSources();
        boolean joystick = (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
        boolean gamepad = (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD;
        return joystick || gamepad;
    }

    /** Map a controller key to its bit in the receiver's button mask, or -1. */
    private static int bitFor(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A: return 0;
            case KeyEvent.KEYCODE_BUTTON_B: return 1;
            case KeyEvent.KEYCODE_BUTTON_X: return 2;
            case KeyEvent.KEYCODE_BUTTON_Y: return 3;
            case KeyEvent.KEYCODE_BUTTON_L1: return 4;
            case KeyEvent.KEYCODE_BUTTON_R1: return 5;
            case KeyEvent.KEYCODE_BUTTON_START: return 6;
            case KeyEvent.KEYCODE_BUTTON_SELECT: return 7;
            default: return -1;
        }
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
