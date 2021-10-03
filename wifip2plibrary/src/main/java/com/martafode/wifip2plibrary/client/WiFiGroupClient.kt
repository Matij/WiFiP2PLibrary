package com.martafode.wifip2plibrary.client

import android.Manifest
import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.AsyncTask
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresPermission
import com.google.gson.Gson
import com.martafode.wifip2plibrary.common.*
import com.martafode.wifip2plibrary.common.direct.WiFiDirectUtils
import com.martafode.wifip2plibrary.common.listeners.ClientConnectedListener
import com.martafode.wifip2plibrary.common.listeners.ClientDisconnectedListener
import com.martafode.wifip2plibrary.common.listeners.DataReceivedListener
import com.martafode.wifip2plibrary.common.listeners.PeerConnectedListener
import com.martafode.wifip2plibrary.common.listeners.ServiceConnectedListener
import com.martafode.wifip2plibrary.common.listeners.ServiceDisconnectedListener
import com.martafode.wifip2plibrary.common.listeners.ServiceDiscoveredListener
import com.martafode.wifip2plibrary.common.messages.DisconnectionMessageContent
import com.martafode.wifip2plibrary.common.messages.MessageWrapper
import com.martafode.wifip2plibrary.common.messages.RegisteredDevicesMessageContent
import com.martafode.wifip2plibrary.common.messages.RegistrationMessageContent
import com.martafode.wifip2plibrary.service.WiFiGroupService
import org.apache.commons.io.IOUtils
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.Charset
import java.util.ArrayList

class WiFiGroupClient private constructor(context: Context): PeerConnectedListener, ServiceDisconnectedListener {
    companion object {
        const val TAG = "WiFiGroupClient"

        private var instance: WiFiGroupClient? = null

        /**
         * Return the WiFiGroupClient instance. If the instance doesn't exist yet, it's created and returned.
         *
         * @param context The application context.
         * @return The actual WiFiGroupClient instance.
         */
        fun getInstance(context: Context): WiFiGroupClient {
            if (instance == null) {
                instance = WiFiGroupClient(context)
            }
            return instance!!
        }
    }

    private var serviceDevices: ArrayList<WiFiGroupServiceDevice> = ArrayList()

    private var dnsSdTxtRecordListener: WifiP2pManager.DnsSdTxtRecordListener? = null
    private var dnsSdServiceResponseListener: WifiP2pManager.DnsSdServiceResponseListener? = null
    private var serviceConnectedListener: ServiceConnectedListener? = null
    /**
     * Listener to know when data is received from the service device or other client devices
     * connected to the same group.
     */
    var dataReceivedListener: DataReceivedListener? = null
    /**
     * Listener to notify when the service device has been disconnected.
     */
    var serviceDisconnectedListener: ServiceDisconnectedListener? = null
    /**
     * Listener to know when a new client is registered in the actual group.
     */
    var clientConnectedListener: ClientConnectedListener? = null
    /**
     * Listener to know when a client has been disconnected from the group.
     */
    var clientDisconnectedListener: ClientDisconnectedListener? = null

    private var serverSocket: ServerSocket? = null

    private var wiFiP2PInstance: WiFiP2PInstance = WiFiP2PInstance.getInstance(context)

    private var serviceDevice: WiFiGroupServiceDevice? = null
    private var isRegistered: Boolean = false

    /**
     * Get/Set the devices connected to the actual group.
     *
     * @return the devices connected to the actual group.
     */
    var clientsConnected: HashMap<String, WiFiGroupDevice> = HashMap()

    init {
        wiFiP2PInstance.peerConnectedListener = this
        wiFiP2PInstance.serviceDisconnectedListener = this
    }

