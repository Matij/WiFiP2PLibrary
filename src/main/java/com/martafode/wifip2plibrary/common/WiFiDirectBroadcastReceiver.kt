package com.martafode.wifip2plibrary.common

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pManager.EXTRA_NETWORK_INFO
import android.net.wifi.p2p.WifiP2pManager.EXTRA_WIFI_P2P_DEVICE
import android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION
import android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION
import android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION
import android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION
import android.util.Log
import androidx.annotation.RequiresPermission

class WiFiDirectBroadcastReceiver(
    private val wiFiP2PInstance: WiFiP2PInstance
): BroadcastReceiver() {
    companion object {
        const val TAG = "WiFiDirectBroadcastReceiver"
    }

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            WIFI_P2P_STATE_CHANGED_ACTION -> {
                when (intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)) {
                    WifiP2pManager.WIFI_P2P_STATE_ENABLED -> Log.i(TAG, "WiFi P2P is active")
                    else -> Log.i(TAG, "WiFi P2P isn't active")
                }
            }
            WIFI_P2P_PEERS_CHANGED_ACTION -> {
                Log.d(TAG, "New peers detected. Requesting peers list...")

                wiFiP2PInstance.wifiP2pManager?.requestPeers(wiFiP2PInstance.channel) { peers ->
                    if (!peers.deviceList.isEmpty()) {
                        Log.d(TAG, "Peers detected:")

                        peers.deviceList?.forEach { device ->
                            Log.d(TAG, "\tDevice Name: " + device.deviceName)
                            Log.d(TAG, "\tDevice Address: " + device.deviceAddress)
                        }
                    } else {
                        Log.d(TAG, "No peers detected")
                    }
                }
            }
            WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                val networkInfo = intent.getParcelableExtra<NetworkInfo>(EXTRA_NETWORK_INFO)
                if (networkInfo!!.isConnected) {
                    Log.d(TAG, "New device is connected")
                    wiFiP2PInstance.wifiP2pManager?.requestConnectionInfo(wiFiP2PInstance.channel, wiFiP2PInstance)
                } else {
                    Log.d(TAG, "The server device has been disconnected")
                    wiFiP2PInstance.onServerDeviceDisconnected()
                }
            }
            WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                val device = intent.getParcelableExtra<WifiP2pDevice>(EXTRA_WIFI_P2P_DEVICE)
                Log.d(TAG, "This device name: " + device!!.deviceName)
                Log.d(TAG, "This device address: " + device.deviceAddress)

                if (wiFiP2PInstance.thisDevice == null) {
                    wiFiP2PInstance.thisDevice = WiFiGroupDevice(device)
                }
            }
        }
    }
}
