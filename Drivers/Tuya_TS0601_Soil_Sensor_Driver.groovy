/**
 * Tuya TS0601 Soil Moisture Sensor Driver
 *
 * Author: George Gilman (ggilman@gmail.com)
 * Date: 2025-05-24
 *
 * This driver parses data from the specific Tuya TS0601 soil moisture sensor,
 * focusing on interpreting the proprietary Tuya EF00 cluster.
 *
 * Clusters identified:
 * inClusters: 0004 (On/Off), 0005 (Scenes), EF00 (Tuya Private), 0000 (Basic), ED00 (Tuya Private)
 * outClusters: 0019 (OTA), 000A (Time)
 *
 * Confirmed Tuya Data Points (DP_IDs):
 * - DPID 15 (0x0F): Battery Percentage (value: 0-100)
 * - DPID 14 (0x0E): Battery State (enum: 0=low, 1=medium, 2=high)
 * - DPID 3 (0x03): Battery Voltage (value: mV)
 * - DPID 9 (0x09): Temperature Scale (0=Celsius, 1=Fahrenheit)
 * - DPID 101 (0x65): Illuminance (in Lux)
 * - DPID 5 (0x05):  Soil Moisture (value: percentage * 100)
 * - DPID 110 (0x6e): Soil Temperature (value: Celsius/Fahrenheit * 10)
 *
 * Obsolete/Redundant DPIDs (might appear but are not actively used for events in this driver):
 * - DPID 113 (0x71): Previously thought to be Soil Moisture
 * - DPID 112 (0x70): Previously thought to be Soil Temperature
 */

metadata {
    definition (name: "Tuya TS0601 Soil Moisture Sensor", namespace: "ggilman", author: "George Gilman", importUrl: null) {
        capability "Battery"
        capability "Refresh"
        capability "TemperatureMeasurement"
        capability "IlluminanceMeasurement"
        capability "Configuration"
        capability "Health Check"

        attribute 'healthStatus', 'enum', ['unknown', 'offline', 'online']
        attribute 'soilMoisture', 'number' // Custom attribute for soil moisture
        attribute 'batteryState', 'enum', ['unknown', 'low', 'medium', 'high'] // Custom attribute for battery state
        attribute 'batteryVoltage', 'number' // NEW: Custom attribute for battery voltage

        fingerprint profileId:"0104", endpointId:"01", inClusters:"0004,0005,EF00,0000,ED00", outClusters:"0019,000A", model:"TS0601", manufacturer:"_TZE284_sgabhwa6", controllerType: "ZGB"
    }

    preferences {
        input(name: 'temperatureOffset', type: 'decimal', title: '<b>Temperature offset</b>', description: 'Adjust the reported temperature value.', defaultValue: 0.0, range: '-100.0..100.0')
        input(name: 'humidityOffset', type: 'decimal', title: '<b>Humidity offset</b>', description: 'Adjust the reported humidity/moisture percentage.', defaultValue: 0.0, range: '-100.0..100.0')
        input(name: 'logEnable', type: 'bool', title: '<b>Enable debug logging</b>', description: 'Enable debug logging for 30 minutes.', defaultValue: true)
        input(name: 'txtEnable', type: 'bool', title: '<b>Enable description text logging</b>', description: 'Display measured values in Hubitat log page.', defaultValue: true)
    }
}

// Global variable for how many health checks to miss before marking offline
// This threshold is initialized in initializeVars to avoid static/Field issues.

void installed() {
    log.debug "Installing Tuya TS0601 Soil Moisture Sensor..."
    sendEvent(name: "soilMoisture", value: "unknown")
    sendEvent(name: "battery", value: "unknown")
    sendEvent(name: "batteryState", value: "unknown")
    sendEvent(name: "batteryVoltage", value: "unknown")
    sendEvent(name: "temperature", value: "unknown")
    sendEvent(name: "illuminance", value: "unknown")
    initializeVars(true) // Initialize variables, including healthStatus
    configure()
}

