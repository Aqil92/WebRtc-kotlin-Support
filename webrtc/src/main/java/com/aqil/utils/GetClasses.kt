package com.aqil.utils

import android.content.Context
import org.webrtc.*
import java.util.*

class GetClasses {

    companion object{
        fun getEagleClass():EglBase{
            return EglBase.create()
        }




    fun getVideoRendereList(): ArrayList<VideoRenderer.Callbacks> {
            return ArrayList()
        }

        fun createVideoCapturer(context: Context, videoCallEnabled: Boolean): VideoCapturer? {

            if(!videoCallEnabled)
                return null

            return (if (Camera2Enumerator.isSupported(context)) {
                createCameraCapturer(Camera2Enumerator(context))
            } else {
                createCameraCapturer(Camera1Enumerator(true))
            }) ?: return null
        }


        private fun createCameraCapturer(enumerator: CameraEnumerator): VideoCapturer? {
            val deviceNames = enumerator.deviceNames

            // First, try to find front facing camera
            for (deviceName in deviceNames) {
                if (enumerator.isFrontFacing(deviceName)) {
                    val videoCapturer = enumerator.createCapturer(deviceName, null)
                    if (videoCapturer != null) {
                        return videoCapturer
                    }
                }
            }

            // Front facing camera not found, try something else

            for (deviceName in deviceNames) {
                if (!enumerator.isFrontFacing(deviceName)) {

                    val videoCapturer = enumerator.createCapturer(deviceName, null)

                    if (videoCapturer != null) {
                        return videoCapturer
                    }
                }
            }

            return null
        }


    }
}