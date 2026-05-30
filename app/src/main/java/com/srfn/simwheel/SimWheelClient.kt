package com.srfn.simwheel

import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.net.SocketTimeoutException

/** One immutable snapshot of the control state. [steer] is normalised -1..1. */
data class Controls(
    val steer: Float,
    val throttle: Float,
    val brake: Float,
    val buttons: Int
)

/**
 * Speaks the SimWheel PC receiver's wire protocol (reverse-engineered from
 * Receiver.cpp).
 *
 *  * Transport: UDP, the receiver listens on [PORT] (4567) for everything.
 *  * Encoding:  JSON objects.
 *  * Discovery: send {"type":"discover","phoneName":"..."}; the PC answers
 *              {"type":"discover_reply","name":"<pc>","connection":"..."} from
 *              [PORT] back to our source port.
 *  * Telemetry: {"steering":<deg>,"throttle":0..1,"brake":0..1,"dx":0,"dy":0,
 *               "1":bool,..,"8":bool}.  "steering" is an ANGLE IN DEGREES; the
 *              PC divides it by its configured range (default 900). Every packet
 *              must carry either "zaxis" or both "dx"/"dy" or the receiver
 *              throws, so we always send dx:0/dy:0. Button keys are the
 *              stringified vJoy button numbers.
 *
 * NOTE: the receiver asks the user to approve this device (y/n on the PC
 * console) the first time a packet arrives from the phone's IP.
 */
class SimWheelClient(private val provider: () -> Controls) {

    companion object {
        const val PORT = 4567
        private const val BUTTON_COUNT = 8
    }

    /** Resolved PC address (from a discovery reply or manual entry). */
    @Volatile
    var pcIp: String? = null

    /** Human-readable PC name from the last discovery reply. */
    @Volatile
    var pcName: String? = null

    /** Degrees of virtual rotation sent at full lock. Match the PC's range. */
    @Volatile
    var rangeDeg: Float = 900f

    /** Telemetry send rate. */
    @Volatile
    var rateHz: Int = 60

    /** When true the send loop streams telemetry to [pcIp]. */
    @Volatile
    var streaming: Boolean = false

    @Volatile
    var packetsSent: Long = 0
        private set

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    var lastReplyAt: Long = 0
        private set

    private val phoneName: String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: DatagramSocket? = null
    private var receiveJob: Job? = null
    private var sendJob: Job? = null

    /** Open the socket and start the receive + send loops. Idempotent. */
    fun start() {
        if (socket != null) return
        try {
            val s = DatagramSocket()
            s.broadcast = true
            s.soTimeout = 500
            socket = s
        } catch (e: Exception) {
            lastError = e.message
            return
        }
        receiveJob = scope.launch { receiveLoop() }
        sendJob = scope.launch { sendLoop() }
    }

    fun stop() {
        receiveJob?.cancel(); receiveJob = null
        sendJob?.cancel(); sendJob = null
        socket?.close(); socket = null
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }

    private suspend fun receiveLoop() {
        val buf = ByteArray(1024)
        while (coroutineContext.isActive) {
            val s = socket ?: break
            try {
                val packet = DatagramPacket(buf, buf.size)
                s.receive(packet)
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
                handleReply(text, packet.address)
            } catch (_: SocketTimeoutException) {
                // idle, loop again (soTimeout makes this cancellable)
            } catch (_: SocketException) {
                break // socket closed
            } catch (e: Exception) {
                lastError = e.message
            }
        }
    }

    private fun handleReply(text: String, from: InetAddress) {
        try {
            val j = JSONObject(text)
            if (j.optString("type") == "discover_reply") {
                pcName = j.optString("name", from.hostAddress)
                pcIp = from.hostAddress
                lastReplyAt = System.currentTimeMillis()
            }
        } catch (_: Exception) {
            // not JSON we care about
        }
    }

    private suspend fun sendLoop() {
        while (coroutineContext.isActive) {
            val s = socket
            val ip = pcIp
            if (s != null && streaming && ip != null) {
                try {
                    val payload = telemetryJson().toByteArray(Charsets.UTF_8)
                    s.send(DatagramPacket(payload, payload.size, InetAddress.getByName(ip), PORT))
                    packetsSent++
                    lastError = null
                } catch (e: Exception) {
                    lastError = e.message ?: e.javaClass.simpleName
                }
            }
            delay(1000L / rateHz.coerceIn(1, 200))
        }
    }

    private fun telemetryJson(): String {
        val c = provider()
        val o = JSONObject()
        o.put("steering", r3(c.steer * rangeDeg))
        o.put("throttle", r3(c.throttle))
        o.put("brake", r3(c.brake))
        // Required by the receiver (the no-zaxis branch reads dx/dy). 0,0 is inert.
        o.put("dx", 0)
        o.put("dy", 0)
        for (i in 0 until BUTTON_COUNT) {
            o.put((i + 1).toString(), c.buttons and (1 shl i) != 0)
        }
        return o.toString()
    }

    /**
     * Send discovery probes and wait for the PC to answer. Because the receiver
     * requires the user to approve the device on the PC console, this waits a
     * while. Returns true once a reply has been seen.
     */
    suspend fun discover(timeoutMs: Int = 15000): Boolean = withContext(Dispatchers.IO) {
        val s = socket ?: return@withContext false
        val payload = JSONObject()
            .put("type", "discover")
            .put("phoneName", phoneName)
            .toString()
            .toByteArray(Charsets.UTF_8)

        val start = System.currentTimeMillis()
        val before = lastReplyAt
        var nextSend = 0L
        while (System.currentTimeMillis() - start < timeoutMs) {
            val now = System.currentTimeMillis()
            if (now >= nextSend) {
                val targets = broadcastAddresses().toMutableList()
                pcIp?.let { runCatching { targets.add(InetAddress.getByName(it)) } }
                for (addr in targets) {
                    runCatching {
                        s.send(DatagramPacket(payload, payload.size, addr, PORT))
                    }
                }
                nextSend = now + 1200
            }
            if (lastReplyAt > before && pcIp != null) return@withContext true
            delay(150)
        }
        lastReplyAt > before && pcIp != null
    }

    private fun broadcastAddresses(): List<InetAddress> {
        val list = mutableListOf<InetAddress>()
        runCatching { list.add(InetAddress.getByName("255.255.255.255")) }
        runCatching {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (ia in nif.interfaceAddresses) {
                    ia.broadcast?.let { list.add(it) }
                }
            }
        }
        return list
    }

    private fun r3(v: Float): Double = Math.round(v * 1000.0) / 1000.0
}