void configure() {
    log.debug "Configuring Tuya TS0601 Soil Moisture Sensor..."
    List<hubitat.device.HubAction> actions = []

    actions.addAll(zigbee.readAttribute(0x0000, 0x0001, null, 200)) // Read ZCL Version
    actions.addAll(zigbee.readAttribute(0x0000, 0x0004, null, 200)) // Read Manufacturer Name
    actions.addAll(zigbee.readAttribute(0x0000, 0x0005, null, 200)) // Read Model Identifier

    if (actions) {
        sendHubCommand(new hubitat.device.HubMultiAction(actions))
        log.info "Configuration commands sent. Monitor logs for incoming data."
    } else {
        log.warn "No configuration commands to send."
    }

    if (settings.logEnable) {
        runIn(1800, 'logsOff', [overwrite: true, misfire: 'ignore'])
        log.info 'Debug logging will be turned off in 30 minutes'
    }

    scheduleDeviceHealthCheck() // Schedule periodic health check after configuration
}

void refresh() {
    log.debug "Refreshing Tuya TS0601 Soil Moisture Sensor..."
    List<hubitat.device.HubAction> actions = []

    actions.addAll(zigbee.readAttribute(0x0001, 0x0021, null, 200)) // Read Battery Percentage
    actions.addAll(zigbee.readAttribute(0x0402, 0x0000, null, 200)) // Read Temperature (Standard cluster, may not be used by this sensor)
    actions.addAll(zigbee.readAttribute(0x0405, 0x0000, null, 200)) // Read Humidity (Standard cluster, may not be used by this sensor)
    actions.addAll(zigbee.command(0xEF00, 0x03)) // Tuya specific query for all DPs

    if (actions) {
        sendHubCommand(new hubitat.device.HubMultiAction(actions))
        log.info "Refresh command sent. Device will report when it determines it needs to or on next check-in."
    } else {
        log.warn "No refresh commands to send."
    }
}

void ping() {
    log.debug "Ping received. For battery-powered Zigbee devices, this generally initiates a refresh."
    refresh()
}

void parse(String description) {
    log.debug "Parsing description: ${description}"
    setPresent() // Reset health check counter on any incoming data
    def cluster = zigbee.parse(description)

    if (!cluster) {
        log.warn "Failed to parse Zigbee description: ${description}"
        return // Exit early to prevent further errors
    }

    log.debug "Parsed cluster: ${cluster}"

    if (cluster.clusterId == 0xEF00 && (cluster.command in [0x01, 0x02, 0x05, 0x06])) {
        processTuyaEF00Data(cluster.data, cluster.command)
    } else if (cluster.clusterId == 0x0000 && cluster.command == 0x0A) {
        // This is a Basic Cluster Report Attributes message (0x0A)
        parseBasicClusterData(cluster)
    } else if (cluster.clusterId == 0x0000 && cluster.command == 0x0B) {
         log.debug "ZCL Default Response: Command: ${cluster.data[0]}, Status: ${cluster.data[1]}"
    } else {
        // For unhandled messages in the 'else' block, use msgMap from parseDescriptionAsMap for safe parsing.
        // This handles cases where zigbee.parse() might not expose attrId/value as properties.
        def msgMap = zigbee.parseDescriptionAsMap(description) // Re-parse into a plain Map for safety

        String clusterIdHex = (msgMap.clusterId instanceof Integer) ? String.format('%04x', msgMap.clusterId) : 'N/A'
        String commandHex = (msgMap.command instanceof Integer) ? String.format('%02x', msgMap.command) : 'N/A'
        String attrIdHex = 'N/A'
        String valueStr = 'N/A'

        // Use get() for safe access to keys that might not exist as properties
        def parsedAttrId = msgMap.get('attrId')
        def parsedValue = msgMap.get('value')

        if (parsedAttrId != null) {
            attrIdHex = (parsedAttrId instanceof Integer) ? String.format('%04x', parsedAttrId) : parsedAttrId.toString()
        }
        if (parsedValue != null) {
            valueStr = parsedValue.toString()
        }

        if (attrIdHex != 'N/A' || valueStr != 'N/A') { // Log if we found either attrId or value
            if (settings?.txtEnable) { log.debug "General parsed Zigbee message: Cluster ID: ${clusterIdHex}, Command: ${commandHex}, Attr ID: ${attrIdHex}, Value: ${valueStr}" }
        } else { // Fallback to logging the full parsed map if nothing specific was found
            if (settings?.txtEnable) { log.debug "Unhandled parsed Zigbee message type. Cluster ID: ${clusterIdHex}, Command: ${commandHex}, Raw Description: ${description}, Parsed Map: ${msgMap}" }
        }
    }
}

