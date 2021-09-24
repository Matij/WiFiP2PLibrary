package com.martafode.wifip2plibrary.common.listeners

import com.martafode.wifip2plibrary.common.WiFiP2PError

interface ServiceRegisteredListener {
    fun onSuccessServiceRegistered()
    fun onErrorServiceRegistered(wiFiP2PError: WiFiP2PError?)
}
