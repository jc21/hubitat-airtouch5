/*
 * AirTouch 5 Controller — Hubitat Driver
 * Protocol: AirTouch 5 Communication Protocol V1.1
 * Port: 9005 (TCP)
 *
 * Install both AirTouch5.groovy and AirTouch5Zone.groovy in Hubitat's driver code editor.
 * Set the static IP address of the AirTouch 5 console in device preferences.
 * No password or authentication is required by the protocol.
 *
 * Main device represents the selected AC unit (0–7).
 * Zone child devices are created automatically from live zone status data.
 */

metadata {
    definition(name: "AirTouch 5 Controller", namespace: "airtouch5", author: "Custom") {
        capability "Switch"           // on() / off() controls the selected AC unit
        capability "TemperatureMeasurement"  // AC inlet temperature
        capability "Refresh"
        capability "Initialize"

        attribute "acMode",          "string"   // auto / heat / dry / fan / cool / auto heat / auto cool
        attribute "acFanSpeed",      "string"   // auto / quiet / low / medium / high / powerful / turbo / intelligent auto
        attribute "setpoint",        "number"   // current setpoint in °C
        attribute "powerState",      "string"   // on / off / away (off) / away (on) / sleep
        attribute "consoleVersion",  "string"
        attribute "connectionStatus","string"   // connected / disconnected / error

        command "setAcMode",     [[name: "mode*",     type: "ENUM",
                                   constraints: ["auto", "heat", "dry", "fan", "cool"]]]
        command "setAcFanSpeed", [[name: "speed*",    type: "ENUM",
                                   constraints: ["auto", "quiet", "low", "medium", "high", "powerful", "turbo", "intelligent auto"]]]
        command "setSetpoint",   [[name: "setpoint*", type: "NUMBER", description: "Target temperature (°C, 10–35)"]]
    }

    preferences {
        input name: "ipAddress",     type: "text",   title: "AirTouch 5 IP Address",        required: true
        input name: "acIndex",       type: "number", title: "AC Unit Number (0–7)",          defaultValue: 0,    required: true
        input name: "pollInterval",  type: "enum",   title: "Poll Interval",
              options: ["30": "30 s", "60": "1 min", "120": "2 min", "300": "5 min"],        defaultValue: "60"
        input name: "logEnable",     type: "bool",   title: "Enable debug logging",          defaultValue: true
    }
}

// ── Lifecycle ────────────────────────────────────────────────────────────────

def installed()  { log.info "AirTouch 5 installed"; initialize() }
def updated()    { log.info "AirTouch 5 updated";   unschedule(); initialize() }

def initialize() {
    state.msgId = 0
    state.rxHex = ""
    state.lastRxTime = 0
    state.zoneNamesFetched = false
    unschedule()
    sendEvent(name: "connectionStatus", value: "disconnected")
    connect()
}

// ── Connection ───────────────────────────────────────────────────────────────

def connect() {
    try {
        logDebug "Connecting to ${settings.ipAddress}:9005"
        interfaces.rawSocket.connect(settings.ipAddress, 9005, byteInterface: true)
        // rawSocket.connect() is asynchronous; we treat the socket as connected optimistically
        // and rely on socketStatus() to detect failures.  Schedule the first poll with a small
        // delay to give the TCP handshake time to complete.
        sendEvent(name: "connectionStatus", value: "connecting")
        runIn(3, "onConnected")
    } catch (e) {
        log.error "Connect failed: ${e}"
        sendEvent(name: "connectionStatus", value: "error")
        runIn(30, "connect")
    }
}

def onConnected() {
    log.info "Connected to AirTouch 5 at ${settings.ipAddress}"
    sendEvent(name: "connectionStatus", value: "connected")
    // AC ability must be the first message sent — the AirTouch 5 uses it to
    // register the client before it will respond to any AC control or status requests.
    requestACAbility()
    schedulePoll()
}

// Reconnects if the socket has gone silent since the last poll.
def ensureConnected() {
    if (device.currentValue("connectionStatus") != "connected") {
        log.warn "ensureConnected: not connected, reconnecting"
        connect()
        pauseExecution(3000)
    }
}

