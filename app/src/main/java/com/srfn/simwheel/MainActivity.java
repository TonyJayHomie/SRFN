package com.srfn.simwheel;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * SimWheel Phone — single-screen controller UI, built entirely from framework
 * views (no AndroidX) so it can be compiled with a minimal offline toolchain.
 */
public final class MainActivity extends Activity {

    private SharedPreferences prefs;
    private SteeringSensor steering;
    private final GamepadManager gamepad = new GamepadManager();
    private SimWheelClient client;

    private volatile boolean swapPedals = false;
    private boolean streaming = false;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable uiTick;

    // views we update at runtime
    private TextView statusText, deviceText, steerValue, throttleValue, brakeValue,
            buttonsValue, flatWarning, packetsText,
            sensitivityLabel, deadzoneLabel, rangeLabel, rateLabel;
    private ProgressBar steerBar, throttleBar, brakeBar;
    private EditText ipInput;
    private Button startButton, discoverButton;

    private static final int TEAL = 0xFF1DE9B6;
    private static final int BG = 0xFF0E1116;
    private static final int WARN = 0xFFFFC107;
    private static final int FG = 0xFFECEFF1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setBackgroundColor(BG);

        prefs = getSharedPreferences("simwheel", Context.MODE_PRIVATE);
        steering = new SteeringSensor(this);
        client = new SimWheelClient(new SimWheelClient.ControlsProvider() {
            public Controls get() {
                float t = gamepad.getRightTrigger();
                float b = gamepad.getLeftTrigger();
                return new Controls(
                        steering.getSteer(),
                        swapPedals ? b : t,
                        swapPedals ? t : b,
                        gamepad.getButtons());
            }
        });

