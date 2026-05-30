package com.srfn.simwheel;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

/**
 * Turns the phone into a steering wheel.
 *
 * The phone is held in landscape like a wheel and rotated in the plane of the
 * screen. We track the direction of gravity inside that screen plane
 * (atan2 of the X/Y gravity components), which rotates linearly with the wheel
 * angle. {@link Sensor#TYPE_GRAVITY} (a gyro + accelerometer fusion on most
 * devices) keeps it responsive and drift free; we fall back to a low-pass
 * filtered accelerometer when no gravity sensor exists.
 *
 * Output {@link #getSteer()} is -1 (full left) .. +1 (full right). Call
 * {@link #calibrateCenter()} while holding neutral to define 0.
 */
public final class SteeringSensor implements SensorEventListener {

    private final SensorManager sensorManager;
    private final Sensor gravitySensor;
    private final Sensor accelSensor;

    private volatile float steer = 0f;
    private volatile float angleDeg = 0f;
    private volatile boolean flat = false;

    private volatile float maxAngleDeg = 90f;   // physical rotation for full lock
    private volatile float deadzone = 0.03f;    // fraction of travel
    private volatile boolean invert = false;

    private final float[] gravity = new float[] {0f, 0f, 9.81f};
    private boolean haveGravity = false;
    private double centerRad = 0.0;
    private float smoothed = 0f;

    public SteeringSensor(Context context) {
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    }

    public float getSteer() { return steer; }
    public float getAngleDeg() { return angleDeg; }
    public boolean isFlat() { return flat; }

    public void setMaxAngleDeg(float v) { maxAngleDeg = v; }
    public float getMaxAngleDeg() { return maxAngleDeg; }
    public void setDeadzone(float v) { deadzone = v; }
    public float getDeadzone() { return deadzone; }
    public void setInvert(boolean v) { invert = v; }
    public boolean getInvert() { return invert; }

    public boolean isAvailable() { return gravitySensor != null || accelSensor != null; }

    public void start() {
        Sensor s = gravitySensor != null ? gravitySensor : accelSensor;
        if (s != null) {
            sensorManager.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    public void stop() {
        sensorManager.unregisterListener(this);
    }

    /** Capture the current orientation as the wheel's neutral centre. */
    public void calibrateCenter() {
        if (haveGravity) {
            centerRad = Math.atan2(gravity[0], gravity[1]);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_GRAVITY) {
            gravity[0] = event.values[0];
            gravity[1] = event.values[1];
            gravity[2] = event.values[2];
        } else {
            // Low-pass the raw accelerometer to estimate gravity.
            float a = 0.8f;
            gravity[0] = a * gravity[0] + (1 - a) * event.values[0];
            gravity[1] = a * gravity[1] + (1 - a) * event.values[1];
            gravity[2] = a * gravity[2] + (1 - a) * event.values[2];
        }
        haveGravity = true;
        compute();
    }

    private void compute() {
        float gx = gravity[0];
        float gy = gravity[1];

        // If the in-plane gravity component is tiny the phone is flat and the
        // angle is meaningless, so hold the last value instead of jittering.
        double inPlane = Math.hypot(gx, gy);
        flat = inPlane < 1.5;
        if (flat) return;

        double delta = Math.atan2(gx, gy) - centerRad;
        while (delta > Math.PI) delta -= 2 * Math.PI;
        while (delta < -Math.PI) delta += 2 * Math.PI;
        angleDeg = (float) Math.toDegrees(delta);

        double maxRad = Math.toRadians(Math.max(1.0, maxAngleDeg));
        float s = (float) (delta / maxRad);
        if (invert) s = -s;
        s = clamp(s, -1f, 1f);

        // Centre deadzone, rescaled so travel still reaches the full range.
        float dz = clamp(deadzone, 0f, 0.45f);
        if (Math.abs(s) <= dz) {
            s = 0f;
        } else {
            s = (s - Math.signum(s) * dz) / (1f - dz);
        }

        // Light exponential smoothing to take the edge off sensor noise.
        smoothed += (s - smoothed) * 0.5f;
        steer = clamp(smoothed, -1f, 1f);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { /* unused */ }
}
