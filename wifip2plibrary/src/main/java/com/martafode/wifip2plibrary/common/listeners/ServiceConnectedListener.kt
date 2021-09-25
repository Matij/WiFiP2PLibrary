package com.martafode.wifip2plibrary.common.listeners

import com.martafode.wifip2plibrary.common.WiFiGroupDevice

interface ServiceConnectedListener {
    fun onServiceConnected(serviceDevice: WiFiGroupDevice?)
}
