package com.martafode.wifip2plibrary.common.listeners

import android.net.wifi.p2p.WifiP2pInfo

interface PeerConnectedListener {
    fun onPeerConnected(wifiP2pInfo: WifiP2pInfo)
}
