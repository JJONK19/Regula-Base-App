package com.regula.demo.fingerprint.util

import android.Manifest

object PermissionsUtil {
    val BLE_PERMISSIONS = arrayOf(
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION
    )
}