def socketStatus(String message) {
    log.info "socketStatus: ${message}"
    if (message.contains("error") || message.contains("closed") || message.contains("reset")) {
        sendEvent(name: "connectionStatus", value: "disconnected")
        log.warn "Socket lost (${message}). Reconnecting in 30 s."
        unschedule("refresh")
        unschedule("onConnected")
        runIn(30, "connect")
    }
}

def schedulePoll() {
    def secs = (settings.pollInterval ?: "60").toInteger()
    if (secs < 60) {
        schedule("0/${secs} * * ? * *", "refresh")
    } else {
        def mins = secs / 60
        schedule("0 0/${mins} * ? * *", "refresh")
    }
}

// ── Commands ─────────────────────────────────────────────────────────────────

def on() {
    ensureConnected()
    def ac = acNum()
    // Power nibble 0011 = Set to on; keep mode (0xF), keep fan (0xF), byte3=0x00 (no setpoint change)
    sendControlMsg(0x22, [], [[(0x30 | ac), 0xFF, 0x00, 0xFF]])
    runIn(3, "requestACStatus")
}

def off() {
    ensureConnected()
    def ac = acNum()
    // Power nibble 0010 = Set to off; keep everything else
    sendControlMsg(0x22, [], [[(0x20 | ac), 0xFF, 0x00, 0xFF]])
    runIn(3, "requestACStatus")
}

def setAcMode(String mode) {
    def modeBits
    switch (mode.toLowerCase()) {
        case "auto": modeBits = 0x00; break
        case "heat": modeBits = 0x01; break
        case "dry":  modeBits = 0x02; break
        case "fan":  modeBits = 0x03; break
        case "cool": modeBits = 0x04; break
        default: log.warn "setAcMode: unknown mode '${mode}'"; return
    }
    ensureConnected()
    def ac = acNum()
    sendControlMsg(0x22, [], [[(0xF0 | ac), ((modeBits << 4) | 0x0F), 0x00, 0xFF]])
    runIn(3, "requestACStatus")
}

def setAcFanSpeed(String speed) {
    def fanBits
    switch (speed.toLowerCase()) {
        case "auto":            fanBits = 0x00; break
        case "quiet":           fanBits = 0x01; break
        case "low":             fanBits = 0x02; break
        case "medium":          fanBits = 0x03; break
        case "high":            fanBits = 0x04; break
        case "powerful":        fanBits = 0x05; break
        case "turbo":           fanBits = 0x06; break
        case "intelligent auto":fanBits = 0x08; break
        default: log.warn "setAcFanSpeed: unknown speed '${speed}'"; return
    }
    ensureConnected()
    def ac = acNum()
    sendControlMsg(0x22, [], [[(0xF0 | ac), (0xF0 | fanBits), 0x00, 0xFF]])
    runIn(3, "requestACStatus")
}

def setSetpoint(setpoint) {
    def raw = (setpoint.toBigDecimal() * 10 - 100).toInteger()
    if (raw < 0 || raw > 250) { log.warn "setSetpoint: ${setpoint}°C out of range (10–35°C)"; return }
    ensureConnected()
    def ac = acNum()
    sendControlMsg(0x22, [], [[(0xF0 | ac), 0xFF, 0x40, raw]])
    runIn(3, "requestACStatus")
}

// Called by zone child devices to send a zone control message.
// byte2Val encodes the zone setting (bits 7-5) and power (bits 2-0) fields.
def zoneControl(int zoneNum, int byte2Val, int value) {
    ensureConnected()
    sendControlMsg(0x20, [], [[zoneNum & 0x3F, byte2Val, value, 0x00]])
}

def refresh() {
    // Detect half-open TCP: if we've sent requests but received nothing in 2× the poll interval, reconnect.
    def secs = (settings.pollInterval ?: "60").toInteger()
    def lastRx = state.lastRxTime ?: 0
    if (lastRx > 0 && (now() - lastRx) > (secs * 2 * 1000)) {
        log.warn "No RX data for ${secs * 2}s — reconnecting"
        sendEvent(name: "connectionStatus", value: "disconnected")
        unschedule("refresh")
        runIn(1, "connect")
        return
    }
    requestACStatus()
    runIn(1, "requestZoneStatus")
    runIn(2, "requestConsoleVersion")
}

