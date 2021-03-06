package dev.cardoso.bluechat.bluetooth.framework

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.os.Bundle
import android.os.Handler
import dev.cardoso.bluechat.bluetooth.*
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.*


class ChatService(handler: Handler) {
    // Member fields

    private val bluetoothAdapter: BluetoothAdapter
    private val handler: Handler
    private var secureAcceptThread: AcceptThread? = null
    private var insecureAcceptThread: AcceptThread? = null
    private var connectThread: ConnectThread? = null
    private var connectedThread: ConnectedThread? = null
    private var state: Int
    // Set the current state of the chat connection
    @Synchronized
    private fun setState(state: Int) {
        this.state = state
        handler.obtainMessage(MESSAGE_STATE_CHANGE, state, -1)
            .sendToTarget()
    }

    // get current connection state
    @Synchronized
    fun getState(): Int {
        return state
    }

    // start service
    @Synchronized
    fun start() { // Cancel any thread
        if (connectThread != null) {
            connectThread!!.cancel()
            connectThread = null
        }
        // Cancel any running thresd
        if (connectedThread != null) {
            connectedThread!!.cancel()
            connectedThread = null
        }
        setState(STATE_LISTEN)
        // Start the thread to listen on a BluetoothServerSocket
        if (secureAcceptThread == null) {
            secureAcceptThread = AcceptThread(true)
            secureAcceptThread!!.start()
        }
        if (insecureAcceptThread == null) {
            insecureAcceptThread = AcceptThread(false)
            insecureAcceptThread!!.start()
        }
    }

    // initiate connection to remote device
    @Synchronized
    fun connect(
        device: BluetoothDevice?,
        secure: Boolean
    ) { // Cancel any thread
        if (state == STATE_CONNECTING) {
            if (connectThread != null) {
                connectThread!!.cancel()
                connectThread = null
            }
        }
        // Cancel running thread
        if (connectedThread != null) {
            connectedThread!!.cancel()
            connectedThread = null
        }
        // Start the thread to connect with the given device
        connectThread = ConnectThread(device!!, secure)
        connectThread!!.start()
        setState(STATE_CONNECTING)
    }

    // manage Bluetooth connection
    @Synchronized
    fun connected(
        socket: BluetoothSocket?,
        device: BluetoothDevice
    ) { // Cancel the thread
        if (connectThread != null) {
            connectThread!!.cancel()
            connectThread = null
        }
        // Cancel running thread
        if (connectedThread != null) {
            connectedThread!!.cancel()
            connectedThread = null
        }
        if (secureAcceptThread != null) {
            secureAcceptThread!!.cancel()
            secureAcceptThread = null
        }
        if (insecureAcceptThread != null) {
            insecureAcceptThread!!.cancel()
            insecureAcceptThread = null
        }
        // Start the thread to manage the connection and perform transmissions
        connectedThread = ConnectedThread(socket!!)
        connectedThread!!.start()
        // Send the name of the connected device back to the UI Activity
        val msg = handler.obtainMessage(MESSAGE_DEVICE_NAME)
        val bundle = Bundle()
        bundle.putString(DEVICE_NAME, device.name)
        msg.data = bundle
        handler.sendMessage(msg)

        setState(STATE_CONNECTED)
    }

    // stop all threads
    @Synchronized
    fun stop() {
        if (connectThread != null) {
            connectThread!!.cancel()
            connectThread = null
        }
        if (connectedThread != null) {
            connectedThread!!.cancel()
            connectedThread = null
        }
        if (secureAcceptThread != null) {
            secureAcceptThread!!.cancel()
            secureAcceptThread = null
        }
        if (insecureAcceptThread != null) {
            insecureAcceptThread!!.cancel()
            insecureAcceptThread = null
        }
        setState(STATE_NONE)
    }

    fun write(out: ByteArray?) {
        var r: ConnectedThread?
        synchronized(this) {
            if (state != STATE_CONNECTED) return
            r = connectedThread
        }
        r!!.write(out)

    }

    private fun connectionFailed() {
        val msg = handler.obtainMessage(MESSAGE_TOAST)
        val bundle = Bundle()
        bundle.putString(TOAST, "No se puede conectar el dispositivo")
        msg.data = bundle
        handler.sendMessage(msg)
        // Start the service over to restart listening mode
        start()
    }

    private fun connectionLost() {
        val msg = handler.obtainMessage(MESSAGE_TOAST)
        val bundle = Bundle()
        bundle.putString(TOAST, "Se perdió la conexion")
        msg.data = bundle
        handler.sendMessage(msg)
        // Start the service over to restart listening mode
        start()
    }