private void processTuyaEF00Data(List<Integer> data, int command) {
    log.debug "Parsing Tuya EF00 data: ${data.collect { String.format('%02x', it) }.join(' ')}"

    int i = 2 // Start after the initial two bytes (often unknown + transaction ID)
    while (i < data.size() - 3) {
        int dp = data[i]
        int dataType = data[i+1]
        int dataLength = (data[i+2] << 8) + data[i+3]

        if (i + 4 + dataLength > data.size()) {
            log.warn "Tuya EF00 data parsing error: dataLength exceeds buffer. Index: ${i}, DPID: ${dp}, Length: ${dataLength}"
            break
        }

        List<Integer> valueBytes = data.subList(i + 4, i + 4 + dataLength)
        long fncmd = parseTuyaValue(dataType, valueBytes)

        log.debug "DP_ID: ${String.format('%02x', dp)} (${dp}), DataType: ${String.format('%02x', dataType)}, Length: ${dataLength}, Value: ${fncmd}"

        switch (dp) {
            case 15: // 0x0F - Battery Percentage
                sendEvent(name: "battery", value: (int)fncmd, unit: "%", descriptionText: "Battery is ${(int)fncmd}%")
                if (settings.txtEnable) { log.info "Battery (DPID 15): ${fncmd}%" }
                break
            case 14: // 0x0E - Battery State
                String stateValue = "unknown"
                if (fncmd == 0) { stateValue = "low" }
                else if (fncmd == 1) { stateValue = "medium" }
                else if (fncmd == 2) { stateValue = "high" }
                sendEvent(name: "batteryState", value: stateValue, descriptionText: "Battery State is ${stateValue}")
                if (settings.txtEnable) { log.info "Battery State (DPID 14): ${stateValue} (raw: ${fncmd})" }
                break
            case 3: // 0x03 - Battery Voltage (mV)
                long signedFncmdVoltage = convertUnsignedToSigned(fncmd)
                // Explicitly cast to BigDecimal to ensure methods are found
                BigDecimal voltageV = new BigDecimal(signedFncmdVoltage) / new BigDecimal(1000.0)
                sendEvent(name: "batteryVoltage", value: (voltageV * 100).round() / 100.0, unit: "V", descriptionText: "Battery Voltage is ${((voltageV * 100).round() / 100.0)}V")
                if (settings.txtEnable) { log.info "Battery Voltage (DPID 3): ${((voltageV * 100).round() / 100.0)}V (raw: ${fncmd})" }
                break
            case 9: // 0x09 - Temperature Scale (0=C, 1=F)
                def scaleUnit = (fncmd == 1) ? "F" : "C"
                device.updateDataValue("temperatureUnit", scaleUnit) // Stores the preferred unit on the device data
                if (settings.txtEnable) { log.info "Temperature Scale (DPID 9): ${scaleUnit}" }
                break
            case 101: // 0x65 - Illuminance (in Lux)
                sendEvent(name: "illuminance", value: (int)fncmd, unit: "Lux", descriptionText: "Illuminance is ${(int)fncmd} Lux")
                if (settings.txtEnable) { log.info "Illuminance (DPID 101): ${fncmd} Lux" }
                break
            case 5:    // 0x05 - Soil Moisture
                long signedFncmdMoisture = convertUnsignedToSigned(fncmd)
                def moisturePercent = new BigDecimal(signedFncmdMoisture) / new BigDecimal(100.0)
                def finalMoisture = ((moisturePercent + safeToDouble(settings?.humidityOffset)) * 10).round() / 10.0
                sendEvent(name: "soilMoisture", value: finalMoisture, unit: "%", descriptionText: "Soil Moisture is ${finalMoisture}%")
                if (settings.txtEnable) { log.info "Soil Moisture (DPID 5): ${finalMoisture}%" }
                break
            case 110:  // 0x6e - Soil Temperature
                 long signedFncmdTemp = convertUnsignedToSigned(fncmd)
                 def temperature = new BigDecimal(signedFncmdTemp) / new BigDecimal(10.0);
                 def tempUnit = device.getDataValue("temperatureUnit") ?: location.temperatureScale
                 if (tempUnit == "C" && location.temperatureScale == "F") {
                     temperature = (temperature * 1.8) + 32
                 } else if (tempUnit == "F" && location.temperatureScale == "C") {
                     temperature = (temperature - 32) / 1.8
                 }
                 def finalTemperature = ((temperature + safeToDouble(settings?.temperatureOffset)) * 10).round() / 10.0
                 sendEvent(name: "temperature", value: finalTemperature, unit: "°${location.temperatureScale}", descriptionText: "Soil Temperature is ${finalTemperature}°${location.temperatureScale}")
                 if (settings.txtEnable) { log.info "Soil Temperature (DPID 110): ${finalTemperature}°${location.temperatureScale}" }
                 break;
            case 112: // 0x70 - Previously thought to be Soil Temperature, now logged as obsolete.
            case 113: // 0x71 - Previously thought to be Soil Moisture, now logged as obsolete.
                if (settings.txtEnable) { log.debug "Obsolete/Redundant Tuya EF00 DP_ID: ${dp}, Raw Value: ${fncmd}. New mapping used." }
                break
            default: // Other unhandled DPIDs (e.g., 102, 103, 104, 107, 108, 109, 111)
                if (settings.txtEnable) { log.debug "Unhandled Tuya EF00 DP_ID: ${dp}, Raw Value: ${fncmd}" }
                break
        }
        i += (4 + dataLength)
    }
}

