package com.martafode.wifip2plibrary.service

import android.Manifest
import android.content.Context
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.coroutineScope
import com.google.gson.Gson
import com.martafode.wifip2plibrary.common.WiFiP2PError
import com.martafode.wifip2plibrary.common.WiFiP2PInstance
import com.martafode.wifip2plibrary.common.WiFiGroupDevice
import com.martafode.wifip2plibrary.common.direct.WiFiDirectUtils
import com.martafode.wifip2plibrary.common.executeAsyncTask
import com.martafode.wifip2plibrary.common.listeners.ClientConnectedListener
import com.martafode.wifip2plibrary.common.listeners.ClientDisconnectedListener
import com.martafode.wifip2plibrary.common.listeners.DataReceivedListener
import com.martafode.wifip2plibrary.common.listeners.PeerConnectedListener
import com.martafode.wifip2plibrary.common.listeners.ServiceRegisteredListener
import com.martafode.wifip2plibrary.common.messages.DisconnectionMessageContent
import com.martafode.wifip2plibrary.common.messages.MessageWrapper
import com.martafode.wifip2plibrary.common.messages.RegisteredDevicesMessageContent
import com.martafode.wifip2plibrary.common.messages.RegistrationMessageContent
import kotlinx.coroutines.CoroutineScope
import org.apache.commons.io.IOUtils
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.Charset

class WiFiGroupService private constructor(context: Context) : PeerConnectedListener {
    companion object {
        const val TAG = "WiFiGroupService"

        private const val SERVICE_TYPE = "_wifiGroup._tcp"
        const val SERVICE_PORT_PROPERTY = "SERVICE_PORT"
        private const val SERVICE_PORT_VALUE = 9999
        const val SERVICE_NAME_PROPERTY = "SERVICE_NAME"
        const val SERVICE_NAME_VALUE = "WiFiGROUP"
        private const val SERVICE_GROUP_NAME = "GROUP_NAME"

        private var instance: WiFiGroupService? = null

        /**
         * Listener to know when data is received from the client devices connected to the group.
         *
         */
        var dataReceivedListener: DataReceivedListener? = null
        /**
         * Listener to know when a new client is registered in the group.
         *
         */
        var clientConnectedListener: ClientConnectedListener? = null
        /**
         * Listener to know when a client has been disconnected from the group.
         *
         */
        var clientDisconnectedListener: ClientDisconnectedListener? = null

        var clientsConnected: HashMap<String, WiFiGroupDevice> = HashMap()

        private var serverSocket: ServerSocket? = null
        private var groupAlreadyCreated: Boolean = false

        /**
         * Return the <code>WiFiGroupService</code> instance. If the instance doesn't exist yet, it's
         * created and returned.
         *
         * @param context The application context.
         * @return The actual <code>WiFiGroupService</code> instance.
         */
        fun getInstance(context: Context): WiFiGroupService {
            if (instance == null) {
                instance = WiFiGroupService(context)
            }
            return instance!!
        }
    }

    private var wiFiP2PInstance: WiFiP2PInstance = WiFiP2PInstance.getInstance(context = context).apply {
        peerConnectedListener = this@WiFiGroupService
    }

    private val scope: CoroutineScope = ProcessLifecycleOwner.get().lifecycle.coroutineScope

    /**
     * Start a WiFiGroup service registration in the actual local network with the name indicated in
     * the arguments. When te service is registered the method
     * {@link ServiceRegisteredListener#onSuccessServiceRegistered()} is called.
     *
     * @param groupName                 The name of the group that want to be created.
     * @param serviceRegisteredListener The <code>ServiceRegisteredListener</code> to notify
     *                                  registration changes.
     */
    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    fun registerService(groupName: String, serviceRegisteredListener: ServiceRegisteredListener) {
        registerService(groupName = groupName, customProperties = null, serviceRegisteredListener = serviceRegisteredListener)
    }

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    fun registerService(groupName: String, customProperties: Map<String, String>? = null, serviceRegisteredListener: ServiceRegisteredListener) {
        // We need to start peer discovering because otherwise the clients cannot found the service
        wiFiP2PInstance.startPeerDiscovering()

        val record = hashMapOf<String, String>().apply {
            put(SERVICE_PORT_PROPERTY, SERVICE_PORT_VALUE.toString())
            put(SERVICE_NAME_PROPERTY, SERVICE_NAME_VALUE)
            put(SERVICE_GROUP_NAME, groupName)


            // Insert the custom properties to the record Map
            customProperties?.let { properties ->
                properties.entries.forEach { entry ->
                    put(entry.key, entry.value)
                }
            }
        }

        val serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(groupName, SERVICE_TYPE, record)

        wiFiP2PInstance.wifiP2pManager?.clearLocalServices(wiFiP2PInstance.channel, object: WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "Success clearing local services")
            }