// ── Outbound requests ────────────────────────────────────────────────────────

def requestACStatus()       { sendControlMsg(0x23, [], []) }
def requestZoneStatus()     { sendControlMsg(0x21, [], []) }
def requestConsoleVersion() { transmit(0x90, 0xB0, 0x1F, [0xFF, 0x30]) }
def requestZoneNames()      { transmit(0x90, 0xB0, 0x1F, [0xFF, 0x13]) }
def requestACAbility()      { transmit(0x90, 0xB0, 0x1F, [0xFF, 0x11]) }
def requestACError()        { transmit(0x90, 0xB0, 0x1F, [0xFF, 0x10, acNum()]) }

// ── Message builders ─────────────────────────────────────────────────────────

// Build and send a 0xC0 control/status message.
// normalData  – byte list for the fixed section
// repeatList  – list of byte-lists, one entry per repeat unit
def sendControlMsg(int subType, List normalData, List repeatList) {
    def nLen = normalData.size()
    def rLen = repeatList.size() > 0 ? repeatList[0].size() : 0
    def rCnt = repeatList.size()

    def subHdr = [subType, 0x00,
                  0x00, nLen,
                  (rLen >> 8) & 0xFF, rLen & 0xFF,
                  (rCnt >> 8) & 0xFF, rCnt & 0xFF]

    def data = subHdr + normalData
    repeatList.each { data += it }
    transmit(0x80, 0xB0, 0xC0, data)
}

def transmit(int addrHi, int addrLo, int msgType, List data) {
    def id  = nextMsgId()
    def len = data.size()

    // Payload used for CRC: address + id + type + dataLen (2 bytes) + data
    def payload = [addrHi, addrLo, id, msgType, (len >> 8) & 0xFF, len & 0xFF] + data

    def crc    = crc16Modbus(payload)
    def rawMsg = payload + [(crc >> 8) & 0xFF, crc & 0xFF]

    // Full wire frame: header + payload-with-redundant-bytes + CRC-with-redundant-bytes
    def wire = [0x55, 0x55, 0x55, 0xAA] + addRedundantBytes(rawMsg)
    def hex  = wire.collect { sprintf("%02X", it & 0xFF) }.join("")
    logDebug "TX: ${hex}"
    interfaces.rawSocket.sendMessage(hex)
}

// Insert 0x00 after every run of three consecutive 0x55 bytes.
List addRedundantBytes(List bytes) {
    def result = []; int cnt = 0
    bytes.each { b ->
        result.add(b)
        cnt = ((b & 0xFF) == 0x55) ? cnt + 1 : 0
        if (cnt == 3) { result.add(0x00); cnt = 0 }
    }
    return result
}

int nextMsgId() {
    state.msgId = ((state.msgId ?: 0) + 1) & 0xFF
    return state.msgId
}

// ── Receive pipeline ─────────────────────────────────────────────────────────

def parse(String hexChunk) {
    logDebug "RX chunk: ${hexChunk}"
    state.lastRxTime = now()
    state.rxHex = (state.rxHex ?: "") + hexChunk
    processRxBuffer()
}