    // runs while listening for incoming connections
    private inner class AcceptThread(secure: Boolean) : Thread() {
        private val serverSocket: BluetoothServerSocket?
        private val socketType: String
        override fun run() {
            name = "AcceptThread$socketType"
            var socket: BluetoothSocket?
            while (this@ChatService.state != STATE_CONNECTED) {
                socket = try {
                    serverSocket!!.accept()
                } catch (e: IOException) {
                    break
                }
                // If a connection was accepted
                if (socket != null) {
                    synchronized(this@ChatService) {
                        when (this@ChatService.state) {
                            STATE_LISTEN, STATE_CONNECTING -> {
                                // start the connected thread.
                                connected(
                                    socket, socket.remoteDevice
                                )
                            }
                            STATE_NONE, STATE_CONNECTED ->  // Either not ready or already connected. Terminate
                                // new socket.
                                try {
                                    socket.close()
                                } catch (e: IOException) {
                                }
                        }
                    }
                }
            }
        }

        fun cancel() {
            try {
                serverSocket!!.close()
            } catch (e: IOException) {
            }
        }

        init {
            var tmp: BluetoothServerSocket? = null
            socketType = if (secure) "Secure" else "Insecure"
            try {
                tmp = if (secure) {
                    bluetoothAdapter.listenUsingRfcommWithServiceRecord(
                        NAME_SECURE, MY_UUID_SECURE
                    )
                } else {
                    bluetoothAdapter
                        .listenUsingInsecureRfcommWithServiceRecord(
                            NAME_INSECURE,
                            MY_UUID_INSECURE
                        )
                }
            } catch (e: IOException) {
            }
            serverSocket = tmp
        }
    }

    // runs while attempting to make an outgoing connection
    private inner class ConnectThread(private val device: BluetoothDevice, secure: Boolean) :
        Thread() {
        private val socket: BluetoothSocket?
        private val socketType: String
        override fun run() {
            name = "ConnectThread$socketType"
            // Always cancel discovery because it will slow down a connection
            bluetoothAdapter.cancelDiscovery()
            // Make a connection to the BluetoothSocket
            try {
                socket!!.connect()
            } catch (e: IOException) {
                try {
                    socket!!.close()
                } catch (e2: IOException) {
                }
                connectionFailed()
                return
            }
            // Reset the ConnectThread because we're done
            synchronized(this@ChatService) { connectThread = null }
            // Start the connected thread
            connected(socket, device)
        }

        fun cancel() {
            try {
                socket!!.close()
            } catch (e: IOException) {
            }
        }

        init {
            var tmp: BluetoothSocket? = null
            socketType = if (secure) "Secure" else "Insecure"
            try {
                tmp = if (secure) {
                    device
                        .createRfcommSocketToServiceRecord(MY_UUID_SECURE)
                } else {
                    device
                        .createInsecureRfcommSocketToServiceRecord(MY_UUID_INSECURE)
                }
            } catch (e: IOException) {
            }
            socket = tmp
        }
    }

    // runs during a connection with a remote device
    private inner class ConnectedThread(
        private val bluetoothSocket: BluetoothSocket) :
        Thread() {
        private val inputStream: InputStream?
        private val outputStream: OutputStream?
        override fun run() {
            var buffer = ByteArray(990)
            var buff=ByteArray(0)
            var bytes:Int
            var bytesT =0
            var contador=0
            var mensaje=false
            // Keep listening to the InputStream
            while (true) {
                try { // Read from the InputStream
                    if (inputStream!!.available()>0){
                        bytes = inputStream!!.read(buffer)
                        contador++
                        if (bytes==990){
                            //Acumulación de partes-Imagen
                            bytesT += bytes
                            buff+=buffer
                        }
                        else if (bytes<990){
                            if (contador==1){
                                //mensaje normal
                                bytesT = bytes
                                buff=buffer
                                mensaje=true
                            }
                            else if (contador>=2){
                                //Acumulación de ultima parte-Imagen
                                bytesT += bytes
                                buff+=buffer
                                mensaje=true
                            }
                        }
                    }
                    else{
                        if (mensaje){
                            var bufferFinal=ByteArray(bytesT)
                            bufferFinal=buff.copyOfRange(0,bytesT)
                            handler.obtainMessage(MESSAGE_READ, bytesT, -1, bufferFinal).sendToTarget()
                            mensaje=false
                            bytesT=0
                            contador=0
                            buffer=ByteArray(990)
                            buff=ByteArray(0)
                        }else{
                        //println("NO HAY DATOS..")
                        }
                    }
                    //outputStream!!.flush()
                } catch (e: IOException) {
                    connectionLost()
                    // Start the service over to restart listening mode
                    this@ChatService.start()
                    break
                }
            }
        }

        // write to OutputStream
        fun write(buffer: ByteArray?) {
            try {
                outputStream!!.write(buffer)
                handler.obtainMessage(MESSAGE_WRITE, -1, -1, buffer).sendToTarget()
            } catch (e: IOException) {
            }
            outputStream!!.flush()
        }

        fun cancel() {
            try {
                bluetoothSocket.close()
            } catch (e: IOException) {
            }
        }

        init {
            var tmpIn: InputStream? = null
            var tmpOut: OutputStream? = null
            try {
                tmpIn = bluetoothSocket.inputStream
                tmpOut = bluetoothSocket.outputStream
            } catch (e: IOException) {
            }
            inputStream = tmpIn
            outputStream = tmpOut
        }
    }

    companion object {
        private const val NAME_SECURE = "BluetoothChatSecure"
        private const val NAME_INSECURE = "BluetoothChatInsecure"
        // Unique UUID for this application
        private val MY_UUID_SECURE = UUID
            .fromString("fa87c0d0-afac-11de-8a39-0800200c9a66")
        private val MY_UUID_INSECURE = UUID
            .fromString("8ce255c0-200a-11e0-ac64-0800200c9a66")
        // Constants that indicate the current connection state
        const val STATE_NONE = 0
        const val STATE_LISTEN = 1 // listening connection
        const val STATE_CONNECTING = 2 // initiate outgoing
        // connection
        const val STATE_CONNECTED = 3 // connected to remote device
    }

    init {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        state = STATE_NONE
        this.handler = handler
    }
}