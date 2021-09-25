package com.martafode.wifip2plibrary.common

enum class WiFiP2PError(val reason: Int) {
    ERROR(0),
    P2P_NOT_SUPPORTED(1),
    BUSY(2);

    companion object {
        fun fromReason(reason: Int): WiFiP2PError? {
            return values().find { it.reason == reason }
        }
    }
}
