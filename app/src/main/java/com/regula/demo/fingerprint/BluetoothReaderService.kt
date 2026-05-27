package com.regula.demo.fingerprint

import android.bluetooth.BluetoothAdapter
import kotlin.jvm.Synchronized
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.Bundle
import android.bluetooth.BluetoothServerSocket
import android.content.Context
import android.os.Handler
import android.util.Log
import com.regula.demo.fingerprint.util.Constants
import com.regula.demo.fingerprint.util.Constants.STATE_CONNECTED
import com.regula.demo.fingerprint.util.Constants.STATE_CONNECTING
import com.regula.demo.fingerprint.util.Constants.STATE_LISTEN
import com.regula.demo.fingerprint.util.Constants.STATE_NONE
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.IllegalArgumentException
import java.util.*

class BluetoothReaderService(context: Context?, handler: Handler) {
    private val mAdapter: BluetoothAdapter
    private val mHandler: Handler
    private var mAcceptThread: AcceptThread? = null
    private var mConnectThread: ConnectThread? = null
    private var mConnectedThread: ConnectedThread? = null
    private var mState: Int
    private var mInStream: InputStream? = null
    private var mOutStream: OutputStream? = null

    var state: Int
        get() = mState
        private set(state) {
            if (D) Log.d(TAG, "setState() $mState -> $state")
            mState = state
            mHandler.obtainMessage(Constants.MESSAGE_STATE_CHANGE, state, -1).sendToTarget()
        }

    @Synchronized
    fun start() {
        if (D) Log.d(TAG, "start")
        if (mConnectThread != null) {
            mConnectThread!!.cancel()
            mConnectThread = null
        }
        if (mConnectedThread != null) {
            mConnectedThread!!.cancel()
            mConnectedThread = null
        }
        if (mAcceptThread == null) {
            mAcceptThread = AcceptThread()
            mAcceptThread!!.start()
        }
        state = STATE_LISTEN
    }

    @Synchronized
    fun connect(device: BluetoothDevice) {
        if (D) Log.d(TAG, "connect to: $device")
        if (mState == STATE_CONNECTING) {
            if (mConnectThread != null) {
                mConnectThread!!.cancel()
                mConnectThread = null
            }
        }
        if (mConnectedThread != null) {
            mConnectedThread!!.cancel()
            mConnectedThread = null
        }
        mConnectThread = ConnectThread(device)
        mConnectThread!!.start()
        state = STATE_CONNECTING
    }

    @Synchronized
    fun connected(socket: BluetoothSocket?, device: BluetoothDevice) {
        if (D) Log.d(TAG, "connected")
        if (mConnectThread != null) {
            mConnectThread!!.cancel()
            mConnectThread = null
        }
        if (mConnectedThread != null) {
            mConnectedThread!!.cancel()
            mConnectedThread = null
        }
        if (mAcceptThread != null) {
            mAcceptThread!!.cancel()
            mAcceptThread = null
        }
        mConnectedThread = ConnectedThread(socket)
        mConnectedThread!!.start()
        val msg = mHandler.obtainMessage(Constants.MESSAGE_DEVICE_NAME)
        val bundle = Bundle()
        bundle.putString(Constants.DEVICE_NAME, device.name)
        msg.data = bundle
        mHandler.sendMessage(msg)
        state = STATE_CONNECTED
    }

    @Synchronized
    fun stop() {
        if (D) Log.d(TAG, "stop")
        if (mConnectThread != null) {
            mConnectThread!!.cancel()
            mConnectThread = null
        }
        if (mConnectedThread != null) {
            mConnectedThread!!.cancel()
            mConnectedThread = null
        }
        if (mAcceptThread != null) {
            mAcceptThread!!.cancel()
            mAcceptThread = null
        }
        state = STATE_NONE
    }

    fun write(out: ByteArray?) {
        var r: ConnectedThread?
        synchronized(this) {
            if (mState != STATE_CONNECTED) return
            r = mConnectedThread
        }
        r!!.write(out)
    }

    private fun connectionFailed() {
        state = STATE_LISTEN
        val msg = mHandler.obtainMessage(Constants.MESSAGE_TOAST)
        val bundle = Bundle()
        bundle.putString(Constants.TOAST, "Unable to connect device")
        msg.data = bundle
        mHandler.sendMessage(msg)
    }

    private fun connectionLost() {
        state = STATE_LISTEN
        val msg = mHandler.obtainMessage(Constants.MESSAGE_TOAST)
        val bundle = Bundle()
        bundle.putString(Constants.TOAST, "Device connection was lost")
        msg.data = bundle
        mHandler.sendMessage(msg)
    }