// Handles parsing of Basic Cluster (0x0000) attribute reports.
void parseBasicClusterData(cluster) {
    try {
        // Use get() for safer access to attrId and value
        Integer attrId = cluster.get('attrId') instanceof Integer ? cluster.get('attrId') : null
        Object value = cluster.get('value') // Value can be various types

        if (attrId == 0x0001 && value != null) { // ZCL Version
            if (settings.txtEnable) { log.info "ZCL Version: ${value}" }
        } else if (attrId == 0x0021 && value != null) { // Standard Battery Percentage Remaining
            def batteryPercent = value as Integer
            sendEvent(name: "battery", value: batteryPercent, unit: "%", descriptionText: "Battery is ${batteryPercent}% (Zigbee Cluster 0x0021)")
            if (settings.txtEnable) { log.info "Battery (Zigbee 0x0021): ${batteryPercent}%" }
        } else if (attrId == 0x0020 && value != null) { // Standard Battery Voltage
            def batteryVoltage = (value as Integer) / 10.0 // Typically mV, so /10.0 for V
            if (settings.txtEnable) { log.info "Battery Voltage (Zigbee 0x0020): ${batteryVoltage}V" }
        } else if ((attrId == 0xFFE2 || attrId == 0xFFE4) && value != null) { // Common Tuya custom battery attributes
            def batteryRawValue = value as Integer
            if (settings.txtEnable) { log.info "Raw battery value from Basic Cluster (FFE2/FFE4): ${batteryRawValue} (needs interpretation)" }
        } else {
            // Log with N/A if attrId or value are not present
            if (settings.txtEnable) { log.debug "Unhandled Basic Cluster attribute: Attr ID: ${attrId != null ? String.format('%04x', attrId) : 'N/A'}, Value: ${value}, Raw Map: ${cluster}" }
        }

        if (cluster.additionalAttrs) {
            cluster.additionalAttrs.each { attr ->
                Integer addAttrId = attr.get('attrId') instanceof Integer ? attr.get('attrId') : null // Use get() here too
                Object addAttrValue = attr.get('value') // Use get() here too
                if ((addAttrId == 0xFFE2 || addAttrId == 0xFFE4) && addAttrValue != null) {
                    def batteryRawValue = addAttrValue as Integer
                    if (settings.txtEnable) { log.info "Raw battery value from Basic Cluster additionalAttrs (FFE2/FFE4): ${batteryRawValue} (needs interpretation)" }
                } else {
                    if (settings.txtEnable) { log.debug "Unhandled additional Basic Cluster attribute: Attr ID: ${addAttrId != null ? String.format('%04x', addAttrId) : 'N/A'}, Value: ${addAttrValue}" }
                }
            }
        }
    } catch (e) {
        log.error "Error parsing Basic Cluster data: ${e.message}. Cluster map: ${cluster}"
    }
}

long parseTuyaValue(int dataType, List<Integer> bytes) {
    long value = 0
    switch (dataType) {
        case 0x00: // Raw bytes
        case 0x01: // Boolean (0 or 1)
        case 0x04: // Enum (single byte 0-255)
        case 0x05: // Bitmap (1, 2, or 4 bytes as bitfield)
            value = bytes.size() > 0 ? (long)bytes[0] : 0
            break
        case 0x02: // Value (4-byte integer)
            value = parse4ByteUnsigned(bytes)
            break
        case 0x03: // String (variable length)
            value = 0 // Not typically used for sensor numerical values
            break
        default:
            log.warn "Unknown Tuya Data Type: ${String.format('%02x', dataType)}"
            break
    }
    return value
}

