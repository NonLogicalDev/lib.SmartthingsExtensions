/**
 *  REST Api
 *
 *  Copyright 2018 Oleg Utkin
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  Loosely modelled after:
 *    https://github.com/codersaur/SmartThings/blob/master/smartapps/influxdb-logger/influxdb-logger.groovy
 */

definition(
    name: "LAN Logger",
    namespace: "nonlogical",
    author: "Oleg Utkin",
    description: "Logs data from sensors to a lan server.",
    category: "SmartThings Labs",
    iconUrl: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience.png",
    iconX3Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
    iconX4Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
)

preferences {
    page(name:"pageMain")
}

//------------------------------------------------------------
// Pages
//------------------------------------------------------------

def pageMain() {
    return dynamicPage(name:"pageMain", title:"", install:true, uninstall:true) {
        section("Devices:") {
            input "devices", "capability.sensor", title: "Devices:", multiple: true
        }
        section("LoggerHost:") {
            input "lanIP", "text", title: "Lan IP:"
            input "lanPORT", "text", title: "Lan PORT:"
            input "lanPATH", "text", title: "Lan PATH:"
        }
    }
}

//------------------------------------------------------------
// IO Handler
//------------------------------------------------------------

def lanRequest(method, hIP, hPort, hPath, data) {
    sendHubCommand(new physicalgraph.device.HubAction([
        method: method,

        path:   hPath,
        headers: [
            "HOST": "${hIP}:${hPort}",
        ],

        body: data,
    ], null, [ callback: lanResponse ]))
}

def lanResponse(physicalgraph.device.HubResponse hubResponse) {
    log.debug "RES:HEAD: ${hubResponse.headers}"
    log.debug "RES:BODY: ${hubResponse.body}"
}

def postLANMessage(data) {
    try {
        lanRequest("POST", state.lanIP, state.lanPORT, state.lanPATH, data)
    } catch (all) {
        log.debug "OOPS"
    }
}

//------------------------------------------------------------
// Lifecycle
//------------------------------------------------------------

def installed() {
    log.debug "INSTALLING..."
    saveSettings()

    postLANMessage([
        type: "state",
        data: "installed"
    ])
}

def updated() {
    log.debug "UPDATING..."
    saveSettings()

    postLANMessage([
        type: "state",
        data: "updated"
    ])
}

def uninstalled() {
    log.debug "UNINSTALLING..."
    state.subs = []

    postLANMessage([
        type: "state",
        data: "uninstalled"
    ])
}

def onPoll() {
    log.debug "POLLING..."
    state.subs.each { s ->
        onDevicePoll(getDeviceById(s.deviceId), s.capName, s.attrName)
    }
}

//------------------------------------------------------------
// HELPERS
//------------------------------------------------------------

def formatISODate(date) {
    return date.format( "yyyy-MM-dd'T'HH:mm:ss.SSSXXX" )
}

def getCapacityForAttr(devId, attrName) {
    return state.handlers["${devId}:${attrName}"]
}

def setCapacityForAttr(devId, attrName, capName) {
    state.handlers["${devId}:${attrName}"] = capName
}

def clearCapacityForAttrs() {
    state.handlers = [:]
}

def getDeviceById(p_id) {
    return settings.devices.find { it.id == p_id }
}

//------------------------------------------------------------

def saveSettings() {
    // Set LAN Connection Properties.
    log.debug "SAVE.SETTINGS: ${settings}"
    state.lanIP = settings.lanIP
    state.lanPORT = settings.lanPORT
    state.lanPATH = settings.lanPATH

    // Set subscriptions.
    log.debug "SAVE.SUBSCRIPTIONS::"
    state.subs = connectToDevices(settings.devices)
    postLANMessage([
        type: "subscribe",
        data: state.subs.collect {
            "${getDeviceById(it.deviceId).name} -> ${it.capName}:${it.attrName}"
        },
    ])

    // Set schedule.
    log.debug "SAVE.SCHEDULE::"
    try {
        unschedule()
    } catch (all) {}
    runEvery5Minutes("onPoll")
    log.debug "SAVE.DONE::"
}

def connectToDevices(devices) {
    unsubscribe()
    clearCapacityForAttrs()

    def subs = []

    devices.each { dev ->
        def sdev = deviceObjectToMap(dev, true)

        log.debug("DEVTYPE: ${sdev.type}")
        sdev.capabilities.each { cap ->
            cap.attributes.each { attr ->
                log.debug "SUBSCRIBING: ${dev}:{${cap.name}}:(${attr})"
                setCapacityForAttr(dev.id, attr, cap.name)
                subscribe(dev, attr, onDeviceEvent)

                subs.add([
                    deviceId: dev.id,
                    attrName: attr,
                    capName: cap.name
                ])
            }
        }
    }

    return subs
}

//------------------------------------------------------------
// Event Handling
//------------------------------------------------------------

def onDevicePoll(device, capName, attrName) {
    def evt = serializePoll(device, capName, attrName)

    log.debug "POLLING_EVENT: ${device.label}::${capName}:${attrName} :: ${evt}"
    postLANMessage([
        type: "event",
        data: evt,
    ])
}

def onDeviceEvent(e) {
    def attrName = e.name
    def capName = getCapacityForAttr(e.device.id, attrName)
    def evt = serializeEvent(e, capName)

    log.debug "DEVICE_EVENT: ${e.device.label}::${capName}:${attrName} :: ${evt}"
    postLANMessage([
        type: "event",
        data: evt,
    ])
}

//------------------------------------------------------------
// Serialization Methods
//------------------------------------------------------------