    private inner class AcceptThread : Thread() {
        private val mmServerSocket: BluetoothServerSocket?
        override fun run() {
            if (D) Log.d(TAG, "BEGIN mAcceptThread$this")
            name = "AcceptThread"
            var socket: BluetoothSocket? = null
            while (mState != STATE_CONNECTED) {
                socket = try {
                    mmServerSocket!!.accept()
                } catch (e: IOException) {
                    Log.e(TAG, "accept() failed", e)
                    break
                }
                if (socket != null) {
                    synchronized(this@BluetoothReaderService) {
                        when (mState) {
                            STATE_LISTEN, STATE_CONNECTING ->
                                connected(socket, socket.remoteDevice)
                            STATE_NONE, STATE_CONNECTED ->
                                try {
                                    socket.close()
                                } catch (e: IOException) {
                                    Log.e(TAG, "Could not close unwanted socket", e)
                                }
                            else -> {
                                throw IllegalArgumentException("state is not valid")
                            }
                        }
                    }
                }
            }
            if (D) Log.i(TAG, "END mAcceptThread")
        }

        fun cancel() {
            if (D) Log.d(TAG, "cancel $this")
            try {
                mmServerSocket!!.close()
            } catch (e: IOException) {
                Log.e(TAG, "close() of server failed", e)
            }
        }

        init {
            var tmp: BluetoothServerSocket? = null
            try {
                tmp = mAdapter.listenUsingRfcommWithServiceRecord(NAME, MY_UUID)
            } catch (e: IOException) {
                Log.e(TAG, "listen() failed", e)
            }
            mmServerSocket = tmp
        }
    }

    private inner class ConnectThread(private val mmDevice: BluetoothDevice) : Thread() {
        private val mmSocket: BluetoothSocket?
        override fun run() {
            Log.i(TAG, "BEGIN mConnectThread")
            name = "ConnectThread"
            mAdapter.cancelDiscovery()
            try {
                mmSocket!!.connect()
            } catch (e: IOException) {
                connectionFailed()
                try {
                    mmSocket!!.close()
                } catch (e2: IOException) {
                    Log.e(TAG, "unable to close() socket during connection failure", e2)
                }
                this@BluetoothReaderService.start()
                return
            }
            synchronized(this@BluetoothReaderService) { mConnectThread = null }
            connected(mmSocket, mmDevice)
        }

        fun cancel() {
            try {
                mmSocket!!.close()
            } catch (e: IOException) {
                Log.e(TAG, "close() of connect socket failed", e)
            }
        }

        init {
            var tmp: BluetoothSocket? = null
            try {
                tmp = mmDevice.createRfcommSocketToServiceRecord(MY_UUID)
            } catch (e: IOException) {
                Log.e(TAG, "create() failed", e)
            }
            mmSocket = tmp
        }
    }

    private inner class ConnectedThread(socket: BluetoothSocket?) : Thread() {
        private val mmSocket: BluetoothSocket?
        private val mmInStream: InputStream?
        private val mmOutStream: OutputStream?
        override fun run() {
            Log.i(TAG, "BEGIN mConnectedThread")
            val buffer = ByteArray(1024)
            var bytes: Int
            while (true) {
                try {
                    bytes = mmInStream!!.read(buffer)
                    mHandler.obtainMessage(Constants.MESSAGE_READ, bytes, -1, buffer).sendToTarget()
                    try {
                        currentThread()
                        sleep(50)
                    } catch (e: InterruptedException) {
                        e.printStackTrace()
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "disconnected", e)
                    connectionLost()
                    break
                }
            }
        }

        fun write(buffer: ByteArray?) {
            try {
                mmOutStream!!.write(buffer)
                mHandler.obtainMessage(Constants.MESSAGE_WRITE, -1, -1, buffer)
                    .sendToTarget()
            } catch (e: IOException) {
                Log.e(TAG, "Exception during write", e)
            }
        }

        fun cancel() {
            try {
                mmSocket!!.close()
            } catch (e: IOException) {
                Log.e(TAG, "close() of connect socket failed", e)
            }
        }

        init {
            Log.d(TAG, "create ConnectedThread")
            mmSocket = socket
            var tmpIn: InputStream? = null
            var tmpOut: OutputStream? = null
            try {
                tmpIn = socket!!.inputStream
                tmpOut = socket.outputStream
            } catch (e: IOException) {
                Log.e(TAG, "temp sockets not created", e)
            }
            mmInStream = tmpIn
            mmOutStream = tmpOut
            mInStream = tmpIn
            mOutStream = tmpOut
        }
    }

    fun writestream(buffer: ByteArray?): Boolean {
        var ret = false
        if (mState == STATE_CONNECTED) {
            try {
                mOutStream!!.write(buffer)
                ret = true
            } catch (e: IOException) {
                Log.e(TAG, "Exception during write", e)
            }
        }
        return ret
    }

    fun readstream(buffer: ByteArray?): Int {
        var bytes = 0
        if (mState == STATE_CONNECTED) {
            try {
                bytes = mInStream!!.read(buffer)
            } catch (e: IOException) {
                Log.e(TAG, "Exception during read", e)
            }
        }
        return bytes
    }

    companion object {
        private const val TAG = "BluetoothChatService"
        private const val D = true
        private const val NAME = "BluetoothChat"
        private val MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    init {
        mAdapter = BluetoothAdapter.getDefaultAdapter()
        mState = STATE_NONE
        mHandler = handler
    }
}
