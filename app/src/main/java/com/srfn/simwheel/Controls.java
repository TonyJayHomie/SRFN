package com.srfn.simwheel;

/** Immutable snapshot of the control state sent to the receiver. */
public final class Controls {
    public final float steer;     // -1..+1
    public final float throttle;  // 0..1
    public final float brake;     // 0..1
    public final int buttons;     // bit0=A .. bit7=Select

    public Controls(float steer, float throttle, float brake, int buttons) {
        this.steer = steer;
        this.throttle = throttle;
        this.brake = brake;
        this.buttons = buttons;
    }
}
