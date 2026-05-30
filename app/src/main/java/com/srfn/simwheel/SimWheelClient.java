package com.srfn.simwheel;

import android.os.Build;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * Speaks the SimWheel PC receiver's wire protocol (reverse-engineered from
 * Receiver.cpp).
 *
 * <ul>
 *   <li>Transport: UDP, the receiver listens on {@link #PORT} (4567) for everything.</li>
 *   <li>Encoding: JSON objects.</li>
 *   <li>Discovery: send {"type":"discover","phoneName":"..."}; the PC answers
 *       {"type":"discover_reply","name":"&lt;pc&gt;","connection":"..."} from
 *       {@link #PORT} back to our source port.</li>
 *   <li>Telemetry: {"steering":&lt;deg&gt;,"throttle":0..1,"brake":0..1,"dx":0,
 *       "dy":0,"1":bool,..,"8":bool}. "steering" is an ANGLE IN DEGREES; the PC
 *       divides it by its configured range (default 900). Every packet must
 *       carry either "zaxis" or both "dx"/"dy" or the receiver throws, so we
 *       always send dx:0/dy:0. Button keys are stringified vJoy numbers.</li>
 * </ul>
 *
 * NOTE: the receiver asks the user to approve this device (y/n on the PC
 * console) the first time a packet arrives from the phone's IP.
 */
public final class SimWheelClient {

    public interface ControlsProvider {
        Controls get();
    }

    public static final int PORT = 4567;
    private static final int BUTTON_COUNT = 8;
    private static final Charset ASCII = Charset.forName("US-ASCII");

    private final ControlsProvider provider;
    private final String phoneName;

    private volatile String pcIp = null;
    private volatile String pcName = null;
    private volatile float rangeDeg = 900f;
    private volatile int rateHz = 60;
    private volatile boolean streaming = false;

    private volatile long packetsSent = 0;
    private volatile String lastError = null;
    private volatile long lastReplyAt = 0;

    private DatagramSocket socket;
    private Thread receiveThread;
    private Thread sendThread;
    private volatile boolean running = false;

    public SimWheelClient(ControlsProvider provider) {
        this.provider = provider;
        this.phoneName = (Build.MANUFACTURER + " " + Build.MODEL).trim();
    }

    // ---- configuration / status accessors -------------------------------

    public void setPcIp(String ip) { pcIp = ip; }
    public String getPcIp() { return pcIp; }
    public String getPcName() { return pcName; }
    public void setRangeDeg(float v) { rangeDeg = v; }
    public float getRangeDeg() { return rangeDeg; }
    public void setRateHz(int v) { rateHz = v; }
    public int getRateHz() { return rateHz; }
    public void setStreaming(boolean v) { streaming = v; }
    public boolean isStreaming() { return streaming; }
    public long getPacketsSent() { return packetsSent; }
    public String getLastError() { return lastError; }

    // ---- lifecycle ------------------------------------------------------

    /** Open the socket and start the receive + send loops. Idempotent. */
    public synchronized void start() {
        if (running) return;
        try {
            DatagramSocket s = new DatagramSocket();
            s.setBroadcast(true);
            s.setSoTimeout(500);
            socket = s;
        } catch (Exception e) {
            lastError = e.getMessage();
            return;
        }
        running = true;
        receiveThread = new Thread(new Runnable() {
            public void run() { receiveLoop(); }
        }, "sw-recv");
        sendThread = new Thread(new Runnable() {
            public void run() { sendLoop(); }
        }, "sw-send");
        receiveThread.start();
        sendThread.start();
    }

    public synchronized void stop() {
        running = false;
        if (receiveThread != null) { receiveThread.interrupt(); receiveThread = null; }
        if (sendThread != null) { sendThread.interrupt(); sendThread = null; }
        if (socket != null) { socket.close(); socket = null; }
    }

    public void shutdown() {
        stop();
    }

    // ---- loops ----------------------------------------------------------

    private void receiveLoop() {
        byte[] buf = new byte[1024];
        while (running) {
            DatagramSocket s = socket;
            if (s == null) break;
            try {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                s.receive(packet);
                String text = new String(packet.getData(), 0, packet.getLength(), ASCII).trim();
                handleReply(text, packet.getAddress());
            } catch (SocketTimeoutException e) {
                // idle, loop again
            } catch (SocketException e) {
                break; // socket closed
            } catch (Exception e) {
                lastError = e.getMessage();
            }
        }
    }

    private void handleReply(String text, InetAddress from) {
        try {
            JSONObject j = new JSONObject(text);
            if ("discover_reply".equals(j.optString("type"))) {
                pcName = j.optString("name", from.getHostAddress());
                pcIp = from.getHostAddress();
                lastReplyAt = System.currentTimeMillis();
            }
        } catch (Exception e) {
            // not JSON we care about
        }
    }

    private void sendLoop() {
        while (running) {
            DatagramSocket s = socket;
            String ip = pcIp;
            if (s != null && streaming && ip != null) {
                try {
                    byte[] payload = telemetryJson().getBytes(ASCII);
                    s.send(new DatagramPacket(payload, payload.length,
                            InetAddress.getByName(ip), PORT));
                    packetsSent++;
                    lastError = null;
                } catch (Exception e) {
                    lastError = e.getMessage() != null ? e.getMessage()
                            : e.getClass().getSimpleName();
                }
            }
            int hz = rateHz < 1 ? 1 : (rateHz > 200 ? 200 : rateHz);
            try {
                Thread.sleep(1000L / hz);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private String telemetryJson() throws Exception {
        Controls c = provider.get();
        JSONObject o = new JSONObject();
        o.put("steering", r3(c.steer * rangeDeg));
        o.put("throttle", r3(c.throttle));
        o.put("brake", r3(c.brake));
        // Required by the receiver (the no-zaxis branch reads dx/dy). 0,0 is inert.
        o.put("dx", 0);
        o.put("dy", 0);
        for (int i = 0; i < BUTTON_COUNT; i++) {
            o.put(Integer.toString(i + 1), (c.buttons & (1 << i)) != 0);
        }
        return o.toString();
    }

    // ---- discovery ------------------------------------------------------

    /**
     * Blocking discovery: broadcast probes and wait for the PC to answer.
     * Call off the UI thread. Returns true once a reply is seen.
     */
    public boolean discover(int timeoutMs) {
        DatagramSocket s = socket;
        if (s == null) return false;
        byte[] payload;
        try {
            payload = new JSONObject()
                    .put("type", "discover")
                    .put("phoneName", phoneName)
                    .toString().getBytes(ASCII);
        } catch (Exception e) {
            return false;
        }

        long start = System.currentTimeMillis();
        long before = lastReplyAt;
        long nextSend = 0;
        while (System.currentTimeMillis() - start < timeoutMs) {
            long now = System.currentTimeMillis();
            if (now >= nextSend) {
                List<InetAddress> targets = broadcastAddresses();
                String ip = pcIp;
                if (ip != null) {
                    try { targets.add(InetAddress.getByName(ip)); } catch (Exception ignored) {}
                }
                for (InetAddress addr : targets) {
                    try {
                        s.send(new DatagramPacket(payload, payload.length, addr, PORT));
                    } catch (Exception ignored) {}
                }
                nextSend = now + 1200;
            }
            if (lastReplyAt > before && pcIp != null) return true;
            try { Thread.sleep(150); } catch (InterruptedException e) { break; }
        }
        return lastReplyAt > before && pcIp != null;
    }

    private List<InetAddress> broadcastAddresses() {
        List<InetAddress> list = new ArrayList<InetAddress>();
        try { list.add(InetAddress.getByName("255.255.255.255")); } catch (Exception ignored) {}
        try {
            Enumeration<NetworkInterface> nifs = NetworkInterface.getNetworkInterfaces();
            while (nifs.hasMoreElements()) {
                NetworkInterface nif = nifs.nextElement();
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InterfaceAddress ia : nif.getInterfaceAddresses()) {
                    InetAddress b = ia.getBroadcast();
                    if (b != null) list.add(b);
                }
            }
        } catch (Exception ignored) {}
        return list;
    }

    private static double r3(float v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
