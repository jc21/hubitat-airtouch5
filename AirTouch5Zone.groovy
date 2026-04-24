/*
 * AirTouch 5 Zone — Hubitat Child Driver
 * Protocol: AirTouch 5 Communication Protocol V1.1
 *
 * Child devices are created automatically by the AirTouch 5 Controller driver.
 * Do not add this device manually; install this driver file so the parent can reference it.
 *
 * Zone control commands delegate to the parent controller, which holds the TCP socket.
 */

metadata {
    definition(name: "AirTouch 5 Zone", namespace: "airtouch5", author: "Custom") {
        capability "Switch"                // on() / off()
        capability "TemperatureMeasurement"   // zone temperature (°C) when sensor fitted

        attribute "zoneName",        "string"   // name configured in AirTouch console
        attribute "setpoint",        "number"   // target setpoint in °C
        attribute "openPercentage",  "number"   // damper open % (0–100)
        attribute "controlMethod",   "string"   // "temperature" or "percentage"

        command "setSetpoint",       [[name: "setpoint*",    type: "NUMBER",
                                       description: "Target temperature (°C, 10–35). Switches zone to temperature control."]]
        command "setOpenPercentage", [[name: "percentage*",  type: "NUMBER",
                                       description: "Damper open % (0–100). Switches zone to percentage control."]]
        command "setControlMethod",  [[name: "method*",      type: "ENUM",
                                       constraints: ["temperature", "percentage"]]]
    }
}

// ── Switch ───────────────────────────────────────────────────────────────────

def on() {
    // Power bits 3-1 = 011 (set to on), zone setting bits 7-5 = 000 (keep)
    parent.zoneControl(zoneNum(), 0x03, 0xFF)
}

def off() {
    // Power bits 3-1 = 010 (set to off)
    parent.zoneControl(zoneNum(), 0x02, 0xFF)
}

// ── Zone commands ─────────────────────────────────────────────────────────────

def setSetpoint(setpoint) {
    def raw = (setpoint.toBigDecimal() * 10 - 100).toInteger()
    if (raw < 0 || raw > 250) { log.warn "setSetpoint: ${setpoint}°C out of range (10–35°C)"; return }
    // byte2 = 0xA0: zone-setting bits 8-6 = 101 ("set target setpoint").
    // The AirTouch protocol switches the zone to temperature-control mode as part of this
    // command — no separate mode-change message exists in the protocol.
    parent.zoneControl(zoneNum(), 0xA0, raw)
    sendEvent(name: "controlMethod", value: "temperature")
}

def setOpenPercentage(percentage) {
    def pct = percentage.toInteger().clamp(0, 100)
    // byte2 = 0x80: zone-setting bits 8-6 = 100 ("set open percentage").
    // The AirTouch protocol switches the zone to percentage-control mode as part of this
    // command — no separate mode-change message exists in the protocol.
    parent.zoneControl(zoneNum(), 0x80, pct)
    sendEvent(name: "controlMethod", value: "percentage")
}

def setControlMethod(String method) {
    if (method == "temperature") {
        // Switching to temperature control requires providing a setpoint.
        // Re-send the current setpoint (default 22°C) with the 0xA0 setting bits,
        // which tells the AirTouch controller to switch mode and apply the value.
        def sp  = (device.currentValue("setpoint") ?: 22).toBigDecimal()
        def raw = (sp * 10 - 100).toInteger().clamp(0, 250)
        parent.zoneControl(zoneNum(), 0xA0, raw)
    } else {
        // Switching to percentage control requires providing an open percentage.
        // Re-send the current percentage (default 50%) with the 0x80 setting bits.
        def pct = (device.currentValue("openPercentage") ?: 50).toInteger().clamp(0, 100)
        parent.zoneControl(zoneNum(), 0x80, pct)
    }
    sendEvent(name: "controlMethod", value: method)
}

// ── Helper ────────────────────────────────────────────────────────────────────

private int zoneNum() {
    // DNI format: "<parentDNI>-zone<N>"
    return device.deviceNetworkId.replaceAll(/.*-zone/, "").toInteger()
}
