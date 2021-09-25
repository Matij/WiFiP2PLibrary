package com.martafode.wifip2plibrary.common.listeners

import com.martafode.wifip2plibrary.common.WiFiP2PError
import com.martafode.wifip2plibrary.common.WiFiGroupServiceDevice

interface ServiceDiscoveredListener {
    fun onNewServiceDeviceDiscovered(serviceDevice: WiFiGroupServiceDevice?)
    fun onFinishServiceDeviceDiscovered(serviceDevices: List<WiFiGroupServiceDevice?>?)
    fun onError(wiFiP2PError: WiFiP2PError?)
}