    /**
     * Start to discover WiFiGroup services registered in the current local network.
     * <p>
     * Before you start to discover services you must to register the <code>WiFiDirectBroadcastReceiver</code>
     * in the <code>onResume()</code> method of your activity.
     *
     * @param discoveringTimeInMillis   The time in milliseconds to search for registered WiFiGroup services.
     * @param serviceDiscoveredListener The listener to notify changes of the services found by the client.
     * @see com.martafode.wifip2plibrary.common.WiFiDirectBroadcastReceiver
     */
    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    fun discoverServices(discoveringTimeInMillis: Long, serviceDiscoveredListener: ServiceDiscoveredListener) {
        serviceDevices.clear()

        // We need to start discovering peers to activate the service search
        wiFiP2PInstance.startPeerDiscovering()

        setupDnsListeners(wiFiP2PInstance, serviceDiscoveredListener)
        WiFiDirectUtils.clearServiceRequest(wiFiP2PInstance)

        val serviceRequest = WifiP2pDnsSdServiceRequest.newInstance()
        wiFiP2PInstance.wifiP2pManager?.addServiceRequest(wiFiP2PInstance.channel, serviceRequest, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Success adding service request")
            }

            override fun onFailure(reason: Int) {
                val wiFiP2PError = WiFiP2PError.fromReason(reason)
                Log.e(TAG, "Error adding service request. Reason: $wiFiP2PError")
                serviceDiscoveredListener.onError(wiFiP2PError)
            }
        })

        wiFiP2PInstance.wifiP2pManager?.discoverServices(wiFiP2PInstance.channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Success initiating disconvering services")
            }

            override fun onFailure(reason: Int) {
                val wiFiP2PError = WiFiP2PError.fromReason(reason)
                Log.e(TAG, "Error discovering services. Reason: $wiFiP2PError")
                serviceDiscoveredListener.onError(wiFiP2PError)
            }
        })

        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed({
            serviceDiscoveredListener.onFinishServiceDeviceDiscovered(serviceDevices)
        }, discoveringTimeInMillis)
    }

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    fun connectToService(serviceDevice: WiFiGroupServiceDevice, serviceConnectedListener: ServiceConnectedListener) {
        this.serviceDevice = serviceDevice
        this.serviceConnectedListener = serviceConnectedListener

        val wifiP2pConfig = WifiP2pConfig()
        wifiP2pConfig.deviceAddress = serviceDevice.deviceMac

        wiFiP2PInstance.wifiP2pManager?.connect(wiFiP2PInstance.channel, wifiP2pConfig, object: WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "Initiated connection to device: ")
                Log.i(TAG, "\tDevice name: " + serviceDevice.deviceName)
                Log.i(TAG, "\tDevice address: " + serviceDevice.deviceMac)
            }

            override fun onFailure(reason: Int) {
                Log.e(TAG, "Fail initiation connection. Reason: " + WiFiP2PError.fromReason(reason))
            }
        })
    }

    override fun onPeerConnected(wifiP2pInfo: WifiP2pInfo) {
        Log.i(TAG, "OnPeerConnected...")

        if (wifiP2pInfo.groupFormed && wifiP2pInfo.isGroupOwner) {
            Log.e(TAG, "I shouldn't be the group owner, I'am a client!")
        }

        if (wifiP2pInfo.groupFormed && serviceDevice != null && !isRegistered) {
            serviceDevice!!.deviceServerSocketIP = (wifiP2pInfo.groupOwnerAddress.hostAddress)
            Log.i(TAG, "The Server Address is: " + wifiP2pInfo.groupOwnerAddress.hostAddress)

            // We are connected to the server. Create a server socket to receive messages
            createServerSocket()

            // FIXME - Change this into a server socket creation listener or similar
            // Wait 2 seconds for the server socket creation
            val handler = Handler(Looper.getMainLooper())
            handler.postDelayed({ // We send the negotiation message to the server
                sendServerRegistrationMessage()
                if (serviceConnectedListener != null) {
                    serviceConnectedListener!!.onServiceConnected(serviceDevice)
                }
                isRegistered = true
            }, 2000)
        }
    }

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    override fun onServerDisconnectedListener() {
        // If the server is disconnected the client is cleared
        disconnect()

        serviceDisconnectedListener?.onServerDisconnectedListener()
    }

    /**
     * Send a message to the service device.
     *
     * @param message The message to be sent.
     */
    fun sendMessageToServer(message: MessageWrapper) {
        sendMessage(serviceDevice, message)
    }

    /**
     * Send a message to all the devices connected to the group, including the service device.
     *
     * @param message The message to be sent.
     */
    fun sendMessageToAllClients(message: MessageWrapper) {
        sendMessageToServer(message)

        clientsConnected.values.forEach { device ->
            if (!device.deviceMac.equals(wiFiP2PInstance.thisDevice?.deviceMac)) {
                sendMessage(device, message)
            }
        }
    }

    /**
     * Send a message to the desired device who it's connected in the group.
     *
     * @param device  The receiver of the message.
     * @param message The message to be sent.
     */
    fun sendMessage(device: WiFiGroupDevice?, message: MessageWrapper) {
        // Set the actual device to the message

        // Set the actual device to the message
        message.wifiGroupDevice = wiFiP2PInstance.thisDevice

        class SendMessageAsyncTask : AsyncTask<MessageWrapper?, Void?, Void?>() {
            override fun doInBackground(vararg params: MessageWrapper?): Void? {
                if (device?.deviceServerSocketIP != null) {
                    try {
                        val socket = Socket()
                        socket.bind(null)
                        val hostAddress = InetSocketAddress(
                            device.deviceServerSocketIP,
                            device.deviceServerSocketPort
                        )
                        socket.connect(hostAddress, 2000)
                        val gson = Gson()
                        val messageJson = gson.toJson(params[0])
                        val outputStream = socket.getOutputStream()
                        outputStream.write(
                            messageJson.toByteArray(),
                            0,
                            messageJson.toByteArray().size
                        )
                        Log.d(TAG, "Sending data: " + params[0])
                        socket.close()
                        outputStream.close()
                    } catch (e: IOException) {
                        Log.e(TAG, "Error creating client socket: " + e.message)
                    }
                }
                return null
            }
        }
        SendMessageAsyncTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, message)
    }

    /**
     * Disconnect from the actual group connected. Before the disconnection, the client sends a
     * message to the service device to notify the disconnection.
     */
    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    fun disconnect() {
        serverSocket?.let { nonNullServerSocket ->
            try {
                nonNullServerSocket.close()
                Log.i(TAG, "ServerSocket closed")
            } catch (e: IOException) {
                Log.e(TAG, "Error closing the serverSocket")
            }
        }

        sendDisconnectionMessage()

        // FIXME - Change this into a message sent it listener
        // Wait 2 seconds to disconnection message was sent
        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed({
            WiFiDirectUtils.clearServiceRequest(wiFiP2PInstance)
            WiFiDirectUtils.stopPeerDiscovering(wiFiP2PInstance)
            WiFiDirectUtils.removeGroup(wiFiP2PInstance)
            serverSocket = null
            isRegistered = false
            clientsConnected.clear()
        }, 2000)
    }

    fun setupDnsListeners(wiFiP2PInstance: WiFiP2PInstance, serviceDiscoveredListener: ServiceDiscoveredListener) {
        if (dnsSdTxtRecordListener == null || dnsSdServiceResponseListener == null) {
            dnsSdTxtRecordListener = getTxtRecordListener(serviceDiscoveredListener)
            dnsSdServiceResponseListener = WifiP2pManager.DnsSdServiceResponseListener { _, _, _ -> }

            wiFiP2PInstance.wifiP2pManager?.setDnsSdResponseListeners(
                wiFiP2PInstance.channel,
                dnsSdServiceResponseListener,
                dnsSdTxtRecordListener
            )

        }
    }

    private fun getTxtRecordListener(serviceDiscoveredListener: ServiceDiscoveredListener): WifiP2pManager.DnsSdTxtRecordListener {
        return WifiP2pManager.DnsSdTxtRecordListener { fullDomainName, txtRecordMap, device ->
            if (txtRecordMap.containsKey(WiFiGroupService.SERVICE_NAME_PROPERTY) &&
                txtRecordMap[WiFiGroupService.SERVICE_NAME_PROPERTY]
                    .equals(WiFiGroupService.SERVICE_NAME_VALUE, ignoreCase = true)) {

                val servicePort = Integer.valueOf(txtRecordMap[WiFiGroupService.SERVICE_PORT_PROPERTY]!!)
                val serviceDevice = WiFiGroupServiceDevice(device, txtRecordMap)
                serviceDevice.deviceServerSocketPort = servicePort

                if (serviceDevices.contains(serviceDevice).not()) {
                    Log.i(TAG, "Found a new WiFiGroup service: ")
                    Log.i(TAG, "\tDomain Name: $fullDomainName")
                    Log.i(TAG, "\tDevice Name: " + device.deviceName)
                    Log.i(TAG, "\tDevice Address: " + device.deviceAddress)
                    Log.i(TAG, "\tServer socket Port: " + serviceDevice.deviceServerSocketPort)

                    serviceDevices.add(serviceDevice)
                    serviceDiscoveredListener.onNewServiceDeviceDiscovered(serviceDevice)
                } else {
                    Log.d(TAG, "Found a new service: ")
                    Log.d(TAG, "\tDomain Name: $fullDomainName")
                    Log.d(TAG, "\tDevice Name: " + device.deviceName)
                    Log.d(TAG, "\tDevice Address: " + device.deviceAddress)
                }
            }
        }
    }

    private fun createServerSocket() {
        if (serverSocket == null) {
            class CreateServerSocketAsyncTask : AsyncTask<Void?, Void?, Void?>() {
                override fun doInBackground(vararg params: Void?): Void? {
                    try {
                        serverSocket = ServerSocket(0)
                        val port = serverSocket!!.localPort
                        wiFiP2PInstance.thisDevice?.deviceServerSocketPort = port
                        Log.i(
                            TAG,
                            "Client ServerSocket created. Accepting requests..."
                        )
                        Log.i(TAG, "\tPort: $port")
                        while (true) {
                            val socket = serverSocket!!.accept()
                            val dataReceived = try {
                                IOUtils.toString(socket.getInputStream(), Charset.defaultCharset())
                            } catch (e: NoSuchMethodError) {
                                Log.e(TAG, "An error occurred on ${javaClass.canonicalName}#createServerSocket doInBackground: $e")
                                null
                            }
                            Log.i(
                                TAG,
                                "Data received: $dataReceived"
                            )
                            Log.i(
                                TAG,
                                "From IP: " + socket.inetAddress.hostAddress
                            )
                            val gson = Gson()
                            val messageWrapper =
                                gson.fromJson(dataReceived, MessageWrapper::class.java)
                            onMessageReceived(messageWrapper)
                        }
                    } catch (e: IOException) {
                        Log.e(
                            TAG,
                            "Error creating/closing client ServerSocket: " + e.message
                        )
                    }
                    return null
                }
            }

            CreateServerSocketAsyncTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR)
        }
    }

    private fun onMessageReceived(messageWrapper: MessageWrapper) {
        when (messageWrapper.messageType) {
            MessageWrapper.MessageType.CONNECTION_MESSAGE -> {
                val gson = Gson()

                val messageContentStr: String = messageWrapper.message
                val registrationMessageContent: RegistrationMessageContent = gson.fromJson(
                    messageContentStr,
                    RegistrationMessageContent::class.java
                )
                val device = registrationMessageContent.wifiGroupDevice
                if (clientsConnected.contains(device.deviceMac)) {
                    clientsConnected[device.deviceMac!!] = device

                    clientConnectedListener?.onClientConnected(device)

                    Log.d(TAG, "New client connected to the group:")
                    Log.d(TAG, "\tDevice name: " + device.deviceName)
                    Log.d(TAG, "\tDecive mac: " + device.deviceMac)
                    Log.d(TAG, "\tDevice IP: " + device.deviceServerSocketIP)
                    Log.d(TAG, "\tDevice ServerSocket port: " + device.deviceServerSocketPort)
                }
            }
            MessageWrapper.MessageType.DISCONNECTION_MESSAGE -> {
                val gson = Gson()

                val messageContentStr: String = messageWrapper.message
                val disconnectionMessageContent: DisconnectionMessageContent = gson.fromJson(
                    messageContentStr,
                    DisconnectionMessageContent::class.java
                )
                val device: WiFiGroupDevice = disconnectionMessageContent.wifiGroupDevice
                clientsConnected.remove(device.deviceMac)

                clientDisconnectedListener?.onClientDisconnected(device)

                Log.d(TAG, "Client disconnected from the group:")
                Log.d(TAG, "\tDevice name: " + device.deviceName)
                Log.d(TAG, "\tDecive mac: " + device.deviceMac)
                Log.d(TAG, "\tDevice IP: " + device.deviceServerSocketIP)
                Log.d(TAG, "\tDevice ServerSocket port: " + device.deviceServerSocketPort)
            }
            MessageWrapper.MessageType.REGISTERED_DEVICES -> {
                val gson = Gson()

                val messageContentStr: String = messageWrapper.message
                val registeredDevicesMessageContent: RegisteredDevicesMessageContent =
                    gson.fromJson(
                        messageContentStr,
                        RegisteredDevicesMessageContent::class.java
                    )
                val devicesConnected: List<WiFiGroupDevice> =
                    registeredDevicesMessageContent.devicesRegistered

                devicesConnected.forEach { device ->
                    if (clientsConnected.containsKey(device.deviceMac)) {
                        clientsConnected[device.deviceMac!!] = device
                        Log.d(TAG, "Client already connected to the group:")
                        Log.d(TAG, "\tDevice name: " + device.deviceName)
                        Log.d(TAG, "\tDecive mac: " + device.deviceMac)
                        Log.d(TAG, "\tDevice IP: " + device.deviceServerSocketIP)
                        Log.d(TAG, "\tDevice ServerSocket port: " + device.deviceServerSocketPort)
                    }
                }
            }
            else -> dataReceivedListener?.onDataReceived(messageWrapper)
        }
    }

    private fun sendServerRegistrationMessage() {
        val thisDevice = wiFiP2PInstance.thisDevice
        if (thisDevice != null) {
            val content = RegistrationMessageContent(thisDevice)

            val gson = Gson()

            val negotiationMessage = MessageWrapper(
                message = gson.toJson(content),
                messageType = MessageWrapper.MessageType.CONNECTION_MESSAGE
            )

            sendMessageToServer(negotiationMessage)
        }
    }

    private fun sendDisconnectionMessage() {
        val thisDevice = wiFiP2PInstance.thisDevice
        if (thisDevice != null) {
            val content = DisconnectionMessageContent(thisDevice)

            val gson = Gson()

            val disconnectionMessage = MessageWrapper(
                message = gson.toJson(content),
                messageType = MessageWrapper.MessageType.DISCONNECTION_MESSAGE
            )

            sendMessageToServer(disconnectionMessage)
        }
    }

    fun clear() {
        wiFiP2PInstance.clear()
        instance = null
    }
}
