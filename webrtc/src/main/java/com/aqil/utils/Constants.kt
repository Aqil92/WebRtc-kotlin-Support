package com.aqil.utils

object Constants {
    const val EXTRA_ROOMID = "org.appspot.apprtc.ROOMID"
    const val IS_VIDEO = "IS_VIDEO"
    const val CAPTURE_PERMISSION_REQUEST_CODE = 1

    // List of mandatory application permissions.
     val MANDATORY_PERMISSIONS = arrayOf(
        "android.permission.MODIFY_AUDIO_SETTINGS",
        "android.permission.RECORD_AUDIO",
        "android.permission.INTERNET"
    )

    // Peer connection statistics callback period in ms.
    const val STAT_CALLBACK_PERIOD = 1000
    // Local preview screen position before call is connected.
    const val LOCAL_X_CONNECTING = 0
    const val LOCAL_Y_CONNECTING = 0
    const val LOCAL_WIDTH_CONNECTING = 100
    const val LOCAL_HEIGHT_CONNECTING = 100
    // Local preview screen position after call is connected.
    const val LOCAL_X_CONNECTED = 72
    const val LOCAL_Y_CONNECTED = 72
    const val LOCAL_WIDTH_CONNECTED = 25
    const  val LOCAL_HEIGHT_CONNECTED = 25
    // Remote video screen position
    const val REMOTE_X = 0
    const val REMOTE_Y = 0
    const val REMOTE_WIDTH = 100
    const  val REMOTE_HEIGHT = 100
}