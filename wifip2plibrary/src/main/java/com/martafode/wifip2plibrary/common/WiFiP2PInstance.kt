package com.martafode.wifip2plibrary.common

import android.Manifest
import android.content.Context
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pManager.ConnectionInfoListener
import android.util.Log
import androidx.annotation.RequiresPermission
import com.martafode.wifip2plibrary.common.listeners.PeerConnectedListener
import com.martafode.wifip2plibrary.common.listeners.ServiceDisconnectedListener

class WiFiP2PInstance private constructor(context: Context) : ConnectionInfoListener {

    companion object {
        const val TAG: String = "WiFiP2PInstance"

        private var instance: WiFiP2PInstance ? = null

        fun getInstance(context: Context): WiFiP2PInstance {
            if (instance == null) {
                instance = WiFiP2PInstance(context = context)
            }
            return instance!!
        }
    }

    var wifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager?
    var channel = wifiP2pManager?.initialize(context, context.mainLooper, null)
    var broadcastReceiver = WiFiDirectBroadcastReceiver(this)

    var thisDevice: WiFiGroupDevice? = null

    var peerConnectedListener: PeerConnectedListener? = null
    var serviceDisconnectedListener: ServiceDisconnectedListener? = null

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    fun startPeerDiscovering() {
        wifiP2pManager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "Peers discovering initialized")
            }

            override fun onFailure(reason: Int) {
                Log.e(TAG, "Error initiating peer disconvering. Reason: $reason")
            }
        })
    }

    override fun onConnectionInfoAvailable(info: WifiP2pInfo) {
        peerConnectedListener?.onPeerConnected(wifiP2pInfo = info)
    }

    fun onServerDeviceDisconnected() {
        serviceDisconnectedListener?.onServerDisconnectedListener()
    }
}
