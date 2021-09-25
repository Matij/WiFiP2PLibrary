package com.martafode.wifip2plibrary.common.messages

import com.martafode.wifip2plibrary.common.WiFiGroupDevice

data class RegisteredDevicesMessageContent(
    val devicesRegistered: List<WiFiGroupDevice>
)
