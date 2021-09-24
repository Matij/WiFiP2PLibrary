package com.martafode.wifip2plibrary.common.messages

import com.martafode.wifip2plibrary.common.WiFiGroupDevice

data class MessageWrapper(
    val message: String,
    val messageType: MessageType
) {
    enum class MessageType {
        NORMAL, CONNECTION_MESSAGE, DISCONNECTION_MESSAGE, REGISTERED_DEVICES
    }

    var wifiGroupDevice: WiFiGroupDevice ? = null

    override fun toString(): String {
        return "MessageWrapper{" +
                "message=$message, " +
                "messageType=$messageType, " +
                "wifiGroupDevice=$wifiGroupDevice" +
                '}'
    }
}
