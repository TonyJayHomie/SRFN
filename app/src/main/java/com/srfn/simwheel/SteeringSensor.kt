package com.srfn.simwheel

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sign

/**
 * Turns the phone into a steering wheel.
 *
 * The phone is held in landscape like a wheel and rotated in the plane of the
 * screen. We track the direction of gravity inside that screen plane
 * (atan2 of the X/Y gravity components) which rotates linearly with the wheel
 * angle. Using [Sensor.TYPE_GRAVITY] (a gyro + accelerometer fusion on most
 * devices) keeps it responsive and drift-free; we fall back to a low-pass
 * filtered accelerometer if a dedicated gravity sensor is missing.
 *
 * Output [steer] is in the receiver's range of -1.0 (full left) .. +1.0 (full
 * right). Call [calibrateCenter] while holding the wheel in the neutral
 * position to define 0.
 */
class SteeringSensor(context: Context) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gravitySensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val accelSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    /** Latest steering value, -1.0 .. +1.0. */
    @Volatile
    var steer: Float = 0f
        private set

    /** Current wheel angle relative to the calibrated centre, in degrees. */
    @Volatile
    var angleDeg: Float = 0f
        private set

    /** True when the phone is lying too flat to read a reliable wheel angle. */
    @Volatile
    var flat: Boolean = false
        private set

    /** Physical rotation (degrees) that corresponds to full lock. */
    @Volatile
    var maxAngleDeg: Float = 90f

    /** Centre deadzone as a fraction of full travel, 0.0 .. ~0.3. */
    @Volatile
    var deadzone: Float = 0.03f

    /** Flip left/right. */
    @Volatile
    var invert: Boolean = false

    private val gravity = floatArrayOf(0f, 0f, 9.81f)
    private var haveGravity = false
    private var centerRad = 0.0
    private var smoothed = 0f

    val isAvailable: Boolean get() = gravitySensor != null || accelSensor != null

    fun start() {
        val sensor = gravitySensor ?: accelSensor ?: return
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    /** Capture the current orientation as the wheel's neutral centre. */
    fun calibrateCenter() {
        if (haveGravity) {
            centerRad = atan2(gravity[0].toDouble(), gravity[1].toDouble())
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_GRAVITY) {
            gravity[0] = event.values[0]
            gravity[1] = event.values[1]
            gravity[2] = event.values[2]
        } else {
            // Low-pass the raw accelerometer to estimate gravity.
            val a = 0.8f
            gravity[0] = a * gravity[0] + (1 - a) * event.values[0]
            gravity[1] = a * gravity[1] + (1 - a) * event.values[1]
            gravity[2] = a * gravity[2] + (1 - a) * event.values[2]
        }
        haveGravity = true
        compute()
    }

    private fun compute() {
        val gx = gravity[0]
        val gy = gravity[1]

        // If the in-plane gravity component is tiny the phone is flat and the
        // angle is meaningless, so hold the last value instead of jittering.
        val inPlane = hypot(gx.toDouble(), gy.toDouble())
        flat = inPlane < 1.5
        if (flat) return

        var delta = atan2(gx.toDouble(), gy.toDouble()) - centerRad
        // Normalise to (-pi, pi].
        while (delta > PI) delta -= 2 * PI
        while (delta < -PI) delta += 2 * PI
        angleDeg = Math.toDegrees(delta).toFloat()

        val maxRad = Math.toRadians(maxAngleDeg.toDouble().coerceAtLeast(1.0))
        var s = (delta / maxRad).toFloat()
        if (invert) s = -s
        s = s.coerceIn(-1f, 1f)

        // Centre deadzone, rescaled so travel still reaches the full range.
        val dz = deadzone.coerceIn(0f, 0.45f)
        s = if (abs(s) <= dz) 0f else (s - sign(s) * dz) / (1f - dz)

        // Light exponential smoothing to take the edge off sensor noise.
        smoothed += (s - smoothed) * 0.5f
        steer = smoothed.coerceIn(-1f, 1f)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* unused */ }
}
