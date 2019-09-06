package com.aqil.webrtc

import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager.LayoutParams.*
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.aqil.interfeces.AppRTCClient
import com.aqil.interfeces.OnCallEvents
import com.aqil.interfeces.PeerConnectionEvents
import com.aqil.utils.Constants
import com.aqil.utils.Constants.EXTRA_ROOMID
import com.aqil.utils.Constants.IS_VIDEO
import com.aqil.utils.Constants.STAT_CALLBACK_PERIOD
import com.aqil.utils.GetClasses
import com.aqil.utils.GetClasses.Companion.createVideoCapturer
import com.aqil.web_rtc.AppRTCAudioManager
import com.aqil.web_rtc.PeerConnectionClient
import com.aqil.web_rtc.WebSocketRTCClient
import com.aqil.webrtc.view.PercentFrameLayout
import kotlinx.android.synthetic.main.activity_call.*
import org.webrtc.*

class CallActivity : AppCompatActivity(), AppRTCClient.SignalingEvents, PeerConnectionEvents,
    OnCallEvents {

    private val LOG_TAG = "CallActivityLog"

    private var peerConnectionClient: PeerConnectionClient? = PeerConnectionClient()
    private var appRtcClient: AppRTCClient? = null
    private var signalingParameters: AppRTCClient.SignalingParameters? = null
    private var audioManager: AppRTCAudioManager? = null
    private var rootEglBase = GetClasses.getEagleClass()
    private val remoteRenderer = GetClasses.getVideoRendereList()
    private var activityRunning: Boolean = false

    private var roomConnectionParameters: AppRTCClient.RoomConnectionParameters? = null
    private var peerConnectionParameters: PeerConnectionClient.PeerConnectionParameters? = null

    private var iceConnected: Boolean = false
    private var isError: Boolean = false
    private var callStartedTimeMs: Long = 0
    private var micEnabled = true

    private var isSpeakerOn = true

    private  var roomId:String?=""
    private  var isVideo:Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_call)

        callForWindowAdjustment()

        init()

        uploadVideo()


        getExtraIntent()

        settingParametersForCall()

        startCall()

        setupListeners()

    }

    fun updateVideoView(remote_video_layout: PercentFrameLayout,
                        local_video_layout:PercentFrameLayout,
                        remote_video_view: SurfaceViewRenderer,
                        local_video_view: SurfaceViewRenderer,
                        iceConnected:Boolean) {

        remote_video_layout.setPosition(
            Constants.REMOTE_X,
            Constants.REMOTE_Y,
            Constants.REMOTE_WIDTH,
            Constants.REMOTE_HEIGHT
        )
        remote_video_view.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        remote_video_view.setMirror(false)

        if (iceConnected) {
            local_video_layout.setPosition(
                Constants.LOCAL_X_CONNECTED,
                Constants.LOCAL_Y_CONNECTED,
                Constants.LOCAL_WIDTH_CONNECTED,
                Constants.LOCAL_HEIGHT_CONNECTED
            )
            local_video_view.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        } else {
            local_video_layout.setPosition(
                Constants.LOCAL_X_CONNECTING,
                Constants.LOCAL_Y_CONNECTING,
                Constants.LOCAL_WIDTH_CONNECTING,
                Constants.LOCAL_HEIGHT_CONNECTING
            )
            local_video_view.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        }

        local_video_view.setMirror(true)

        local_video_view.requestLayout()
        remote_video_view.requestLayout()
    }

    fun uploadVideo(){
        updateVideoView(remote_video_layout,
            local_video_layout,
            remote_video_view,
            local_video_view,iceConnected)
    }


    private fun init(){
        remoteRenderer.add(remote_video_view)

        // Create video renderers.
        local_video_view.init(rootEglBase.eglBaseContext, null)
        remote_video_view.init(rootEglBase.eglBaseContext, null)

        local_video_view.setZOrderMediaOverlay(true)
        local_video_view.setEnableHardwareScaler(true)
        remote_video_view.setEnableHardwareScaler(true)


    }


    private fun getExtraIntent(){
        val intent = intent
        roomId = intent.getStringExtra(EXTRA_ROOMID)
        isVideo = intent.getBooleanExtra(IS_VIDEO,false)
        isSpeakerOn=!isVideo
        Log.d(LOG_TAG, "Room ID: $roomId")
    }

    private fun settingParametersForCall(){
      // If capturing format is not specified for screencapture, use screen resolution.
      peerConnectionParameters = PeerConnectionClient.PeerConnectionParameters.createDefault(isVideo)

      // Create connection client. Use DirectRTCClient if room name is an IP otherwise use the
      // standard WebSocketRTCClient.
      appRtcClient = WebSocketRTCClient(this)

      // Create connection parameters.
      roomConnectionParameters = AppRTCClient.RoomConnectionParameters("https://appr.tc", roomId.toString(), false)

     // setupListeners()


      peerConnectionClient?.createPeerConnectionFactory(this, peerConnectionParameters!!, this)
    }

    private fun callForWindowAdjustment(){
        window.addFlags(
            FLAG_DISMISS_KEYGUARD or FLAG_SHOW_WHEN_LOCKED
                    or FLAG_TURN_SCREEN_ON
        )
         window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)

    }

    private fun startCall() {
        callStartedTimeMs = System.currentTimeMillis()

        // Start room connection.

        Log.v(LOG_TAG,getString(R.string.connecting_to, roomConnectionParameters!!.roomUrl))
        appRtcClient?.connectToRoom(roomConnectionParameters!!)

        // Create and audio manager that will take care of audio routing,
        // audio modes, audio device enumeration etc.
        audioManager = AppRTCAudioManager.create(this)
        onToggleSpeaker()
        // Store existing audio settings and change audio mode to
        // MODE_IN_COMMUNICATION for best possible VoIP performance.
        Log.d(LOG_TAG, "Starting the audio manager...")

    }

    private fun setupListeners() {
        bt_cut.setOnClickListener { view -> onCallHangUp() }

        bt_mute.setOnClickListener { view -> onToggleMic() }

        bt_camera.setOnClickListener { view -> onCameraSwitch() }

        bt_speeker.setOnClickListener { view -> onToggleSpeaker() }
    }

    private fun onToggleSpeaker() {
        Log.d(LOG_TAG, "onToggleSpeaker: $isSpeakerOn")
        isSpeakerOn = !isSpeakerOn
        audioManager?.setSpeakerphoneOn(isSpeakerOn)
    }






    // Activity interfaces
    override fun onPause() {
        super.onPause()
        activityRunning = false
        // Don't stop the video when using screencapture to allow user to show other apps to the remote
        // end.
        peerConnectionClient?.stopVideoSource()
    }

    override fun onResume() {
        super.onResume()
        activityRunning = true
        // Video is not paused for screencapture. See onPause.
        peerConnectionClient?.startVideoSource()
    }



    override  fun onDestroy() {
        disconnect()

        activityRunning = false
        rootEglBase?.release()
        super.onDestroy()
    }

    // Should be called from UI thread
    private fun callConnected() {
        val delta = System.currentTimeMillis() - callStartedTimeMs
        Log.i(LOG_TAG, "Call connected: delay=" + delta + "ms")
        if (peerConnectionClient == null || isError) {
            Log.w(LOG_TAG, "Call is connected in closed or error state")
            return
        }
        // Update video view.
        uploadVideo()
        // Enable statistics callback.
        peerConnectionClient?.enableStatsEvents(true, STAT_CALLBACK_PERIOD)
    }

    // This method is called when the audio manager reports audio device change,
    // e.g. from wired headset to speakerphone.
    private fun onAudioManagerDevicesChanged(
        device: AppRTCAudioManager.AudioDevice, availableDevices: Set<AppRTCAudioManager.AudioDevice>
    ) {
        Log.d(
            LOG_TAG, "onAudioManagerDevicesChanged: " + availableDevices + ", "
                    + "selected: " + device
        )
        // TODO(henrika): add callback handler.
    }



    // -----Implementation of AppRTCClient.AppRTCSignalingEvents ---------------
    // All callbacks are invoked from websocket signaling looper thread and
    // are routed to UI thread.
    private fun onConnectedToRoomInternal(params: AppRTCClient.SignalingParameters) {
        val delta = System.currentTimeMillis() - callStartedTimeMs

        signalingParameters = params
        Log.v(LOG_TAG,"Creating peer connection, delay=" + delta + "ms")

        peerConnectionClient!!.createPeerConnection(
            rootEglBase.eglBaseContext, local_video_view,
            remoteRenderer, createVideoCapturer(this,peerConnectionParameters!!.videoCallEnabled), signalingParameters!!
        )

        if (signalingParameters!!.initiator) {
            Log.v(LOG_TAG,"Creating OFFER...")
            // Create offer. Offer SDP will be sent to answering client in
            // PeerConnectionEvents.onLocalDescription event.
            peerConnectionClient!!.createOffer()
        } else {
                peerConnectionClient!!.setRemoteDescription(params.offerSdp)
                Log.v(LOG_TAG,"Creating ANSWER...")
                // Create answer. Answer SDP will be sent to offering client in
                // PeerConnectionEvents.onLocalDescription event.
                peerConnectionClient!!.createAnswer()

                // Add remote ICE candidates from room.
                for (iceCandidate in params.iceCandidates!!) {
                    peerConnectionClient!!.addRemoteIceCandidate(iceCandidate)
                }
            }
        }

    // Disconnect from remote resources, dispose of local resources, and exit.
    private fun disconnect() {
        activityRunning = false
        if (appRtcClient != null) {
            appRtcClient!!.disconnectFromRoom()
            appRtcClient = null
        }
        if (peerConnectionClient != null) {
            peerConnectionClient!!.close()
            peerConnectionClient = null
        }
        local_video_view.release()
        remote_video_view.release()
        if (audioManager != null) {
            audioManager!!.stop()
            audioManager = null
        }
        if (iceConnected && !isError) {
            setResult(RESULT_OK)
        } else {
            setResult(RESULT_CANCELED)
        }
        finish()
    }

   override fun onConnectedToRoom(params: AppRTCClient.SignalingParameters) {
        runOnUiThread { onConnectedToRoomInternal(params) }
    }

    override fun onRemoteDescription(sdp: SessionDescription) {
        val delta = System.currentTimeMillis() - callStartedTimeMs
        runOnUiThread {
            if (peerConnectionClient == null) {
                Log.e(LOG_TAG, "Received remote SDP for non-initilized peer connection.")
                return@runOnUiThread
            }
            logAndToast("Received remote " + sdp.type + ", delay=" + delta + "ms")
            peerConnectionClient!!.setRemoteDescription(sdp)
            if (!signalingParameters!!.initiator) {
                logAndToast("Creating ANSWER...")
                // Create answer. Answer SDP will be sent to offering client in
                // PeerConnectionEvents.onLocalDescription event.
                peerConnectionClient!!.createAnswer()
            }
        }
    }

    override fun onRemoteIceCandidate(candidate: IceCandidate) {
        runOnUiThread {
            if (peerConnectionClient == null) {
                Log.e(LOG_TAG, "Received ICE candidate for a non-initialized peer connection.")
                return@runOnUiThread
            }
            peerConnectionClient!!.addRemoteIceCandidate(candidate)
        }
    }



    override fun onChannelClose() {
        runOnUiThread {
            logAndToast("Remote end hung up; dropping PeerConnection")
            disconnect()
        }
    }

    override  fun onChannelError(description: String) {

        logAndToast(description)
    }


    override fun onIceCandidate(candidate: IceCandidate) {
        runOnUiThread {
            if (appRtcClient != null) {
                appRtcClient!!.sendLocalIceCandidate(candidate)
            }
        }
    }

    override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {
        runOnUiThread {
            if (appRtcClient != null) {
                appRtcClient!!.sendLocalIceCandidateRemovals(candidates)
            }
        }
    }

    override fun onIceConnected() {
        val delta = System.currentTimeMillis() - callStartedTimeMs
        runOnUiThread {
            logAndToast("ICE connected, delay=" + delta + "ms")
            iceConnected = true
            callConnected()
        }
    }

    override fun onIceDisconnected() {
        runOnUiThread {
            logAndToast("onIceDisconnected")
            iceConnected = false
            disconnect()
        }
    }

    override fun onPeerConnectionClosed() {}

    override  fun onPeerConnectionStatsReady(reports: Array<StatsReport>) {
        runOnUiThread { }
    }

   override fun onPeerConnectionError(description: String) {
       logAndToast(description)
    }

    override fun onRemoteIceCandidatesRemoved(candidates: Array<IceCandidate>) {
        runOnUiThread {
            if (peerConnectionClient == null) {
                Log.e(LOG_TAG, "Received ICE candidate removals for a non-initialized peer connection.")
                return@runOnUiThread
            }
            peerConnectionClient!!.removeRemoteIceCandidates(candidates)
        }
    }

    override fun onLocalDescription(sdp: SessionDescription?) {
        val delta = System.currentTimeMillis() - callStartedTimeMs
        runOnUiThread {
            if (appRtcClient != null) {
                logAndToast("Sending " + sdp?.type + ", delay=" + delta + "ms")
                if (signalingParameters?.initiator!!) {
                    appRtcClient!!.sendOfferSdp(sdp!!)
                } else {
                    appRtcClient!!.sendAnswerSdp(sdp!!)
                }
            }
            if (peerConnectionParameters?.videoMaxBitrate!! > 0) {
                logAndToast("Set video maximum bitrate: " + peerConnectionParameters?.videoMaxBitrate)
                peerConnectionClient?.setVideoMaxBitrate(peerConnectionParameters?.videoMaxBitrate)
            }
        }
    }


    // CallFragment.OnCallEvents interface implementation.
    override   fun onCallHangUp() {
        disconnect()
    }

    override  fun onCameraSwitch() {
        peerConnectionClient?.switchCamera()
    }

    override fun onToggleMic(): Boolean {
        if (peerConnectionClient != null) {
            micEnabled = !micEnabled
            peerConnectionClient?.setAudioEnabled(micEnabled)
        }
        return micEnabled
    }

    private fun logAndToast(msg:String){
        Toast.makeText(this,msg,Toast.LENGTH_SHORT).show()
    }
}