def serializeEvent(e, capName) {
    def attrName = e.name

    return [
        id:   "" + e.id,
        ts:   e.date.time,
        date: formatISODate(e.date),
        mechanism: "organic",

        device: deviceObjectToMap(e.device, false),

        data: decodeMetric(e, attrName, capName),
    ]
}

def serializePoll(device, capName, attrName) {
    def id = UUID.randomUUID()
    def date = new Date()
    def state = device.latestState(attrName)

    if (state == null) return null

    return [
        id:   "" + id,
        ts:   date.time,
        date: formatISODate(date),
        mechanism: "polling",

        device: deviceObjectToMap(device, false),

        data: decodeMetric(state, attrName, capName),
    ]
}

//------------------------------------------------------------
// Simplifying Methods
//------------------------------------------------------------

def hubObjectToMap(h) {
    return [
        id: "" + h.id,

        name: h.name,
        status: h.status,
        localIP: h.localIP,

        onBatteryPower: h.hub.getDataValue("batteryInUse"),
        uptime: h.hub.getDataValue("uptime"),

        fw: h.firmwareVersionString,
    ]
}

def deviceObjectToMap(d, full=true) {
    def output = [
        id:    "" + d.id,

        name:  d.name,
        label: d.label,
        display: d.displayName,

        make:  d.manufacturerName,
        model: d.modelName,
        type:  d.typeName,

        groupId: d?.device?.groupId,
    ]

    if (full) {
        output << [
            capabilities: d.capabilities.collect {[
                name: it.name.toLowerCase().replace(" ", "-"),

                attributes: it.attributes.collect {
                    it.name
                },

                commands: it.commands.collect {[
                    name: it.name,
                    args: it.arguments.collect {[
                        "" + it
                    ]}
                ]},
            ]},

            allAttributes: d.supportedAttributes.collect {
                it.name
            },

            allCommands: d.supportedCommands.collect {[
                name: it.name,
                args: it.arguments.collect {[
                    "" + it
                ]}
            ]},
        ]
    }

    return output
}

//------------------------------------------------------------
// Value Decoding
//------------------------------------------------------------

def decodeMetric(state, metricName, capName) {
    def output = [:]

    // Fully qualified metric name if available.
    if (capName != null) {
        output.metric = "${capName}.${metricName}"
    } else {
        output.metric = metricName
    }

    def rvalue = decodeState(state)
    def value = rvalue.value
    def type = rvalue.type

    output << [
        unit:   state.unit,
        strValue: state.value,

        type:  type,
        value: value,
    ]

    switch (metricName) {

    ///// LOGICAL:
    //--------------------------------------------------------------------------------/
    // acceleration (L)
    // contact (L)
    // motion (L)
    // presence (L)
    // status (L)
    // switch (L)
    // tamper (L)
    // thermostatMode (L)
    // thermostatOperatingState (S)
    //--------------------------------------------------------------------------------/
        case "acceleration":
            output << [
                decodedType: "logical",
                decodedValue: (value == "active") ? 1 : 0,
            ]
            break

        case "contact":
            output << [
                decodedType: "logical",
                decodedValue: (value == "open") ? 1 : 0,
            ]
            break

        case "motion":
            output << [
                decodedType: "logical",
                decodedValue: (value == "active") ? 1 : 0,
            ]
            break

        case "presence":
            output << [
                decodedType: "logical",
                decodedValue: (value == "present") ? 1 : 0,
            ]
            break

        case "switch":
            output << [
                decodedType: "logical",
                decodedValue: (value == "on") ? 1 : 0,
            ]
            break

        case "tamper":
            output << [
                decodedType: "logical",
                decodedValue: (value == "detected") ? 1 : 0,
            ]
            break

        case "thermostatMode":
            output << [
                decodedType: "logical",
                decodedValue: (value == "on") ? 1 : 0,
            ]
            break

        case "thermostatOperatingState":
            output << [
                decodedType: "logical",
                decodedValue: (value != "idle") ? 1 : 0,
            ]
            break

    // case "status":
    //     outputT = "logical"
    //     outputV = (v == "open") ? 1 : 0
    //     break


    ///// Numeric:
    //--------------------------------------------------------------------------------/
    // battery (N)
    // colorTemperature (N)
    // heatingSetpoint (N)
    // hue (N)
    // humidity (N)
    // illuminance (N)
    // level (N)
    // lxLight (N)
    // pLight (N)
    // power (N)
    // saturation (N)
    // temperature (N)
    // thermostatSetpoint (N)
    //--------------------------------------------------------------------------------/
        case "battery":
        case "colorTemperature":
        case "heatingSetpoint":
        case "hue":
        case "humidity":
        case "illuminance":
        case "level":
        case "lxLight":
        case "pLight":
        case "power":
        case "saturation":
        case "temperature":
        case "thermostatSetpoint":
            output << [
                decodedType: "numeric",
                decodedValue: state.doubleValue
            ]
            break

    ///// Vector:
    //--------------------------------------------------------------------------------/
    // threeAxis (A)
    //--------------------------------------------------------------------------------/
        case "threeAxis":
            output << [
                decodedType: "vector",
                decodedValue: state.threeAxis
            ]
            break

    ///// Raw Value Otherwise:
    // button (S)
    // thermostatOperatingState (S)
    // lastCheckin (DATE)
    //--------------------------------------------------------------------------------/
        default:
            break
    }

    return output
}

def decodeState(e) {
    try {
        return [
            type: "numeric",
            value: e.doubleValue,
        ]
    } catch (all) {}

    try {
        return [
            type: "vector",
            value: e.xyzValue,
        ]
    } catch (all) {}

    if (e.dateValue != null) {
        return [
            type: "date",
            value: e.dateValue,
        ]
    }

    return [
        type: "string",
        value: e.value,
    ]
}