def processRxBuffer() {
    def hex = state.rxHex ?: ""

    while (hex.length() >= 16) {
        // Locate header 55 55 55 AA or 55 55 55 AB
        def hiAA = hex.indexOf("555555AA")
        def hiAB = hex.indexOf("555555AB")
        int hi
        if      (hiAA < 0 && hiAB < 0) { hex = hex.length() > 6 ? hex.substring(hex.length() - 6) : hex; break }
        else if (hiAA < 0) hi = hiAB
        else if (hiAB < 0) hi = hiAA
        else                hi = Math.min(hiAA, hiAB)
        if (hi > 0) hex = hex.substring(hi)

        // AB frames are envelopes: 55 55 55 AB | 00 00 00 | NN | 00 NN | [NN bytes of inner AA messages]
        // Strip the 10-byte AB wrapper and let the loop parse the inner AA messages normally.
        if (hex.startsWith("555555AB")) {
            if (hex.length() < 20) break                                    // need 10 bytes to read length
            int innerLen = Integer.parseInt(hex.substring(16, 20), 16)      // 2-byte length word at offset 8-9
            if (hex.length() < 20 + innerLen * 2) break                    // incomplete frame, wait for more data
            hex = hex.substring(20)                                         // strip the AB framing, expose inner content
            continue
        }

        // Regular 55 55 55 AA message
        def rawAfterHdr = hexToBytes(hex.substring(8))

        // Remove redundant bytes so we can read the length field
        def cleaned = stripRedundantBytes(rawAfterHdr)
        if (cleaned.size() < 6) break   // addr(2)+id(1)+type(1)+len(2)

        def dataLen  = ((cleaned[4] & 0xFF) << 8) | (cleaned[5] & 0xFF)
        def msgClean = 2 + 1 + 1 + 2 + dataLen + 2   // total cleaned bytes for one message

        if (cleaned.size() < msgClean) break   // wait for more data

        def msgBytes = cleaned.subList(0, msgClean)

        // Verify CRC16 MODBUS over everything except the trailing 2 CRC bytes.
        // AirTouch 5 sends CRC high byte first, then low byte.
        def calc = crc16Modbus(msgBytes.subList(0, msgClean - 2))
        def recv = ((msgBytes[msgClean - 2] & 0xFF) << 8) | (msgBytes[msgClean - 1] & 0xFF)
        if (calc == recv) {
            parseMessage(msgBytes)
        } else {
            log.warn "CRC mismatch: calc=${sprintf('%04X', calc)} recv=${sprintf('%04X', recv)} bytes=${bytesToHex(msgBytes)}"
        }

        // Advance the raw hex buffer past the consumed message
        def rawConsumed = countRawForCleaned(rawAfterHdr, msgClean)
        hex = bytesToHex(hexToBytes(hex.substring(8)).subList(rawConsumed, rawAfterHdr.size()))
    }

    state.rxHex = hex
}

// Strip the 0x00 redundant byte that follows every 3-consecutive 0x55 sequence.
List stripRedundantBytes(List bytes) {
    def result = []; int cnt = 0; boolean skip = false
    bytes.each { b ->
        def bv = b & 0xFF
        if (skip) {
            skip = false
            cnt  = (bv == 0x55) ? 1 : 0
            if (bv != 0x00) result.add(bv)  // safety: include if not the expected 0x00
            return
        }
        result.add(bv)
        cnt = (bv == 0x55) ? cnt + 1 : 0
        if (cnt == 3) { skip = true; cnt = 0 }
    }
    return result
}

// Count how many raw bytes (with redundant) correspond to 'target' cleaned bytes.
int countRawForCleaned(List rawBytes, int target) {
    int cleaned = 0; int cnt = 0; boolean skip = false
    for (int i = 0; i < rawBytes.size(); i++) {
        def bv = rawBytes[i] & 0xFF
        if (skip) {
            skip = false
            cnt  = (bv == 0x55) ? 1 : 0
            if (bv != 0x00) { cleaned++; if (cleaned >= target) return i + 1 }
            continue
        }
        cleaned++
        cnt = (bv == 0x55) ? cnt + 1 : 0
        if (cnt == 3) { skip = true; cnt = 0 }
        if (cleaned >= target) return i + 1
    }
    return rawBytes.size()
}

// ── Message parsers ───────────────────────────────────────────────────────────

def parseMessage(List bytes) {
    // bytes layout (cleaned): addr(2) id(1) type(1) len(2) data(n) crc(2)
    def msgType = bytes[3] & 0xFF
    def dataLen = ((bytes[4] & 0xFF) << 8) | (bytes[5] & 0xFF)
    def data    = bytes.subList(6, 6 + dataLen)
    logDebug "MSG type=0x${sprintf('%02X', msgType)} dataLen=${dataLen}"

    if      (msgType == 0xC0) parseControlMsg(data)
    else if (msgType == 0x1F) parseExtendedMsg(data)
    else    log.warn "Unhandled msg type=0x${sprintf('%02X', msgType)}"
}

