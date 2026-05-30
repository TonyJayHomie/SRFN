package com.srfn.simwheel

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.srfn.simwheel.databinding.ActivityMainBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    private lateinit var steering: SteeringSensor
    private val gamepad = GamepadManager()
    private lateinit var client: SimWheelClient

    @Volatile
    private var swapPedals = false
    private var streaming = false
    private var uiJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = getSharedPreferences("simwheel", Context.MODE_PRIVATE)
        steering = SteeringSensor(this)
        client = SimWheelClient {
            val t = gamepad.rightTrigger
            val b = gamepad.leftTrigger
            Controls(
                steer = steering.steer,
                throttle = if (swapPedals) b else t,
                brake = if (swapPedals) t else b,
                buttons = gamepad.buttons
            )
        }

        loadSettings()
        wireControls()
    }

    // ---- Settings load / save -------------------------------------------

    private fun loadSettings() {
        steering.maxAngleDeg = prefs.getFloat("maxAngle", 90f)
        steering.deadzone = prefs.getFloat("deadzone", 0.03f)
        steering.invert = prefs.getBoolean("invert", false)
        client.rateHz = prefs.getInt("rate", 60)
        client.rangeDeg = prefs.getFloat("range", 900f)
        swapPedals = prefs.getBoolean("swap", false)
        client.pcIp = prefs.getString("pcIp", null)

        binding.ipInput.setText(client.pcIp ?: "")
        binding.invertSwitch.isChecked = steering.invert
        binding.swapSwitch.isChecked = swapPedals
        binding.sensitivitySeek.progress = (steering.maxAngleDeg - 20f).roundToInt()
        binding.deadzoneSeek.progress = (steering.deadzone * 100f).roundToInt()
        binding.rateSeek.progress = client.rateHz - 10
        binding.rangeSeek.progress = (client.rangeDeg - 90f).roundToInt()
        updateSettingLabels()
    }

    private fun save(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
    }

    private fun updateSettingLabels() {
        binding.sensitivityLabel.text =
            getString(R.string.lbl_sensitivity, steering.maxAngleDeg.roundToInt())
        binding.deadzoneLabel.text =
            getString(R.string.lbl_deadzone, (steering.deadzone * 100f).roundToInt())
        binding.rateLabel.text = getString(R.string.lbl_rate, client.rateHz)
        binding.rangeLabel.text = getString(R.string.lbl_range, client.rangeDeg.roundToInt())
    }

    // ---- UI wiring -------------------------------------------------------

    private fun wireControls() {
        binding.calibrateButton.setOnClickListener {
            steering.calibrateCenter()
            toast(getString(R.string.toast_calibrated))
        }

        binding.connectButton.setOnClickListener {
            val ip = binding.ipInput.text.toString().trim()
            if (ip.isEmpty()) {
                toast(getString(R.string.toast_enter_ip))
            } else {
                client.pcIp = ip
                save { putString("pcIp", ip) }
                toast(getString(R.string.toast_target, ip))
                runDiscovery() // ping the PC so it shows the approval prompt
            }
        }

        binding.discoverButton.setOnClickListener { runDiscovery() }

        binding.startButton.setOnClickListener { toggleStreaming() }

        binding.invertSwitch.setOnCheckedChangeListener { _, checked ->
            steering.invert = checked
            save { putBoolean("invert", checked) }
        }
        binding.swapSwitch.setOnCheckedChangeListener { _, checked ->
            swapPedals = checked
            save { putBoolean("swap", checked) }
        }

        binding.sensitivitySeek.setOnSeekBarChangeListener(object : SimpleSeek() {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                steering.maxAngleDeg = (p + 20).toFloat() // 20..180 deg
                updateSettingLabels()
                save { putFloat("maxAngle", steering.maxAngleDeg) }
            }
        })
        binding.deadzoneSeek.setOnSeekBarChangeListener(object : SimpleSeek() {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                steering.deadzone = p / 100f // 0..0.30
                updateSettingLabels()
                save { putFloat("deadzone", steering.deadzone) }
            }
        })
        binding.rateSeek.setOnSeekBarChangeListener(object : SimpleSeek() {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                client.rateHz = p + 10 // 10..120 Hz
                updateSettingLabels()
                save { putInt("rate", client.rateHz) }
            }
        })
        binding.rangeSeek.setOnSeekBarChangeListener(object : SimpleSeek() {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                client.rangeDeg = (p + 90).toFloat() // 90..2520 deg, match the PC
                updateSettingLabels()
                save { putFloat("range", client.rangeDeg) }
            }
        })
    }

    private fun runDiscovery() {
        binding.discoverButton.isEnabled = false
        binding.statusText.text = getString(R.string.status_searching)
        lifecycleScope.launch {
            val found = client.discover()
            binding.discoverButton.isEnabled = true
            if (found) {
                val ip = client.pcIp
                if (ip != null) {
                    binding.ipInput.setText(ip)
                    save { putString("pcIp", ip) }
                }
                toast(getString(R.string.toast_found, client.pcName ?: client.pcIp ?: "PC"))
            } else {
                toast(getString(R.string.toast_not_found))
            }
        }
    }

    private fun toggleStreaming() {
        if (streaming) {
            streaming = false
            client.streaming = false
        } else {
            val ip = binding.ipInput.text.toString().trim()
            if (ip.isNotEmpty()) {
                client.pcIp = ip
                save { putString("pcIp", ip) }
            }
            if (client.pcIp.isNullOrEmpty()) {
                toast(getString(R.string.toast_no_target))
                return
            }
            streaming = true
            client.streaming = true
        }
        binding.startButton.text =
            getString(if (streaming) R.string.btn_stop else R.string.btn_start)
    }

    // ---- Gamepad input capture ------------------------------------------

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_MOVE && gamepad.onMotion(ev)) return true
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        if (gamepad.onKey(ev.keyCode, ev)) return true
        return super.dispatchKeyEvent(ev)
    }

    // ---- Lifecycle -------------------------------------------------------

    override fun onResume() {
        super.onResume()
        steering.start()
        client.start()
        client.streaming = streaming
        startUiLoop()
    }

    override fun onPause() {
        super.onPause()
        stopUiLoop()
        client.streaming = false
        client.stop()
        steering.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        client.shutdown()
    }

    private fun startUiLoop() {
        stopUiLoop()
        uiJob = lifecycleScope.launch {
            while (isActive) {
                refreshUi()
                delay(40) // ~25 Hz UI refresh
            }
        }
    }

    private fun stopUiLoop() {
        uiJob?.cancel()
        uiJob = null
    }

    private fun refreshUi() {
        val steer = steering.steer
        val throttle = if (swapPedals) gamepad.leftTrigger else gamepad.rightTrigger
        val brake = if (swapPedals) gamepad.rightTrigger else gamepad.leftTrigger

        binding.steerBar.progress = ((steer + 1f) * 100f).roundToInt()
        binding.throttleBar.progress = (throttle * 100f).roundToInt()
        binding.brakeBar.progress = (brake * 100f).roundToInt()

        binding.steerValue.text = String.format(
            Locale.US, "Steer  %+.2f  (%+d°)", steer, steering.angleDeg.roundToInt()
        )
        binding.throttleValue.text = String.format(Locale.US, "Throttle  %.2f", throttle)
        binding.brakeValue.text = String.format(Locale.US, "Brake  %.2f", brake)
        binding.buttonsValue.text =
            getString(R.string.lbl_buttons, gamepad.buttons, buttonString(gamepad.buttons))
        binding.deviceText.text =
            getString(R.string.lbl_pad, gamepad.lastDeviceName ?: getString(R.string.none))

        binding.flatWarning.visibility = if (steering.flat) View.VISIBLE else View.GONE

        val ip = client.pcIp
        val name = client.pcName ?: ip
        binding.statusText.text = when {
            client.streaming && ip != null -> getString(R.string.status_streaming, name)
            ip != null -> getString(R.string.status_ready, name)
            else -> getString(R.string.status_no_pc)
        }

        val err = client.lastError
        binding.packetsText.text = if (err != null && client.streaming) {
            getString(R.string.lbl_error, err)
        } else {
            getString(R.string.lbl_packets, client.packetsSent)
        }
    }

    private fun buttonString(mask: Int): String {
        if (mask == 0) return "-"
        val names = arrayOf("A", "B", "X", "Y", "L1", "R1", "Start", "Select")
        val sb = StringBuilder()
        for (i in names.indices) {
            if (mask and (1 shl i) != 0) {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(names[i])
            }
        }
        return sb.toString()
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /** SeekBar listener that only cares about progress changes. */
    private abstract class SimpleSeek : SeekBar.OnSeekBarChangeListener {
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }
}
