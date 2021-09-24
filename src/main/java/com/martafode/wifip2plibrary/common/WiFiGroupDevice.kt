package com.martafode.wifip2plibrary.common

import android.net.wifi.p2p.WifiP2pDevice

open class WiFiGroupDevice(device: WifiP2pDevice) {
    var deviceName: String? = device.deviceName
    var deviceMac: String? = device.deviceAddress

    var deviceServerSocketIP: String? = null
    var deviceServerSocketPort: Int = 0

    var customName: String? = null

    override fun toString(): String {
        return StringBuilder().append("WiFiGroupDevice[deviceName=")
            .append(deviceName)
            .append("][deviceMac=")
            .append(deviceMac)
            .append("][deviceServerSocketIP=")
            .append(deviceServerSocketIP)
            .append("][deviceServerSocketPort=")
            .append(deviceServerSocketPort)
            .append("]").toString()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other::class.java) return false

        val that = other as WiFiGroupDevice

        if (if (deviceName != null) deviceName != that.deviceName else that.deviceName != null) return false
        return if (deviceMac != null) deviceMac == that.deviceMac else that.deviceMac == null
    }

    override fun hashCode(): Int {
        return when (deviceName != null) {
            true -> deviceName.hashCode()
            else -> 0
        }.apply {
            31 * this + if (deviceMac != null) deviceMac.hashCode() else 0
        }
    }
}
