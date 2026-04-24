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
    // byte2 = 0xA3: zone-setting bits 7-5 = 101 (set target setpoint) | power bits 1-0 = 11 (set on).
    // The AirTouch requires power bits = ON alongside the zone setting, otherwise it ignores the command.
    parent.zoneControl(zoneNum(), 0xA3, raw)
    sendEvent(name: "controlMethod", value: "temperature")
}

def setOpenPercentage(percentage) {
    def pct = Math.max(0, Math.min(100, percentage.toInteger()))
    // byte2 = 0x83: zone-setting bits 7-5 = 100 (set open percentage) | power bits 1-0 = 11 (set on).
    // The AirTouch requires power bits = ON alongside the zone setting, otherwise it ignores the command.
    parent.zoneControl(zoneNum(), 0x83, pct)
    sendEvent(name: "controlMethod", value: "percentage")
}

def setControlMethod(String method) {
    if (method == "temperature") {
        def sp  = (device.currentValue("setpoint") ?: 22).toBigDecimal()
        def raw = Math.max(0, Math.min(250, (sp * 10 - 100).toInteger()))
        parent.zoneControl(zoneNum(), 0xA3, raw)
    } else {
        def pct = Math.max(0, Math.min(100, (device.currentValue("openPercentage") ?: 50).toInteger()))
        parent.zoneControl(zoneNum(), 0x83, pct)
    }
    sendEvent(name: "controlMethod", value: method)
}

// ── Helper ────────────────────────────────────────────────────────────────────

private int zoneNum() {
    // DNI format: "<parentDNI>-zone<N>"
    return device.deviceNetworkId.replaceAll(/.*-zone/, "").toInteger()
}