def parseControlMsg(List data) {
    if (data.size() < 8) return
    def subType  = data[0] & 0xFF
    def nLen     = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF)
    def rLen     = ((data[4] & 0xFF) << 8) | (data[5] & 0xFF)
    def rCnt     = ((data[6] & 0xFF) << 8) | (data[7] & 0xFF)
    def subData  = data.subList(8, data.size())
    logDebug "Control subType=0x${sprintf('%02X', subType)} nLen=${nLen} rLen=${rLen} rCnt=${rCnt}"

    if      (subType == 0x23) parseACStatus(subData, nLen, rLen, rCnt)
    else if (subType == 0x21) parseZoneStatus(subData, nLen, rLen, rCnt)
    else if (subType == 0x45) parseSystemInfo(subData, nLen)
    else    log.warn "Unhandled control subType=0x${sprintf('%02X', subType)} (ignored)"
}

def parseACStatus(List data, int nLen, int rLen, int rCnt) {
    def myAC = acNum(); int offset = nLen
    logDebug "parseACStatus: myAC=${myAC} rCnt=${rCnt} rLen=${rLen} dataSize=${data.size()}"
    rCnt.times {
        if (offset + rLen > data.size()) { log.warn "parseACStatus: ran out of data at offset=${offset}"; return }
        def d = data.subList(offset, offset + rLen); offset += rLen

        def b1         = d[0] & 0xFF
        def powerNib   = (b1 >> 4) & 0x0F
        def thisAC     = b1 & 0x0F
        logDebug "AC entry: thisAC=${thisAC} powerNib=0x${sprintf('%01X', powerNib)} b1=0x${sprintf('%02X', b1)} b2=0x${sprintf('%02X', d[1] & 0xFF)}"
        if (thisAC != myAC) { logDebug "Skipping AC${thisAC} (looking for AC${myAC})"; return }

        def b2         = d[1] & 0xFF
        def modeNib    = (b2 >> 4) & 0x0F
        def fanNib     = b2 & 0x0F
        def spRaw      = d[2] & 0xFF
        def sp         = (spRaw + 100) / 10.0
        def tempRaw    = ((d[4] & 0xFF) << 8) | (d[5] & 0xFF)
        def tempValid  = (tempRaw >= 0 && tempRaw <= 2000)
        def temp       = (tempRaw - 500) / 10.0

        def swVal   = (powerNib in [0x01, 0x03, 0x05]) ? "on" : "off"

        def modeStr
        switch (modeNib) {
            case 0x00: modeStr = "auto";      break
            case 0x01: modeStr = "heat";      break
            case 0x02: modeStr = "dry";       break
            case 0x03: modeStr = "fan";       break
            case 0x04: modeStr = "cool";      break
            case 0x08: modeStr = "auto heat"; break
            case 0x09: modeStr = "auto cool"; break
            default:   modeStr = "unknown"
        }

        def fanStr
        if (fanNib >= 0x09 && fanNib <= 0x0E) {
            fanStr = "intelligent auto"
        } else {
            switch (fanNib) {
                case 0x00: fanStr = "auto";      break
                case 0x01: fanStr = "quiet";     break
                case 0x02: fanStr = "low";       break
                case 0x03: fanStr = "medium";    break
                case 0x04: fanStr = "high";      break
                case 0x05: fanStr = "powerful";  break
                case 0x06: fanStr = "turbo";     break
                default:   fanStr = "unknown"
            }
        }

        logDebug "AC${thisAC}: sw=${swVal} mode=${modeStr} fan=${fanStr} sp=${sp} temp=${tempValid ? temp : 'N/A'}"
        sendEvent(name: "switch",      value: swVal)
        sendEvent(name: "powerState",  value: powerStateStr(powerNib))
        sendEvent(name: "acMode",      value: modeStr)
        sendEvent(name: "acFanSpeed",  value: fanStr)
        sendEvent(name: "setpoint",    value: sp)
        if (tempValid) sendEvent(name: "temperature", value: temp, unit: "°C")
    }
}

