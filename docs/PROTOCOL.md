# SimWheel receiver protocol (reverse-engineered)

This documents the wire protocol spoken by the **SimWheel PC Receiver**
(`Receiver.cpp`, "SIMWHEEL PC SERVER v3.0"), so a custom phone client can talk
to it. Everything here was derived from the receiver's source; the official app
is closed-source Flutter.

## Transport

| | |
|---|---|
| Protocol | UDP |
| Receiver port | **4567** (one port for discovery *and* telemetry) |
| Direction | phone → PC for control; PC → phone only for the discovery reply |
| Payload | a single **JSON object** per datagram (no framing, no newline needed) |
| Buffer | receiver reads up to 511 bytes per datagram — keep packets small |

The receiver replies to the **source address and port** of the datagram it
received, so a client must send and listen on the same socket.

## Device approval (important!)

The receiver keeps an in-memory allow-list keyed by **IP address**. The *first*
datagram from a new IP makes the PC console block and prompt:

```
Allow this device to control your PC? (y/n):
```

Nothing (not even the discovery reply) happens until a human types `y` on the
PC. After approval that IP is remembered for the rest of the receiver session.
Sending `phoneName` (see below) makes the prompt show a friendly name.

## Discovery

**Phone → PC**
```json
{ "type": "discover", "phoneName": "Pixel 7" }
```

**PC → phone** (only after the device is approved)
```json
{ "type": "discover_reply", "name": "DESKTOP-ABC", "connection": "wifi" }
```
`connection` is one of `wifi` / `usb` / `ethernet` / `unknown`. The reply does
**not** contain the PC's IP — use the source address of the reply datagram.

Discovery may be broadcast (e.g. `255.255.255.255:4567`) or unicast to a known
IP. Unicasting to a manually-typed IP is also the easiest way to trigger the
approval prompt.

## Telemetry

Sent continuously (e.g. 60 Hz) while driving:

```json
{
  "steering": 450.0,
  "throttle": 0.75,
  "brake": 0.0,
  "dx": 0,
  "dy": 0,
  "1": true,
  "2": false
}
```

### Fields

| Key | Type | Meaning |
|-----|------|---------|
| `steering` | number | Wheel angle **in degrees**. The PC divides it by its configured *range* (default **900**, min 90, max 2520) to get the vJoy X axis. So ±range = full lock. |
| `throttle` | number | 0.0 .. 1.0 → vJoy **Y** axis. |
| `brake` | number | 0.0 .. 1.0 → vJoy **Z** axis. |
| `clutch ` | number | Optional, 0..1 → vJoy **RX**. Note the literal **trailing space** in the key (receiver quirk). |
| `zaxis` | number | Optional, 0..1 → vJoy **RZ**. |
| `dx`,`dy` | number | Relative mouse movement. |
| `horn` | bool | → vJoy **button 1**. |
| `"<n>"` | bool | A **stringified number** is a button: `1..199` → vJoy button *n*; `200+` → keyboard key; `500/501/503` → mouse L/R/M. |

### Two mandatory quirks

1. **Every telemetry packet must contain either `zaxis` *or* both `dx` and
   `dy`.** The receiver does `if (zaxis) {...} else { dx = j.at("dx"); ... }`,
   and `j.at("dx")` throws if absent, aborting the rest of the packet. This
   client always sends `"dx": 0, "dy": 0` (an inert 0,0 mouse move).

2. **Only numeric-string keys may remain after the known keys.** The receiver
   iterates leftover keys and calls `std::stoi(key)`; a non-numeric leftover key
   (e.g. `phoneName`) throws and stops button processing. So telemetry packets
   must *not* include `type`/`phoneName`; those belong only in discovery.

### Receiver axis math (for reference)

```
X (steer)    = clamp(16384 + (steering/range) * 16384, 0, 32768)
Y (throttle) = 16384 + (throttle*2 - 1) * 16384      // 0..1 -> 0..32768
Z (brake)    = 16384 + (brake*2 - 1)    * 16384
```

## This client's mapping

| Source on phone | Sent as | vJoy result |
|---|---|---|
| Gyro/tilt wheel angle (normalised ±1 × range°) | `steering` | X axis |
| Controller **right** trigger (RT/R2) | `throttle` | Y axis |
| Controller **left** trigger (LT/L2) | `brake` | Z axis |
| A / B / X / Y / L1 / R1 / Start / Select | `"1"`..`"8"` | buttons 1–8 |

(Throttle/brake can be swapped in the app; `clutch`/`zaxis` are not used.)
