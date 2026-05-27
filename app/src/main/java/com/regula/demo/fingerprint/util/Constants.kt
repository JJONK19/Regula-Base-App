package com.regula.demo.fingerprint.util

object Constants {
    const val STATE_NONE = 0
    const val STATE_LISTEN = 1
    const val STATE_CONNECTING = 2
    const val STATE_CONNECTED = 3

    const val MESSAGE_STATE_CHANGE = 1
    const val MESSAGE_READ = 2
    const val MESSAGE_WRITE = 3
    const val MESSAGE_DEVICE_NAME = 4
    const val MESSAGE_TOAST = 5
    const val REQUEST_PERMISSION_CODE = 1
    const val CMD_GETIMAGE: Byte = 0x30

    const val IMG288 = 288

    const val DEVICE_NAME = "device_name"
    const val TOAST = "toast"
}