        setContentView(buildUi());
        loadSettings();
    }

    // ---- UI construction -------------------------------------------------

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setPadding(dp(10), dp(10), dp(10), dp(10));

        root.addView(buildLeftColumn(), col());
        root.addView(buildRightColumn(), col());
        return root;
    }

    private LinearLayout.LayoutParams col() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        return lp;
    }

    private View buildLeftColumn() {
        ScrollView sv = new ScrollView(this);
        LinearLayout c = column();

        statusText = label(c, "Not connected", 16, TEAL, true);
        label(c, "First time: approve this phone in the PC receiver window (type y).",
                11, 0xFFB0BEC5, false);

        deviceText = label(c, "Pad: none", 12, FG, false);

        steerValue = label(c, "Steer", 14, FG, false);
        steerBar = bar(c, 200, 100);
        throttleValue = label(c, "Throttle", 14, FG, false);
        throttleBar = bar(c, 100, 0);
        brakeValue = label(c, "Brake", 14, FG, false);
        brakeBar = bar(c, 100, 0);

        buttonsValue = label(c, "Buttons [0]: -", 12, FG, false);
        flatWarning = label(c, "Hold the phone upright like a wheel", 12, WARN, false);
        flatWarning.setVisibility(View.GONE);
        packetsText = label(c, "Sent: 0 packets", 11, 0xFFB0BEC5, false);

        startButton = new Button(this);
        startButton.setText("START");
        startButton.setTextSize(18);
        startButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleStreaming(); }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56));
        blp.topMargin = dp(10);
        c.addView(startButton, blp);

        sv.addView(c);
        return sv;
    }

    private View buildRightColumn() {
        ScrollView sv = new ScrollView(this);
        LinearLayout c = column();

        label(c, "CONNECTION", 12, TEAL, true);
        ipInput = new EditText(this);
        ipInput.setHint("PC IP (e.g. 192.168.1.20)");
        ipInput.setHintTextColor(0xFF607D8B);
        ipInput.setTextColor(FG);
        ipInput.setInputType(InputType.TYPE_CLASS_TEXT);
        c.addView(ipInput);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        discoverButton = new Button(this);
        discoverButton.setText("Discover PC");
        discoverButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { runDiscovery(); }
        });
        Button connectButton = new Button(this);
        connectButton.setText("Use IP");
        connectButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { useTypedIp(); }
        });
        row.addView(discoverButton, weight1());
        row.addView(connectButton, weight1());
        c.addView(row);

        Button calibrate = new Button(this);
        calibrate.setText("Calibrate centre");
        calibrate.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                steering.calibrateCenter();
                toast("Centre set");
            }
        });
        c.addView(calibrate);

        label(c, "STEERING / OUTPUT", 12, TEAL, true);

        sensitivityLabel = label(c, "", 13, FG, false);
        final SeekBar sens = seek(c, 160);
        sens.setOnSeekBarChangeListener(new SeekListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean u) {
                steering.setMaxAngleDeg(p + 20);   // 20..180 deg
                updateSettingLabels();
                prefs.edit().putFloat("maxAngle", steering.getMaxAngleDeg()).apply();
            }
        });

        deadzoneLabel = label(c, "", 13, FG, false);
        final SeekBar dz = seek(c, 30);
        dz.setOnSeekBarChangeListener(new SeekListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean u) {
                steering.setDeadzone(p / 100f);    // 0..0.30
                updateSettingLabels();
                prefs.edit().putFloat("deadzone", steering.getDeadzone()).apply();
            }
        });

        rangeLabel = label(c, "", 13, FG, false);
        final SeekBar range = seek(c, 2430);
        range.setOnSeekBarChangeListener(new SeekListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean u) {
                client.setRangeDeg(p + 90);        // 90..2520 deg, match the PC
                updateSettingLabels();
                prefs.edit().putFloat("range", client.getRangeDeg()).apply();
            }
        });

        rateLabel = label(c, "", 13, FG, false);
        final SeekBar rate = seek(c, 110);
        rate.setOnSeekBarChangeListener(new SeekListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean u) {
                client.setRateHz(p + 10);          // 10..120 Hz
                updateSettingLabels();
                prefs.edit().putInt("rate", client.getRateHz()).apply();
            }
        });

        final CheckBox invert = new CheckBox(this);
        invert.setText("Invert steering");
        invert.setTextColor(FG);
        invert.setOnCheckedChangeListener(new CheckBox.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton b, boolean v) {
                steering.setInvert(v);
                prefs.edit().putBoolean("invert", v).apply();
            }
        });
        c.addView(invert);

        final CheckBox swap = new CheckBox(this);
        swap.setText("Swap pedals (LT/RT)");
        swap.setTextColor(FG);
        swap.setOnCheckedChangeListener(new CheckBox.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton b, boolean v) {
                swapPedals = v;
                prefs.edit().putBoolean("swap", v).apply();
            }
        });
        c.addView(swap);

        // stash for loadSettings()
        this.sensSeek = sens; this.dzSeek = dz; this.rangeSeek = range;
        this.rateSeek = rate; this.invertBox = invert; this.swapBox = swap;

        sv.addView(c);
        return sv;
    }

    private SeekBar sensSeek, dzSeek, rangeSeek, rateSeek;
    private CheckBox invertBox, swapBox;

    // ---- settings --------------------------------------------------------

    private void loadSettings() {
        steering.setMaxAngleDeg(prefs.getFloat("maxAngle", 90f));
        steering.setDeadzone(prefs.getFloat("deadzone", 0.03f));
        steering.setInvert(prefs.getBoolean("invert", false));
        client.setRateHz(prefs.getInt("rate", 60));
        client.setRangeDeg(prefs.getFloat("range", 900f));
        swapPedals = prefs.getBoolean("swap", false);
        client.setPcIp(prefs.getString("pcIp", null));

        if (client.getPcIp() != null) ipInput.setText(client.getPcIp());
        invertBox.setChecked(steering.getInvert());
        swapBox.setChecked(swapPedals);
        sensSeek.setProgress(Math.round(steering.getMaxAngleDeg() - 20f));
        dzSeek.setProgress(Math.round(steering.getDeadzone() * 100f));
        rangeSeek.setProgress(Math.round(client.getRangeDeg() - 90f));
        rateSeek.setProgress(client.getRateHz() - 10);
        updateSettingLabels();
    }

    private void updateSettingLabels() {
        sensitivityLabel.setText("Turn sensitivity: " + Math.round(steering.getMaxAngleDeg())
                + "° for full lock");
        deadzoneLabel.setText("Centre deadzone: " + Math.round(steering.getDeadzone() * 100f) + "%");
        rangeLabel.setText("PC steering range: " + Math.round(client.getRangeDeg())
                + "° (match receiver)");
        rateLabel.setText("Send rate: " + client.getRateHz() + " Hz");
    }

    // ---- actions ---------------------------------------------------------

    private void useTypedIp() {
        String ip = ipInput.getText().toString().trim();
        if (ip.isEmpty()) { toast("Enter the PC IP first"); return; }
        client.setPcIp(ip);
        prefs.edit().putString("pcIp", ip).apply();
        toast("Target set to " + ip);
        runDiscovery();
    }

    private void runDiscovery() {
        discoverButton.setEnabled(false);
        statusText.setText("Searching for PC... approve on the PC if asked");
        new Thread(new Runnable() {
            public void run() {
                final boolean found = client.discover(15000);
                ui.post(new Runnable() {
                    public void run() {
                        discoverButton.setEnabled(true);
                        if (found) {
                            String ip = client.getPcIp();
                            if (ip != null) {
                                ipInput.setText(ip);
                                prefs.edit().putString("pcIp", ip).apply();
                            }
                            String n = client.getPcName();
                            toast("Connected to " + (n != null ? n : ip));
                        } else {
                            toast("No PC found. Check Wi-Fi and that the receiver is running and approved.");
                        }
                    }
                });
            }
        }, "sw-discover").start();
    }

    private void toggleStreaming() {
        if (streaming) {
            streaming = false;
            client.setStreaming(false);
        } else {
            String ip = ipInput.getText().toString().trim();
            if (!ip.isEmpty()) {
                client.setPcIp(ip);
                prefs.edit().putString("pcIp", ip).apply();
            }
            if (client.getPcIp() == null || client.getPcIp().isEmpty()) {
                toast("Discover or enter the PC IP first");
                return;
            }
            streaming = true;
            client.setStreaming(true);
        }
        startButton.setText(streaming ? "STOP" : "START");
    }

    // ---- gamepad capture -------------------------------------------------

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_MOVE && gamepad.onMotion(ev)) return true;
        return super.dispatchGenericMotionEvent(ev);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        if (gamepad.onKey(ev.getKeyCode(), ev)) return true;
        return super.dispatchKeyEvent(ev);
    }

    // ---- lifecycle -------------------------------------------------------

    @Override
    protected void onResume() {
        super.onResume();
        steering.start();
        client.start();
        client.setStreaming(streaming);
        startUiLoop();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopUiLoop();
        client.setStreaming(false);
        client.stop();
        steering.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        client.shutdown();
    }

    private void startUiLoop() {
        stopUiLoop();
        uiTick = new Runnable() {
            public void run() {
                refreshUi();
                ui.postDelayed(this, 40); // ~25 Hz
            }
        };
        ui.post(uiTick);
    }

    private void stopUiLoop() {
        if (uiTick != null) { ui.removeCallbacks(uiTick); uiTick = null; }
    }

    private void refreshUi() {
        float steer = steering.getSteer();
        float throttle = swapPedals ? gamepad.getLeftTrigger() : gamepad.getRightTrigger();
        float brake = swapPedals ? gamepad.getRightTrigger() : gamepad.getLeftTrigger();

        steerBar.setProgress(Math.round((steer + 1f) * 100f));
        throttleBar.setProgress(Math.round(throttle * 100f));
        brakeBar.setProgress(Math.round(brake * 100f));

        steerValue.setText(String.format(Locale.US, "Steer  %+.2f  (%+d°)",
                steer, Math.round(steering.getAngleDeg())));
        throttleValue.setText(String.format(Locale.US, "Throttle  %.2f", throttle));
        brakeValue.setText(String.format(Locale.US, "Brake  %.2f", brake));
        buttonsValue.setText("Buttons [" + gamepad.getButtons() + "]: "
                + buttonString(gamepad.getButtons()));
        String pad = gamepad.getLastDeviceName();
        deviceText.setText("Pad: " + (pad != null ? pad : "none"));

        flatWarning.setVisibility(steering.isFlat() ? View.VISIBLE : View.GONE);

        String ip = client.getPcIp();
        String name = client.getPcName() != null ? client.getPcName() : ip;
        if (client.isStreaming() && ip != null) {
            statusText.setText("● STREAMING -> " + name);
        } else if (ip != null) {
            statusText.setText("Ready -> " + name + "   (press START)");
        } else {
            statusText.setText("Not connected - Discover or enter PC IP");
        }

        String err = client.getLastError();
        if (err != null && client.isStreaming()) {
            packetsText.setText("Send error: " + err);
        } else {
            packetsText.setText("Sent: " + client.getPacketsSent() + " packets");
        }
    }

    private static String buttonString(int mask) {
        if (mask == 0) return "-";
        String[] names = {"A", "B", "X", "Y", "L1", "R1", "Start", "Select"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.length; i++) {
            if ((mask & (1 << i)) != 0) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(names[i]);
            }
        }
        return sb.toString();
    }

    // ---- small view helpers ---------------------------------------------

    private LinearLayout column() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(6), dp(6), dp(6), dp(6));
        return c;
    }

    private TextView label(LinearLayout parent, String text, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        parent.addView(t, lp);
        return t;
    }

    private ProgressBar bar(LinearLayout parent, int max, int progress) {
        ProgressBar p = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        p.setMax(max);
        p.setProgress(progress);
        parent.addView(p);
        return p;
    }

    private SeekBar seek(LinearLayout parent, int max) {
        SeekBar s = new SeekBar(this);
        s.setMax(max);
        parent.addView(s);
        return s;
    }

    private LinearLayout.LayoutParams weight1() {
        return new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    /** SeekBar listener that only cares about progress changes. */
    private abstract static class SeekListener implements SeekBar.OnSeekBarChangeListener {
        public void onStartTrackingTouch(SeekBar sb) { }
        public void onStopTrackingTouch(SeekBar sb) { }
    }
}
