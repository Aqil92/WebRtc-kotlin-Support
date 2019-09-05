package com.aqil.webrtc

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.annotation.NonNull
import androidx.appcompat.app.AppCompatActivity
import com.aqil.webrtc.utils.Constants.EXTRA_ROOMID
import com.aqil.webrtc.utils.Constants.IS_VIDEO
import kotlinx.android.synthetic.main.activity_main.*
import pub.devrel.easypermissions.EasyPermissions

class MainActivity : AppCompatActivity() {

    private val RC_CALL = 111
    private val perms = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setOnClickListener()
    }

   override fun onRequestPermissionsResult(requestCode: Int, @NonNull permissions: Array<String>, @NonNull grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this)
    }


    private fun setOnClickListener(){

        btAudio.setOnClickListener {
            val id=etAudio.text.toString()
            if(id!=""){
                if (EasyPermissions.hasPermissions(this, *perms)) {
                    connectToRoom(id,false)
                } else {
                    EasyPermissions.requestPermissions(this, "Need some permissions", RC_CALL, *perms)
                }
            }
        }

        btVideo.setOnClickListener {
            val id=etVideo.text.toString()
            if(etVideo.text.toString()!=""){

                if (EasyPermissions.hasPermissions(this, *perms)) {
                    connectToRoom(id,true)
                } else {
                    EasyPermissions.requestPermissions(this, "Need some permissions", RC_CALL, *perms)
                }

            }
        }

    }

    private fun connectToRoom(roomId: String,isVideo:Boolean) {
        val intent = Intent(this, CallActivity::class.java)
        intent.putExtra(EXTRA_ROOMID, roomId)
        intent.putExtra(IS_VIDEO, isVideo)
        startActivityForResult(intent, 1)
    }
}
