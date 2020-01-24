/*
 *  Original Copyright 2020 SmartThings
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not
 *  use this file except in compliance with the License. You may obtain a copy
 *  of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

/* 
Based on original SmartThings driver (January 2020). Updated to present button
as single 4-button device with "pushed" event for buttons 1-4 and "held" events
for buttons 2 and 3, per device abilities. A "released" event is also generated
for 2 and 3 because the device reports something here, though this is not a "real"
SmartThings event and is probably not usable on this platform. For Hubitat users,
it does seem to get passed fine via HubConnect.
*/

import groovy.json.JsonOutput
import physicalgraph.zigbee.zcl.DataType

metadata {
	definition (name: "EcoSmart 4-Button Remote (Single Device)", namespace: "RMoRobert", author: "Robert Morris", ocfDeviceType: "x.com.st.d.remotecontroller", mcdSync: true, runLocally: false, executeCommandsLocally: false, mnmn: "SmartThings", vid: "generic-4-button") {
		capability "Actuator"
		capability "Battery"
		capability "Button"
		capability "Holdable Button"
		capability "Configuration"
		capability "Sensor"
		capability "Health Check"

		fingerprint inClusters: "0000, 0001, 0003, 1000, FD01", outClusters: "0003, 0004, 0006, 0008, 0019, 0300, 1000", manufacturer: "LDS", model: "ZBT-CCTSwitch-D0001", deviceJoinName: "EcoSmart 4-button remote"
	}

	tiles {
		standardTile("button", "device.button", width: 2, height: 2) {
			state "default", label: "", icon: "st.unknown.zwave.remote-controller", backgroundColor: "#ffffff"
			state "button 1 pushed", label: "pushed #1", icon: "st.unknown.zwave.remote-controller", backgroundColor: "#00A0DC"
		}

		valueTile("battery", "device.battery", decoration: "flat", inactiveLabel: false) {
			state "battery", label:'${currentValue}% battery', unit:""
		}

		main (["button"])
		details(["button", "battery"])
	}
}

private getCLUSTER_GROUPS() { 0x0004 }

private channelNumber(String dni) {
	dni.split(":")[-1] as Integer
}

private getButtonName(buttonNum) {
	return "${device.displayName} " + "Button ${buttonNum}"
}

def installed() {
	def numberOfButtons = 4
	state.ignoreNextButton3 = false

	sendEvent(name: "supportedButtonValues", value: ["pushed", "held"].encodeAsJSON(), displayed: false)
	sendEvent(name: "numberOfButtons", value: numberOfButtons, displayed: false)
	sendEvent(name: "button", value: "pushed", data: [buttonNumber: 1], displayed: false) // just to populate attribute?

	// These devices don't report regularly so they should only go OFFLINE when Hub is OFFLINE
	sendEvent(name: "DeviceWatch-Enroll", value: JsonOutput.toJson([protocol: "zigbee", scheme:"untracked"]), displayed: false)
}

def updated() {

}

def configure() {
	log.debug "Configuring device ${device.getDataValue("model")}"

	def cmds = zigbee.configureReporting(zigbee.POWER_CONFIGURATION_CLUSTER, 0x21, DataType.UINT8, 30, 21600, 0x01) +
			zigbee.readAttribute(zigbee.POWER_CONFIGURATION_CLUSTER, 0x21) +
			zigbee.addBinding(zigbee.ONOFF_CLUSTER) +
			// This device doesn't report a binding to this group but will send all messages to this group ID
			addHubToGroup(0x4003)

	cmds
}

def parse(String description) {
	log.debug "Parsing message from device: '$description'"
	def event = zigbee.getEvent(description)
	if (event) {
		log.debug "Creating event: ${event}"
		sendEvent(event)
	} else {
		if ((description?.startsWith("catchall:")) || (description?.startsWith("read attr -"))) {
			def descMap = zigbee.parseDescriptionAsMap(description)
			if (descMap.clusterInt == zigbee.POWER_CONFIGURATION_CLUSTER && descMap.attrInt == 0x0021) {
				event = getBatteryEvent(zigbee.convertHexToInt(descMap.value))
			} else if (descMap.clusterInt == zigbee.ONOFF_CLUSTER ||
					descMap.clusterInt == zigbee.LEVEL_CONTROL_CLUSTER ||
					descMap.clusterInt == zigbee.COLOR_CONTROL_CLUSTER) {
				event = getButtonEvent(descMap)
			}
		}

		def result = []
		if (event) {
			log.debug "Creating event: ${event}"
			result = createEvent(event)
		}

		return result
	}
}

private Map getBatteryEvent(value) {
	def result = [:]
	result.value = value / 2
	result.name = 'battery'
	result.descriptionText = "${device.displayName} battery was ${result.value}%"
	return result
}

private Map getButtonEvent(Map descMap) {
	log.debug "getButtonEvent..."
	def buttonState = "pushed"
	def buttonNumber = 0
	Map result = [:]
	// Button 1
	if (descMap.clusterInt == zigbee.ONOFF_CLUSTER) {
		buttonNumber = 1

	// Button 2
	} else if (descMap.clusterInt == zigbee.LEVEL_CONTROL_CLUSTER &&
		(descMap.commandInt == 0x00 || descMap.commandInt == 0x01 || descMap.commandInt == 0x03)) {
		buttonNumber = 2
        if (descMap.commandInt == 0x01) buttonState = "held"
        else if (descMap.commandInt == 0x03) buttonState = "released"

	// Button 3
	} else if (descMap.clusterInt == zigbee.COLOR_CONTROL_CLUSTER) {
		if (descMap.commandInt == 0x0A || descMap.commandInt == 0x4B) {
			if (state.ignoreNextButton3) {
				// button 4 sends 2 cmds; one is a button 3 cmd. We want to ignore these specific cmds
				state.ignoreNextButton3 = false
			} else {
				buttonNumber = 3
                if (descMap.commandInt == 0x4B) {
                	if (descMap.data[0] != "00") buttonState = "held"
                	else if (descMap.commandInt == 0x4B) buttonState = "released"
                }
			}
		}

	// Button 4
	} else if (descMap.clusterInt == zigbee.LEVEL_CONTROL_CLUSTER &&
		descMap.commandInt == 0x04) {
		// remember to ignore the next button 3 message we get
		state.ignoreNextButton3 = true
		buttonNumber = 4
	}

	if (buttonNumber != 0) {
		// Create and send event
       def descriptionText = "$device.displayName button $buttonNumber was $buttonState"
       //log.info descriptionText
       result = [
            name           : 'button',
            value          : buttonState,
            descriptionText: descriptionText,
            isStateChange  : true,
            data           : [buttonNumber: buttonNumber]
    ]
    }
	return result
}

private List addHubToGroup(Integer groupAddr) {
	["st cmd 0x0000 0x01 ${CLUSTER_GROUPS} 0x00 {${zigbee.swapEndianHex(zigbee.convertToHexString(groupAddr,4))} 00}",
	 "delay 200"]
}
