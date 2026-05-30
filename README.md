# SimWheel Phone (SRFN)

An open-source **Android** steering-wheel/pedals client for the **SimWheel PC
Receiver** — the same Windows receiver you already run (the C++ "SIMWHEEL PC
SERVER v3.0" that feeds [vJoy](https://sourceforge.net/projects/vjoystick/)).

It is a clean-room re-implementation of the phone side, built to match your
existing receiver, with the inputs you asked for:

- 🎮 **Steering = phone gyro/tilt.** Hold the phone in landscape like a wheel
  and turn it. Uses the fused gravity/gyro sensor, with calibration, deadzone,
  sensitivity and invert.
- 🦶 **Pedals = a Bluetooth controller's analog triggers.** Right trigger →
  throttle, left trigger → brake (swappable). Real analog 0–100%, not on/off.
- 🔘 **Buttons.** A/B/X/Y/L1/R1/Start/Select → vJoy buttons 1–8.
- 📡 **Talks to your current receiver** over Wi‑Fi using its exact UDP + JSON
  protocol on port **4567** (discovery, approval, telemetry). No receiver
  changes needed.

The receiver protocol was reverse-engineered from `Receiver.cpp`; the full
write-up is in [`docs/PROTOCOL.md`](docs/PROTOCOL.md).

---

## Get the APK

A prebuilt, signed, installable debug APK is checked into the repo:
**[`SimWheel-debug.apk`](SimWheel-debug.apk)** (minSdk 26 / Android 8+).
Copy it to your phone and tap to install (allow "install unknown apps").

Three ways to (re)build it:

### 1. SDK-free offline build (no Android Studio, no Google Maven)

The app uses **only the Android framework** (no AndroidX), so it builds with a
tiny toolchain — `aapt2` + `android.jar` + `javac` + `dx` + a signer — none of
which come from Google's Maven. Handy on locked-down networks.

```bash
bash tools/fetch_buildtools.sh   # downloads the toolchain to /tmp/apkbuild
bash tools/build_apk.sh          # -> ./SimWheel-debug.apk
```

### 2. GitHub Actions

[`.github/workflows/android.yml`](.github/workflows/android.yml) runs on every
push. The **build-offline** job uses the toolchain above and always produces an
artifact; **Actions tab → latest run → `SimWheel-debug-apk`**.

### 3. Android Studio / Gradle

Open the project in **Android Studio** and **Build → Build APK(s)**, or:

```bash
./gradlew assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```

Toolchain: pure Java (no Kotlin, no AndroidX) · AGP 8.5.2 · Gradle 8.9 ·
JDK 17 · minSdk 26 · compileSdk 34.

---

## How to use it

**On the PC**
1. Start your SimWheel receiver. When it asks for a **steering range**, note the
   number (default **900**) — you'll match it in the app.
2. Make sure the PC firewall allows the receiver on UDP 4567, and that phone +
   PC are on the **same network** (Wi‑Fi, or USB tethering for low latency).

**On the phone**
3. Pair your **Bluetooth game controller** with the phone (Android Settings →
   Bluetooth). Xbox, DualShock/DualSense, and 8BitDo pads all expose analog
   triggers.
4. Open **SimWheel Phone** (it runs in landscape).
5. Tap **Discover PC** (or type the PC's IP and tap **Use IP**).
6. 👉 **Switch to the PC and type `y`** when the receiver asks
   *"Allow this device to control your PC?"*. The app then shows
   *Connected to &lt;PC name&gt;*.
7. Set **PC steering range** to the same number you gave the receiver.
8. Hold the phone like a wheel in your neutral position and tap
   **Calibrate centre**.
9. Tap **START** and drive. In your game/vJoy, map: **X axis = steering,
   Y = throttle, Z = brake, buttons 1–8**.

---

## Controls & mapping

| Phone input | Sent to receiver | vJoy |
|---|---|---|
| Tilt/turn the phone (gyro) | `steering` (degrees, ±range) | **X axis** |
| Controller right trigger (RT/R2) | `throttle` 0–1 | **Y axis** |
| Controller left trigger (LT/L2) | `brake` 0–1 | **Z axis** |
| A / B / X / Y | `"1"`/`"2"`/`"3"`/`"4"` | buttons 1–4 |
| L1 / R1 / Start / Select | `"5"`/`"6"`/`"7"`/`"8"` | buttons 5–8 |

## Settings

| Setting | What it does |
|---|---|
| **Turn sensitivity** | Physical phone rotation (°) needed for full lock. Lower = twitchier. |
| **Centre deadzone** | Ignores small wobble around centre. |
| **PC steering range** | The degree value sent at full lock — **set it equal to the receiver's range** (default 900) for 1:1 feel. |
| **Send rate** | Telemetry packets per second (default 60). |
| **Invert steering** | Flip left/right. |
| **Swap pedals** | Swap which trigger is throttle vs brake. |

All settings persist between launches.

---

## Troubleshooting

- **Receiver never shows the phone / "No PC found".** Approve the device on the
  PC (`y`). Confirm same subnet, UDP 4567 open, receiver running. If broadcast
  discovery is blocked by the network, type the PC's IP and tap **Use IP**.
- **Steering drifts or is off-centre.** Tap **Calibrate centre** while holding
  the neutral position. Avoid holding the phone flat (a warning appears).
- **Wrong steering direction.** Toggle **Invert steering**.
- **Triggers do nothing.** Make sure the controller is connected to the *phone*
  and that it has *analog* triggers. Press the triggers and watch the on-screen
  Throttle/Brake bars; the detected pad name is shown under the status line.
- **Too sensitive / not enough lock.** Match **PC steering range** to the
  receiver, then tune **Turn sensitivity**.

## Notes & limitations

- The receiver authorises by **IP**, in memory only — re-approve after a
  receiver restart or if your phone's IP changes.
- Steering uses the gravity/gyro fusion sensor; the controller's gyro is not
  used (Android exposes pad triggers/buttons but not pad IMUs in a standard way).
- `clutch`/`zaxis`/mouse/keyboard features of the receiver are not used by this
  client (it sends an inert `dx:0,dy:0` as the protocol requires).
- Debug-signed APK is for personal/sideload use.

## Repo layout

```
SimWheel-debug.apk         prebuilt signed debug APK
app/                       Android app (pure Java, no AndroidX)
  src/main/java/com/srfn/simwheel/
    MainActivity.java      UI (built in code), lifecycle, gamepad capture
    SteeringSensor.java    gyro/gravity -> normalised steering
    GamepadManager.java    analog triggers + buttons from the controller
    SimWheelClient.java    UDP + JSON: discovery + telemetry (port 4567)
    Controls.java          immutable control snapshot
  src/main/res/            strings + launcher icon
tools/
  fetch_buildtools.sh      download the SDK-free toolchain
  build_apk.sh             build a signed APK without the Android SDK
docs/PROTOCOL.md           reverse-engineered receiver protocol
.github/workflows/         CI that builds the APK (offline + Gradle)
```