def parseZoneStatus(List data, int nLen, int rLen, int rCnt) {
    int offset = nLen
    rCnt.times {
        if (offset + rLen > data.size()) return
        def d = data.subList(offset, offset + rLen); offset += rLen

        def b1        = d[0] & 0xFF
        def pwrBits   = (b1 >> 6) & 0x03     // bits 8-7
        def zoneNum   = b1 & 0x3F            // bits 6-1

        def b2        = d[1] & 0xFF
        def tempCtrl  = (b2 >> 7) & 0x01     // bit 8: 1 = temperature control
        def openPct   = b2 & 0x7F            // bits 7-1

        def spRaw     = d[2] & 0xFF
        def sp        = (spRaw != 0xFF) ? ((spRaw + 100) / 10.0) as double : null

        def b4        = d[3] & 0xFF
        def hasSensor = ((b4 >> 7) & 0x01) == 1

        def tempRaw   = ((d[4] & 0xFF) << 8) | (d[5] & 0xFF)
        def temp      = (tempRaw - 500) / 10.0
        def tempValid = hasSensor && (tempRaw <= 2000)

        def b7        = d[6] & 0xFF
        def spill     = ((b7 >> 1) & 0x01) == 1
        def lowBatt   = (b7 & 0x01) == 1

        def pwrStr    = (pwrBits == 0x01) ? "on" : (pwrBits == 0x03) ? "turbo" : "off"
        logDebug "Zone${zoneNum}: ${pwrStr} pct=${openPct}% tempCtrl=${tempCtrl as boolean} sp=${sp} temp=${tempValid ? temp : 'N/A'}"

        updateZoneChild(zoneNum, pwrStr, tempValid ? temp : null, sp, openPct, tempCtrl == 1)
    }
    // Request zone names once per connection (names don't change at runtime).
    if (!state.zoneNamesFetched) {
        state.zoneNamesFetched = true
        requestZoneNames()
    }
}

def parseExtendedMsg(List data) {
    if (data.size() < 2) return
    def cmd1 = data[0] & 0xFF
    def cmd2 = data[1] & 0xFF

    if (cmd1 == 0xFF && cmd2 == 0x30 && data.size() >= 4) {
        def strLen = data[3] & 0xFF
        if (data.size() >= 4 + strLen) {
            def ver = data.subList(4, 4 + strLen).collect { (char)(it & 0xFF) }.join("")
            logDebug "Console version: ${ver}"
            sendEvent(name: "consoleVersion", value: ver)
        }
    } else if (cmd1 == 0xFF && cmd2 == 0x13) {
        parseZoneNames(data.subList(2, data.size()))
    } else if (cmd1 == 0xFF && cmd2 == 0x11) {
        parseACAbility(data.subList(2, data.size()))
    } else if (cmd1 == 0xFF && cmd2 == 0x10) {
        parseACError(data.subList(2, data.size()))
    }
}

def parseACAbility(List data) {
    // 0xFF 0x11: AC ability — one entry per AC: acNum(1) + dataLen(1) + data(dataLen)
    // AC name is first 16 bytes of data (null-terminated).
    int offset = 0
    while (offset + 2 <= data.size()) {
        def acNo   = data[offset] & 0xFF
        def dLen   = data[offset + 1] & 0xFF
        offset += 2
        if (offset + dLen > data.size()) break
        def entry  = data.subList(offset, offset + dLen); offset += dLen
        def name   = entry.subList(0, Math.min(16, entry.size())).collect { (char)(it & 0xFF) }.join("").replaceAll(/\x00.*/, "").trim()
        def startZ = dLen >= 17 ? (entry[16] & 0xFF) : -1
        def zCount = dLen >= 18 ? (entry[17] & 0xFF) : -1
        log.info "AC${acNo} ability: name='${name}' startZone=${startZ} zoneCount=${zCount}"
    }
    // Handshake complete — now request current state and kick off zone name fetch.
    requestACStatus()
    runIn(1, "requestZoneStatus")
    runIn(2, "requestConsoleVersion")
    runIn(3, "requestACError")
}