// Converts a 4-byte unsigned integer to a signed long.
// This is necessary because some Tuya devices send negative temperatures
// or other values as large unsigned integers.
long convertUnsignedToSigned(long unsignedValue) {
    if (unsignedValue > 0x7FFFFFFF) { // If the value is greater than max 32-bit signed int (2,147,483,647)
        return unsignedValue - 0x100000000L // Subtract 2^32 (4,294,967,296)
    }
    return unsignedValue
}

long parse4ByteUnsigned(List<Integer> bytes) {
    long value = 0
    if (bytes.size() == 4) {
        value = ((long) (bytes[0] & 0xFF) << 24) |
                ((long) (bytes[1] & 0xFF) << 16) |
                ((long) (bytes[2] & 0xFF) << 8) |
                ((long) (bytes[3] & 0xFF))
    } else {
        log.warn "Expected 4 bytes for value, got ${bytes.size()}. Bytes: ${bytes.collect { String.format('%02x', it) }.join(' ')}"
    }
    return value
}

// Initializes device state variables and preferences.
void initializeVars(boolean fullInit = true) {
    if (fullInit) {
        log.info "Initializing all device states and preferences to defaults."
        // Set default preferences if they are null (first install or full re-init)
        device.updateSetting('temperatureOffset', [value:0.0, type:'decimal'])
        device.updateSetting('humidityOffset', [value:0.0, type:'decimal'])
        device.updateSetting('logEnable', [value:true, type:'bool'])
        device.updateSetting('txtEnable', [value:true, type:'bool'])

        // Clear all existing state variables on full initialization
        state.clear()
        // Initialize state variables for health check and reporting timestamps
        state.notPresentCounter = 0
        state.lastTempTime = now()
        state.lastHumiTime = now()
        // Initialize the presence threshold in state
        state.presenceCountThreshold = 4 // How many health checks to miss before offline

        // Set initial health status
        sendHealthStatusEvent('unknown')
    }
    // Always update temperature unit based on hub's setting
    device.updateDataValue("temperatureUnit", location.temperatureScale)
    log.debug "Initialization complete."
}

// Schedules the periodic device health check.
void scheduleDeviceHealthCheck() {
    // Randomize the minute and hour to spread out hub workload for multiple devices
    Random rnd = new Random()
    // Schedule using cron format directly with randomized hour/minute (7 fields for Quartz Cron)
    schedule("0 ${rnd.nextInt(59)} ${rnd.nextInt(24)} * * ? *", 'deviceHealthCheck')
    log.debug "Device health check scheduled."
}

// Method called by the scheduled health check job.
void deviceHealthCheck() {
    state.notPresentCounter = (state.notPresentCounter ?: 0) + 1
    if (state.notPresentCounter > state.presenceCountThreshold) { // Use state.presenceCountThreshold
        if ((device.currentValue('healthStatus', true) ?: 'unknown') != 'offline' ) {
            sendHealthStatusEvent('offline')
            if (settings.txtEnable) { log.warn "${device.displayName} is not present (offline)!" }
        }
    } else {
        if (settings.logEnable) { log.debug "${device.displayName} deviceHealthCheck - online (notPresentCounter=${state.notPresentCounter})" }
    }
}

// Resets the notPresentCounter and sets healthStatus to 'online'.
void setPresent() {
    if ((device.currentValue('healthStatus') ?: 'unknown') != 'online') {
        sendHealthStatusEvent('online')
        if (settings.txtEnable) { log.info "${device.displayName} is present (online)." }
    }
    state.notPresentCounter = 0
}

// Helper to send the healthStatus event.
void sendHealthStatusEvent(String value) {
    sendEvent(name: 'healthStatus', value: value, descriptionText: "${device.displayName} healthStatus set to ${value}", type: 'digital')
}

// Disables debug logging after a set time.
void logsOff() {
    log.warn "Debug logging disabled for ${device.displayName}."
    device.updateSetting('logEnable', [value:'false', type:'bool'])
}

// Safely converts a value to Double, providing a default if conversion fails.
Double safeToDouble(val, Double defaultVal=0.0) {
    return "${val}"?.isDouble() ? "${val}".toDouble() : defaultVal
}