            override fun onFailure(reason: Int) {
                Log.e(TAG, "Error clearing local services: $reason")
            }
        })

        wiFiP2PInstance.wifiP2pManager?.addLocalService(wiFiP2PInstance.channel, serviceInfo, object: WifiP2pManager.ActionListener {
            @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            override fun onSuccess() {
                Log.i(TAG, "Service registered")
                serviceRegisteredListener.onSuccessServiceRegistered()

                // Create the group to the clients can connect to it

                // Create the group to the clients can connect to it
                removeAndCreateGroup()

                // Create the socket that will accept request
                createServerSocket()
            }

            override fun onFailure(reason: Int) {
                val wiFiP2PError = WiFiP2PError.fromReason(reason)
                Log.e(TAG, "Failure registering the service. Reason: ${wiFiP2PError?.name}")
                serviceRegisteredListener.onErrorServiceRegistered(wiFiP2PError)
            }
        })
    }

    /**
     * Remove the group created. Before the disconnection, the server sends a message to all
     * clients connected to notify the disconnection.
     */
    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    fun disconnect() {
        serverSocket?.let { socket ->
            try {
                socket.close()
            } catch (e: IOException) {
                Log.e(TAG, "Error closing the serverSocket")
            }
        }

        groupAlreadyCreated = false
        serverSocket = null
        clientsConnected.clear()

        WiFiDirectUtils.removeGroup(wiFiP2PInstance)
        WiFiDirectUtils.clearLocalServices(wiFiP2PInstance)
        WiFiDirectUtils.stopPeerDiscovering(wiFiP2PInstance)
    }

    override fun onPeerConnected(wifiP2pInfo: WifiP2pInfo) {
        Log.i(TAG, "OnPeerConnected...")

        if (wifiP2pInfo.groupFormed && wifiP2pInfo.isGroupOwner) {
            Log.i(TAG, "I am the group owner")
            Log.i(TAG, "My addess is: " + wifiP2pInfo.groupOwnerAddress.hostAddress)
        }
    }

    /**
     * Send a message to all the devices connected to the group.
     *
     * @param message The message to be sent.
     */
    fun sendMessageToAllClients(message: MessageWrapper) {
        clientsConnected.values.forEach { clientDevice ->
            sendMessage(clientDevice, message)
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
        message.wifiGroupDevice = wiFiP2PInstance.thisDevice

        scope.executeAsyncTask(
            params = arrayOf(message),
            onPreExecute = {},
            doInBackground = { params ->
                if (device?.deviceServerSocketIP != null) {
                    try {
                        val socket = Socket()
                        socket.bind(null)

                        val hostAddress = InetSocketAddress(
                            device.deviceServerSocketIP,
                            device.deviceServerSocketPort
                        )
                        socket.connect(hostAddress, 2000)

                        params?.let { messages ->
                            val gson = Gson()
                            val messageJson = gson.toJson(messages[0])

                            val outputStream = socket.getOutputStream()
                            outputStream.write(
                                messageJson.toByteArray(),
                                0,
                                messageJson.toByteArray().size
                            )

                            Log.d(TAG, "Sending data: " + messages[0])

                            socket.close()
                            outputStream.close()
                        }
                    } catch (e: IOException) {
                        Log.e(TAG, "Error creating client socket: " + e.message)
                    }
                }
            },
            onPostExecute = {}
        )
    }

    private fun createServerSocket() {
        serverSocket?.let { notNullServerSocket ->
            scope.executeAsyncTask(
                params = arrayOf<Unit>(),
                doInBackground = {
                    try {
                        serverSocket = ServerSocket(SERVICE_PORT_VALUE)
                        Log.i(TAG, "Server socket created. Accepting requests...")

                        while (true) {
                            val socket = notNullServerSocket.accept()
                            val dataReceived = IOUtils.toString(socket.getInputStream(), Charset.defaultCharset())
                            Log.i(TAG, "Data received: $dataReceived")
                            Log.i(TAG, "From IP: " + socket.inetAddress.hostAddress)

                            val gson = Gson()
                            val messageWrapper = gson.fromJson(dataReceived, MessageWrapper::class.java)
                            onMessageReceived(messageWrapper, socket.inetAddress)
                        }
                    } catch (e: IOException) {
                        Log.e(TAG, "Error creating/closing server socket: " + e.message)
                    }
                }
            )
        }
    }

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    private fun removeAndCreateGroup() {
        wiFiP2PInstance.wifiP2pManager?.requestGroupInfo(wiFiP2PInstance.channel) { group ->
            if (group != null) {
                wiFiP2PInstance.wifiP2pManager?.removeGroup(
                    wiFiP2PInstance.channel,
                    object : WifiP2pManager.ActionListener {
                        @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                        override fun onSuccess() {
                            Log.d(TAG, "Group deleted")
                            Log.d(TAG, "\tNetwordk Name: " + group.networkName)
                            Log.d(TAG, "\tInterface: " + group.getInterface())
                            Log.d(TAG, "\tPassword: " + group.passphrase)
                            Log.d(TAG, "\tOwner Name: " + group.owner.deviceName)
                            Log.d(TAG, "\tOwner Address: " + group.owner.deviceAddress)
                            Log.d(TAG, "\tClient list size: " + group.clientList.size)

                            groupAlreadyCreated = false

                            // Now we can create the group
                            createGroup()
                        }

                        override fun onFailure(p0: Int) {
                            Log.e(TAG, "Error deleting group")
                        }
                    })
            } else {
                createGroup()
            }
        }
    }

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    private fun createGroup() {
        if (!groupAlreadyCreated) {
            wiFiP2PInstance.wifiP2pManager?.createGroup(wiFiP2PInstance.channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.i(TAG, "Group created!")
                    groupAlreadyCreated = true
                }

                override fun onFailure(reason: Int) {
                    Log.e(TAG, "Error creating group. Reason: " + WiFiP2PError.fromReason(reason))
                }
            })
        }
    }

    private fun onMessageReceived(messageWrapper: MessageWrapper, fromAddress: InetAddress) {
        when (messageWrapper.messageType) {
            MessageWrapper.MessageType.CONNECTION_MESSAGE -> {
                val gson = Gson()

                val messageContentStr = messageWrapper.message
                val registrationMessageContent = gson.fromJson(messageContentStr, RegistrationMessageContent::class.java)
                val client = registrationMessageContent.wifiGroupDevice
                client.deviceServerSocketIP = fromAddress.hostAddress
                clientsConnected[client.deviceMac ?: ""] = client

                Log.d(TAG, "New client registered:")
                Log.d(TAG, "\tDevice name: " + client.deviceName)
                Log.d(TAG, "\tDecive mac: " + client.deviceMac)
                Log.d(TAG, "\tDevice IP: " + client.deviceServerSocketIP)
                Log.d(TAG, "\tDevice ServerSocket port: " + client.deviceServerSocketPort)

                // Sending to all clients that new client is connected
                clientsConnected.values.forEach { device ->
                    if (!client.deviceMac.equals(device.deviceMac)) {
                        sendConnectionMessage(device, client)
                    } else {
                        sendRegisteredDevicesMessage(device)
                    }
                }

                clientConnectedListener?.onClientConnected(client)
            }
            MessageWrapper.MessageType.DISCONNECTION_MESSAGE -> {
                val gson = Gson()

                val messageContentStr = messageWrapper.message
                val disconnectionMessageContent = gson.fromJson(messageContentStr, DisconnectionMessageContent::class.java)
                val client = disconnectionMessageContent.wifiGroupDevice
                clientsConnected.remove(client.deviceMac)

                Log.d(TAG, "Client disconnected:")
                Log.d(TAG, "\tDevice name: " + client.deviceName)
                Log.d(TAG, "\tDecive mac: " + client.deviceMac)
                Log.d(TAG, "\tDevice IP: " + client.deviceServerSocketIP)
                Log.d(TAG, "\tDevice ServerSocket port: " + client.deviceServerSocketPort)

                // Sending to all clients that a client is disconnected now
                clientsConnected.values.forEach { device ->
                    if (!client.deviceMac.equals(device.deviceMac)) {
                        sendDisconnectionMessage(device, client)
                    }
                }

                clientDisconnectedListener?.onClientDisconnected(client)
            }
            else -> {
                dataReceivedListener?.onDataReceived(messageWrapper)
            }
        }
    }

    private fun sendConnectionMessage(deviceToSend: WiFiGroupDevice, deviceConnected: WiFiGroupDevice) {
        val content = RegistrationMessageContent(deviceConnected)

        val gson = Gson()
        val messageWrapper = MessageWrapper(
            message = gson.toJson(content),
            messageType = MessageWrapper.MessageType.CONNECTION_MESSAGE
        )

        sendMessage(deviceToSend, messageWrapper)
    }

    private fun sendDisconnectionMessage(deviceToSend: WiFiGroupDevice, deviceDisconnected: WiFiGroupDevice) {
        val content = DisconnectionMessageContent(deviceDisconnected)

        val gson = Gson()

        val disconnectionMessage = MessageWrapper(
            message = gson.toJson(content),
            messageType = MessageWrapper.MessageType.DISCONNECTION_MESSAGE
        )

        sendMessage(deviceToSend, disconnectionMessage)
    }

    private fun sendRegisteredDevicesMessage(deviceToSend: WiFiGroupDevice) {
        val devicesConnected = mutableListOf<WiFiGroupDevice>()

        clientsConnected.values.forEach { device ->
            if (!device.deviceMac.equals(deviceToSend.deviceMac)) {
                devicesConnected.add(device)
            }
        }

        val content = RegisteredDevicesMessageContent(devicesConnected)

        val gson = Gson()

        val messageWrapper = MessageWrapper(
            message = gson.toJson(content),
            messageType = MessageWrapper.MessageType.REGISTERED_DEVICES
        )

        sendMessage(deviceToSend, messageWrapper)
    }
}