def parseACError(List data) {
    // 0xFF 0x10: AC error info — acNum(1) + errLen(1) + errString(errLen)
    if (data.size() < 2) return
    def acNo   = data[0] & 0xFF
    def errLen = data[1] & 0xFF
    if (errLen == 0) {
        log.info "AC${acNo} error: none"
    } else {
        def msg = data.subList(2, Math.min(2 + errLen, data.size())).collect { (char)(it & 0xFF) }.join("")
        log.warn "AC${acNo} error: '${msg}'"
    }
}

def parseSystemInfo(List data, int nLen) {
    // Sub-type 0x45: AirTouch 5 pushes system/console info on new connection.
    // Normal section starts with a null-terminated console name (first 8 bytes).
    if (data.size() < 1) return
    def end = Math.min(8, Math.min(nLen, data.size()))
    def name = data.subList(0, end).collect { (char)(it & 0xFF) }.join("").replaceAll(/\x00.*/, "").trim()
    if (name) log.info "AirTouch 5 console name: ${name}"
}

def parseZoneNames(List data) {
    int offset = 0
    while (offset + 2 <= data.size()) {
        def zoneNum = data[offset] & 0xFF
        def nameLen = data[offset + 1] & 0xFF
        offset += 2
        if (offset + nameLen > data.size()) break
        def name = data.subList(offset, offset + nameLen).collect { (char)(it & 0xFF) }.join("")
        offset += nameLen
        logDebug "Zone ${zoneNum} name: '${name}'"
        updateZoneChildName(zoneNum, name)
    }
}

// ── Zone child devices ────────────────────────────────────────────────────────

def updateZoneChild(int zoneNum, String power, temp, sp, int pct, boolean tempCtrl) {
    def dni   = "${device.deviceNetworkId}-zone${zoneNum}"
    def child = getChildDevice(dni)
    if (!child) {
        try {
            child = addChildDevice("airtouch5", "AirTouch 5 Zone", dni,
                [name: "Zone ${zoneNum}", label: "AirTouch Zone ${zoneNum}", isComponent: true])
            log.info "Created child device for zone ${zoneNum}"
        } catch (e) {
            log.error "Could not create zone ${zoneNum} child device: ${e}"
            return
        }
    }
    child.sendEvent(name: "switch",         value: (power in ["on", "turbo"]) ? "on" : "off")
    child.sendEvent(name: "controlMethod",  value: tempCtrl ? "temperature" : "percentage")
    child.sendEvent(name: "openPercentage", value: pct)
    if (temp  != null) child.sendEvent(name: "temperature", value: temp, unit: "°C")
    if (sp    != null) child.sendEvent(name: "setpoint",    value: sp)
}

def updateZoneChildName(int zoneNum, String name) {
    if (!name) return
    def dni   = "${device.deviceNetworkId}-zone${zoneNum}"
    def child = getChildDevice(dni)
    if (!child) return
    child.sendEvent(name: "zoneName", value: name)
    child.setLabel(name)
}

// ── CRC-16 MODBUS ────────────────────────────────────────────────────────────

int crc16Modbus(List bytes) {
    int crc = 0xFFFF
    bytes.each { b ->
        crc ^= (b & 0xFF)
        8.times { crc = (crc & 1) ? ((crc >>> 1) ^ 0xA001) : (crc >>> 1) }
    }
    return crc & 0xFFFF
}

// ── Helpers ───────────────────────────────────────────────────────────────────

int acNum() { (settings.acIndex ?: 0).toInteger() & 0x0F }

List hexToBytes(String hex) {
    def r = []
    for (int i = 0; i + 1 < hex.length(); i += 2)
        r.add(Integer.parseInt(hex.substring(i, i + 2), 16))
    return r
}

String bytesToHex(List bytes) {
    bytes.collect { sprintf("%02X", it & 0xFF) }.join("")
}

String powerStateStr(int nib) {
    switch (nib) {
        case 0x01: return "on"
        case 0x02: return "away (off)"
        case 0x03: return "away (on)"
        case 0x05: return "sleep"
        default:   return "off"
    }
}

void logDebug(String msg) { if (settings.logEnable) log.debug msg }
