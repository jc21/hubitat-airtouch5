# AirTouch 5 Hubitat Driver — Developer Guide

## Project overview

Two Hubitat Groovy driver files:

- `AirTouch5.groovy` — parent controller driver, holds the TCP socket, parses all protocol messages, exposes AC control commands, creates zone child devices
- `AirTouch5Zone.groovy` — child driver, one instance per zone, delegates all commands to parent via `parent.zoneControl()`

The driver communicates with the AirTouch 5 HVAC console over TCP port 9005 using a binary protocol. No authentication is required.

---

## Protocol fundamentals

### Wire framing

Every message on the wire is prefixed with a 4-byte magic header:

```
55 55 55 AA   — standard message header
55 55 55 AB   — envelope header (wraps one or more AA messages)
```

**AB envelope format** (10-byte wrapper, then inner AA messages):

```
55 55 55 AB | 00 00 00 | NN | 00 NN | [NN bytes of inner AA messages]
```

- Bytes 0–3: `55 55 55 AB`
- Bytes 4–6: always `00 00 00`
- Byte 7: inner length high byte
- Bytes 8–9: `00 NN` where `NN` is the inner length low byte

The AirTouch 5 wraps all its responses in AB envelopes. The driver strips the 10-byte AB framing and exposes the inner content to be parsed as regular AA messages. This is handled in `processRxBuffer()`.

### AA message structure (cleaned, after redundant bytes removed)

| Field    | Bytes | Notes                              |
|----------|-------|------------------------------------|
| address  | 2     | `80 B0` (from device), `90 B0` (to device) |
| msg ID   | 1     | monotonic counter, wraps at 255    |
| msg type | 1     | `C0` = control/status, `1F` = extended |
| data len | 2     | big-endian                         |
| data     | n     |                                    |
| CRC      | 2     | CRC16 MODBUS, big-endian (high byte first) |

### Redundant bytes

`0x00` is inserted after every sequence of exactly three consecutive `0x55` bytes, both in TX and RX. The driver adds these on transmit (`addRedundantBytes`) and strips them on receive (`stripRedundantBytes`).

### CRC16 MODBUS

- Init: `0xFFFF`, polynomial: `0xA001`
- Computed over all bytes from address through end of data (everything except the 4-byte header and the 2 trailing CRC bytes)
- **Both TX and RX use big-endian byte order** (high byte first). This was a hard-won discovery — the AirTouch silently drops any message with incorrect CRC.

```groovy
def crc    = crc16Modbus(payload)
def rawMsg = payload + [(crc >> 8) & 0xFF, crc & 0xFF]  // big-endian
```

---

## Message types

### Control/status messages (`0xC0`)

Sub-type byte is the first byte of the data payload. Sub-type header structure (8 bytes):

```
subType(1) | 00 | nLen(2, big-endian) | rLen(2, big-endian) | rCnt(2, big-endian)
```

Then `nLen` bytes of normal/fixed data, followed by `rCnt` records each of `rLen` bytes.

| Sub-type | Description             |
|----------|-------------------------|
| `0x20`   | Zone control (TX)       |
| `0x21`   | Zone status (RX)        |
| `0x22`   | AC control (TX)         |
| `0x23`   | AC status (RX)          |
| `0x45`   | System info (RX, pushed on connect, undocumented) |

### Extended messages (`0x1F`)

First two bytes of data are a command word:

| Command     | Description        |
|-------------|--------------------|
| `FF 11`     | AC ability (TX request / RX response) |
| `FF 10`     | AC error info (TX request / RX response) |
| `FF 13`     | Zone names (TX request / RX response) |
| `FF 30`     | Console version (TX request / RX response) |

---

## Critical: initialization handshake

**The AirTouch 5 requires `GET_AC_ABILITY` (`FF 11`) to be the very first message sent after connecting.** It uses this to register the client; it will not respond to AC control or status requests until this handshake is complete.

Correct sequence in `onConnected()`:

1. Send `requestACAbility()` — triggers `parseACAbility()` on response
2. `parseACAbility()` then sends: `requestACStatus()`, `requestZoneStatus()`, `requestConsoleVersion()`, `requestACError()`
3. Subsequent polls (`refresh()`) only send `requestACStatus()`, `requestZoneStatus()`, `requestConsoleVersion()`

Zone names are fetched once per connection via `state.zoneNamesFetched` guard — they don't change at runtime.

---

## AC control byte encoding

AC control records are 4 bytes: `[b0, b1, b2, b3]`

- `b0`: `(powerNibble << 4) | acIndex`
  - Power nibble `0x3` = set on, `0x2` = set off, `0xF` = keep current
- `b1`: `(modeNibble << 4) | fanNibble` — `0xFF` = keep current
- `b2`: setpoint control byte — `0x00` keep, `0x40` = set (with b3 as raw value)
- `b3`: setpoint raw value, or `0xFF` keep

Setpoint encoding: `raw = (celsius * 10) - 100`. Range 10–35 °C → raw 0–250.

Zone control is via `parent.zoneControl(zoneNum, byte2Val, value)`:
- Zone on: `byte2Val=0x03`, `value=0xFF`
- Zone off: `byte2Val=0x02`, `value=0xFF`
- Set setpoint: `byte2Val=0xA0`, `value=raw`
- Set open %: `byte2Val=0x80`, `value=pct`

---

## Common gotchas

### Groovy BigDecimal scientific notation

Groovy integer arithmetic returns `BigDecimal`. Division can produce scientific notation (`2E+1` instead of `21.0`). Always cast temperature/setpoint results:

```groovy
// Wrong — can produce "2E+1"
def sp = (spRaw + 100) / 10.0

// Correct
def sp = (spRaw + 100) / 10.0 as double
```

### rawSocket is asynchronous

`interfaces.rawSocket.connect()` returns immediately. The socket is not ready yet. Use `runIn(3, "onConnected")` to delay first TX. Socket errors arrive via `socketStatus()`.

### Half-open TCP detection

Hubitat's `rawSocket` does not detect silent drops at the TCP level. The driver tracks `state.lastRxTime` and reconnects if no data has been received for `2 × pollInterval` seconds. This is checked at the start of `refresh()`.

### AB envelope length field

The inner length is encoded as a 2-byte big-endian word at wire bytes 8–9 of the AB frame (i.e., hex string positions 16–19 after the 8-char `555555AB` prefix). Read it as:

```groovy
int innerLen = Integer.parseInt(hex.substring(16, 20), 16)
```

---

## Logging

- `logDebug` — per-message tracing (RX chunks, TX hex, parsed field values). Enabled via the "Enable debug logging" device preference.
- `log.info` — connection lifecycle, AC ability results, AC error results, console name. Always on.
- `log.warn` — CRC mismatches, half-open reconnects, socket loss.
- `log.error` — connection failures.

Do not promote debug traces to `log.info` permanently. The socket is polled every 30–300 seconds; info-level per-message logs will flood the Hubitat system log.

---

## Reference

The homebridge-airtouch5-platform TypeScript source (`@homebridge-airtouch5-platform/`) was used to resolve ambiguities not covered by the official protocol PDF:

- Confirmed big-endian CRC for TX (`writeUInt16BE`)
- Confirmed `GET_AC_ABILITY` as required first message
- Confirmed AB envelope structure

When the official protocol document (AirTouch 5 Communication Protocol V1.1) is silent on behaviour, cross-check against the homebridge source before guessing.
