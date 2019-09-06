package com.aqil.interfeces

/**
 * Call control interface for container activity.
 */
interface OnCallEvents {
    fun onCallHangUp()

    fun onCameraSwitch()

    fun onToggleMic(): Boolean
}