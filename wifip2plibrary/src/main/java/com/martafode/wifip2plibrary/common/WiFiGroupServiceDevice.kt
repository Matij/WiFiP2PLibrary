package com.martafode.wifip2plibrary.common

import android.net.wifi.p2p.WifiP2pDevice

data class WiFiGroupServiceDevice(
    val wifiP2pDevice: WifiP2pDevice,
    var txtRecordMap: Map<String, String>,
): WiFiGroupDevice(wifiP2pDevice)
