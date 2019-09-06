package com.aqil.web_rtc

import android.content.Context
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.util.Log
import com.aqil.interfeces.AppRTCClient
import com.aqil.interfeces.PeerConnectionEvents
import org.webrtc.*
import org.webrtc.voiceengine.WebRtcAudioManager
import org.webrtc.voiceengine.WebRtcAudioUtils
import java.io.File
import java.io.IOException
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.regex.Pattern

/**
 * Peer connection client implementation.
 *
 *
 *
 * All public methods are routed to local looper thread.
 * All PeerConnectionEvents callbacks are invoked from the same looper thread.
 * This class is a singleton.
 */
class PeerConnectionClient {
    private val pcObserver = PCObserver()
    private val sdpObserver = SDPObserver()
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    private var context: Context? = null
    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    internal var options:PeerConnectionFactory.Options? = null
    private var audioSource: AudioSource? = null
    private var videoSource: VideoSource? = null
    var isVideoCallEnabled:Boolean = false
        private set
    private var preferIsac:Boolean = false
    private var preferredVideoCodec:String? = null
    private var videoCapturerStopped:Boolean = false
    private var isError:Boolean = false
    private var statsTimer: Timer? = null
    private var localRender: VideoRenderer.Callbacks? = null
    private var remoteRenders:List<VideoRenderer.Callbacks>? = null
    private var signalingParameters: AppRTCClient.SignalingParameters? = null
    private var pcConstraints: MediaConstraints? = null
    private var videoWidth:Int = 0
    private var videoHeight:Int = 0
    private var videoFps:Int = 0
    private var audioConstraints:MediaConstraints? = null
    private var aecDumpFileDescriptor: ParcelFileDescriptor? = null
    private var sdpMediaConstraints:MediaConstraints? = null
    private var peerConnectionParameters:PeerConnectionParameters? = null
    // Queued remote ICE candidates are consumed only after both local and
    // remote descriptions are set. Similarly local ICE candidates are sent to
    // remote peer after both local and remote description are set.
    private var queuedRemoteCandidates: LinkedList<IceCandidate>? = null
    private var events: PeerConnectionEvents? = null
    private var isInitiator:Boolean = false
    private var localSdp:SessionDescription? = null // either offer or answer SDP
    private var mediaStream:MediaStream? = null
    private var videoCapturer:VideoCapturer? = null
    // enableVideo is set to true if video should be rendered and sent.
    private var renderVideo:Boolean = false
    private var localVideoTrack:VideoTrack? = null
    private var remoteVideoTrack:VideoTrack? = null
    private var localVideoSender:RtpSender? = null
    // enableAudio is set to true if audio should be sent.
    private var enableAudio:Boolean = false
    private var localAudioTrack: AudioTrack? = null
    private var dataChannel:DataChannel? = null
    private var dataChannelEnabled:Boolean = false

    val isHDVideo:Boolean
        get() = if (!isVideoCallEnabled) {
            false
        } else videoWidth * videoHeight >= 1280 * 720

    /**
     * Peer connection parameters.
     */
    class DataChannelParameters(internal val ordered:Boolean, internal val maxRetransmitTimeMs:Int, internal val maxRetransmits:Int,
                                internal val protocol:String, internal val negotiated:Boolean, val id:Int)

    /**
     * Peer connection parameters.
     */
   class PeerConnectionParameters private constructor(val videoCallEnabled:Boolean, internal val loopback:Boolean, internal val tracing:Boolean,
                                                       internal val videoWidth:Int, internal val videoHeight:Int, internal val videoFps:Int, val videoMaxBitrate:Int, internal val videoCodec:String,
                                                       internal val videoCodecHwAcceleration:Boolean, internal val videoFlexfecEnabled:Boolean, internal val audioStartBitrate:Int,
                                                       internal val audioCodec:String, internal val noAudioProcessing:Boolean, internal val aecDump:Boolean, val useOpenSLES:Boolean,
                                                       internal val disableBuiltInAEC:Boolean, internal val disableBuiltInAGC:Boolean, internal val disableBuiltInNS:Boolean,
                                                       internal val enableLevelControl:Boolean, internal val dataChannelParameters:DataChannelParameters? = null) {
        companion object {

            fun createDefault(isVideo:Boolean):PeerConnectionParameters {
                return PeerConnectionParameters(isVideo, false,
                    false, 0, 0, 0,
                    0, "VP8",
                    true,
                    false,
                    0, "OPUS",
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false)
            }
        }
    }



    init{
        // Executor thread is started once in private ctor and is used for all
        // peer connection API calls to ensure new peer connection factory is
        // created on the same thread as previously destroyed factory.
    }

    fun setPeerConnectionFactoryOptions(options:PeerConnectionFactory.Options) {
        this.options = options
    }

    fun createPeerConnectionFactory(context: Context,
                                    peerConnectionParameters:PeerConnectionParameters, events:PeerConnectionEvents) {
        this.peerConnectionParameters = peerConnectionParameters
        this.events = events
        isVideoCallEnabled = peerConnectionParameters.videoCallEnabled
        dataChannelEnabled = peerConnectionParameters.dataChannelParameters != null
        // Reset variables to initial states.
        this.context = null
        factory = null
        peerConnection = null
        preferIsac = false
        videoCapturerStopped = false
        isError = false
        queuedRemoteCandidates = null
        localSdp = null // either offer or answer SDP
        mediaStream = null
        videoCapturer = null
        renderVideo = true
        localVideoTrack = null
        remoteVideoTrack = null
        localVideoSender = null
        enableAudio = true
        localAudioTrack = null
        statsTimer = Timer()

        executor.execute({ createPeerConnectionFactoryInternal(context) })
    }

    fun createPeerConnection(renderEGLContext:EglBase.Context,
                             localRender:VideoRenderer.Callbacks, remoteRenders:List<VideoRenderer.Callbacks>,
                             videoCapturer:VideoCapturer?, signalingParameters: AppRTCClient.SignalingParameters
    ) {
        if (peerConnectionParameters == null)
        {
            Log.e(TAG, "Creating peer connection without initializing factory.")
            return
        }
        this.localRender = localRender
        this.remoteRenders = remoteRenders
        this.videoCapturer = videoCapturer
        this.signalingParameters = signalingParameters
        executor.execute { try {
            createMediaConstraintsInternal()
            createPeerConnectionInternal(renderEGLContext)
        } catch (e:Exception) {
            reportError("Failed to create peer connection: " + e.message)
            throw e
        }
        }
    }

    fun close() {
        executor.execute { closeInternal() }
    }

    private fun createPeerConnectionFactoryInternal(context: Context) {
        PeerConnectionFactory.initializeInternalTracer()
        if (peerConnectionParameters!!.tracing)
        {
            PeerConnectionFactory.startInternalTracingCapture(
                Environment.getExternalStorageDirectory().absolutePath + File.separator
                        + "webrtc-trace.txt"
            )
        }
        Log.d(TAG,
            "Create peer connection factory. Use video: " + peerConnectionParameters!!.videoCallEnabled)
        isError = false

        // Initialize field trials.
        if (peerConnectionParameters!!.videoFlexfecEnabled)
        {
            PeerConnectionFactory.initializeFieldTrials(VIDEO_FLEXFEC_FIELDTRIAL)
            Log.d(TAG, "Enable FlexFEC field trial.")
        }
        else
        {
            PeerConnectionFactory.initializeFieldTrials("")
        }

        // Check preferred video codec.
        preferredVideoCodec = VIDEO_CODEC_VP8
        if (isVideoCallEnabled && peerConnectionParameters!!.videoCodec != null)
        {
            if (peerConnectionParameters!!.videoCodec == VIDEO_CODEC_VP9)
            {
                preferredVideoCodec = VIDEO_CODEC_VP9
            }
            else if (peerConnectionParameters!!.videoCodec == VIDEO_CODEC_H264)
            {
                preferredVideoCodec = VIDEO_CODEC_H264
            }
        }
        Log.d(TAG, "Preferred video codec: " + preferredVideoCodec!!)

        // Check if ISAC is used by default.
        preferIsac = peerConnectionParameters!!.audioCodec != null && peerConnectionParameters!!.audioCodec == AUDIO_CODEC_ISAC

        // Enable/disable OpenSL ES playback.
        if (!peerConnectionParameters!!.useOpenSLES)
        {
            Log.d(TAG, "Disable OpenSL ES audio even if device supports it")
            WebRtcAudioManager.setBlacklistDeviceForOpenSLESUsage(true /* enable */)
        }
        else
        {
            Log.d(TAG, "Allow OpenSL ES audio if device supports it")
            WebRtcAudioManager.setBlacklistDeviceForOpenSLESUsage(false)
        }

        if (peerConnectionParameters!!.disableBuiltInAEC)
        {
            Log.d(TAG, "Disable built-in AEC even if device supports it")
            WebRtcAudioUtils.setWebRtcBasedAcousticEchoCanceler(true)
        }
        else
        {
            Log.d(TAG, "Enable built-in AEC if device supports it")
            WebRtcAudioUtils.setWebRtcBasedAcousticEchoCanceler(false)
        }

        if (peerConnectionParameters!!.disableBuiltInAGC)
        {
            Log.d(TAG, "Disable built-in AGC even if device supports it")
            WebRtcAudioUtils.setWebRtcBasedAutomaticGainControl(true)
        }
        else
        {
            Log.d(TAG, "Enable built-in AGC if device supports it")
            WebRtcAudioUtils.setWebRtcBasedAutomaticGainControl(false)
        }

        if (peerConnectionParameters!!.disableBuiltInNS)
        {
            Log.d(TAG, "Disable built-in NS even if device supports it")
            WebRtcAudioUtils.setWebRtcBasedNoiseSuppressor(true)
        }
        else
        {
            Log.d(TAG, "Enable built-in NS if device supports it")
            WebRtcAudioUtils.setWebRtcBasedNoiseSuppressor(false)
        }

        // Create peer connection factory.
        if (!PeerConnectionFactory.initializeAndroidGlobals(
                context, true, true, peerConnectionParameters!!.videoCodecHwAcceleration))
        {
            events!!.onPeerConnectionError("Failed to initializeAndroidGlobals")
        }
        if (options != null)
        {
            Log.d(TAG, "Factory networkIgnoreMask option: " + options!!.networkIgnoreMask)
        }
        this.context = context
        factory = PeerConnectionFactory(options)
        Log.d(TAG, "Peer connection factory created.")
    }

    private fun createMediaConstraintsInternal() {
        // Create peer connection constraints.
        pcConstraints = MediaConstraints()
        // Enable DTLS for normal calls and disable for loopback calls.
        if (peerConnectionParameters!!.loopback)
        {
            pcConstraints!!.optional.add(
                MediaConstraints.KeyValuePair(DTLS_SRTP_KEY_AGREEMENT_CONSTRAINT, "false"))
        }
        else
        {
            pcConstraints!!.optional.add(
                MediaConstraints.KeyValuePair(DTLS_SRTP_KEY_AGREEMENT_CONSTRAINT, "true"))
        }

        // Check if there is a camera on device and disable video call if not.
        if (videoCapturer == null)
        {
            Log.w(TAG, "No camera on device. Switch to audio only call.")
            isVideoCallEnabled = false
        }
        // Create video constraints if video call is enabled.
        if (isVideoCallEnabled)
        {
            videoWidth = peerConnectionParameters!!.videoWidth
            videoHeight = peerConnectionParameters!!.videoHeight
            videoFps = peerConnectionParameters!!.videoFps

            // If video resolution is not specified, default to HD.
            if (videoWidth == 0 || videoHeight == 0)
            {
                videoWidth = HD_VIDEO_WIDTH
                videoHeight = HD_VIDEO_HEIGHT
            }

            // If fps is not specified, default to 30.
            if (videoFps == 0)
            {
                videoFps = 30
            }
            Logging.d(TAG, "Capturing format: " + videoWidth + "x" + videoHeight + "@" + videoFps)
        }

        // Create audio constraints.
        audioConstraints = MediaConstraints()
        // added for audio performance measurements
        if (peerConnectionParameters!!.noAudioProcessing)
        {
            Log.d(TAG, "Disabling audio processing")
            audioConstraints!!.mandatory.add(
                MediaConstraints.KeyValuePair(AUDIO_ECHO_CANCELLATION_CONSTRAINT, "false"))
            audioConstraints!!.mandatory.add(
                MediaConstraints.KeyValuePair(AUDIO_AUTO_GAIN_CONTROL_CONSTRAINT, "false"))
            audioConstraints!!.mandatory.add(
                MediaConstraints.KeyValuePair(AUDIO_HIGH_PASS_FILTER_CONSTRAINT, "false"))
            audioConstraints!!.mandatory.add(
                MediaConstraints.KeyValuePair(AUDIO_NOISE_SUPPRESSION_CONSTRAINT, "false"))
        }
        if (peerConnectionParameters!!.enableLevelControl)
        {
            Log.d(TAG, "Enabling level control.")
            audioConstraints!!.mandatory.add(
                MediaConstraints.KeyValuePair(AUDIO_LEVEL_CONTROL_CONSTRAINT, "true"))
        }
        // Create SDP constraints.
        sdpMediaConstraints = MediaConstraints()
        sdpMediaConstraints!!.mandatory.add(
            MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        if (isVideoCallEnabled || peerConnectionParameters!!.loopback)
        {
            sdpMediaConstraints!!.mandatory.add(
                MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }
        else
        {
            sdpMediaConstraints!!.mandatory.add(
                MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
    }

    private fun createPeerConnectionInternal(renderEGLContext:EglBase.Context) {
        if (factory == null || isError)
        {
            Log.e(TAG, "Peerconnection factory is not created")
            return
        }
        Log.d(TAG, "Create peer connection.")

        Log.d(TAG, "PCConstraints: " + pcConstraints!!.toString())
        queuedRemoteCandidates = LinkedList<IceCandidate>()

        if (isVideoCallEnabled)
        {
            Log.d(TAG, "EGLContext: $renderEGLContext")
            factory!!.setVideoHwAccelerationOptions(renderEGLContext, renderEGLContext)
        }

        val rtcConfig = PeerConnection.RTCConfiguration(signalingParameters!!.iceServers)
        // TCP candidates are only useful when connecting to a server that supports
        // ICE-TCP.
        rtcConfig.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
        rtcConfig.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
        rtcConfig.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        // Use ECDSA encryption.
        rtcConfig.keyType = PeerConnection.KeyType.ECDSA

        peerConnection = factory!!.createPeerConnection(rtcConfig, pcConstraints, pcObserver)

        if (dataChannelEnabled)
        {
            val init = DataChannel.Init()
            init.ordered = peerConnectionParameters!!.dataChannelParameters!!.ordered
            init.negotiated = peerConnectionParameters!!.dataChannelParameters!!.negotiated
            init.maxRetransmits = peerConnectionParameters!!.dataChannelParameters!!.maxRetransmits
            init.maxRetransmitTimeMs = peerConnectionParameters!!.dataChannelParameters!!.maxRetransmitTimeMs
            init.id = peerConnectionParameters!!.dataChannelParameters!!.id
            init.protocol = peerConnectionParameters!!.dataChannelParameters!!.protocol
            dataChannel = peerConnection!!.createDataChannel("ApprtcDemo data", init)
        }
        isInitiator = false

        // Set default WebRTC tracing and INFO libjingle logging.
        // NOTE: this _must_ happen while |factory| is alive!
        Logging.enableTracing("logcat:", EnumSet.of(Logging.TraceLevel.TRACE_DEFAULT))
        Logging.enableLogToDebugOutput(Logging.Severity.LS_INFO)

        mediaStream = factory!!.createLocalMediaStream("ARDAMS")
        if (isVideoCallEnabled)
        {
            mediaStream!!.addTrack(createVideoTrack(videoCapturer))
        }

        mediaStream!!.addTrack(createAudioTrack())
        peerConnection!!.addStream(mediaStream)
        if (isVideoCallEnabled)
        {
            findVideoSender()
        }

        if (peerConnectionParameters!!.aecDump)
        {
            try
            {
                aecDumpFileDescriptor = ParcelFileDescriptor.open(
                    File(
                        Environment.getExternalStorageDirectory().path
                                + File.separator + "Download/audio.aecdump"
                    ),
                    ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE
                            or ParcelFileDescriptor.MODE_TRUNCATE
                )
                factory!!.startAecDump(aecDumpFileDescriptor!!.fd, -1)
            }
            catch (e: IOException) {
                Log.e(TAG, "Can not open aecdump file", e)
            }

        }

        Log.d(TAG, "Peer connection created.")
    }

    private fun closeInternal() {
        if (factory != null && peerConnectionParameters!!.aecDump)
        {
            factory!!.stopAecDump()
        }
        Log.d(TAG, "Closing peer connection.")
        statsTimer!!.cancel()
        if (dataChannel != null)
        {
            dataChannel!!.dispose()
            dataChannel = null
        }
        if (peerConnection != null)
        {
            peerConnection!!.dispose()
            peerConnection = null
        }
        Log.d(TAG, "Closing audio source.")
        if (audioSource != null)
        {
            audioSource!!.dispose()
            audioSource = null
        }
        Log.d(TAG, "Stopping capture.")
        if (videoCapturer != null)
        {
            try
            {
                videoCapturer!!.stopCapture()
            }
            catch (e:InterruptedException) {
                throw RuntimeException(e)
            }

            videoCapturerStopped = true
            videoCapturer!!.dispose()
            videoCapturer = null
        }
        Log.d(TAG, "Closing video source.")
        if (videoSource != null)
        {
            videoSource!!.dispose()
            videoSource = null
        }
        Log.d(TAG, "Closing peer connection factory.")
        if (factory != null)
        {
            factory!!.dispose()
            factory = null
        }
        options = null
        Log.d(TAG, "Closing peer connection done.")
        events!!.onPeerConnectionClosed()
        PeerConnectionFactory.stopInternalTracingCapture()
        PeerConnectionFactory.shutdownInternalTracer()
    }

    private fun getStats() {
        if (peerConnection == null || isError)
        {
            return
        }
        val success = peerConnection!!.getStats({ reports-> events!!.onPeerConnectionStatsReady(reports) }, null)
        if (!success)
        {
            Log.e(TAG, "getStats() returns false!")
        }
    }

    fun enableStatsEvents(enable:Boolean, periodMs:Int) {
        if (enable)
        {
            try
            {
                statsTimer!!.schedule(object: TimerTask() {
                    override fun run() {
                        executor.execute { getStats() }
                    }
                }, 0, periodMs.toLong())
            }
            catch (e:Exception) {
                Log.e(TAG, "Can not schedule statistics timer", e)
            }

        }
        else
        {
            statsTimer!!.cancel()
        }
    }

    fun setAudioEnabled(enable:Boolean) {
        executor.execute { enableAudio = enable
            if (localAudioTrack != null) {
                localAudioTrack!!.setEnabled(enableAudio)
            } }
    }

    fun setVideoEnabled(enable:Boolean) {
        executor.execute {
            renderVideo = enable
            if (localVideoTrack != null) {
                localVideoTrack!!.setEnabled(renderVideo)
            }
            if (remoteVideoTrack != null) {
                remoteVideoTrack!!.setEnabled(renderVideo)
            }
        }
    }

    fun createOffer() {
        executor.execute {
            if (peerConnection != null && !isError) {
                Log.d(TAG, "PC Create OFFER")
                isInitiator = true
                peerConnection!!.createOffer(sdpObserver, sdpMediaConstraints)
            }
        }
    }

    fun createAnswer() {
        executor.execute {
            if (peerConnection != null && !isError) {
                Log.d(TAG, "PC create ANSWER")
                isInitiator = false
                peerConnection!!.createAnswer(sdpObserver, sdpMediaConstraints)
            }
        }
    }

    fun addRemoteIceCandidate(candidate:IceCandidate) {
        executor.execute {
            if (peerConnection != null && !isError) {
                if (queuedRemoteCandidates != null) {
                    queuedRemoteCandidates!!.add(candidate)
                } else {
                    peerConnection!!.addIceCandidate(candidate)
                }
            }
        }
    }

    fun removeRemoteIceCandidates(candidates:Array<IceCandidate>) {
        executor.execute(Runnable {
            if (peerConnection == null || isError) {
                return@Runnable
            }
            // Drain the queued remote candidates if there is any so that
            // they are processed in the proper order.
            drainCandidates()
            peerConnection!!.removeIceCandidates(candidates)
        })
    }

    fun setRemoteDescription(sdp:SessionDescription?) {
        executor.execute(Runnable {
            if (peerConnection == null || isError) {
                return@Runnable
            }
            var sdpDescription = sdp?.description
            if (preferIsac) {
                sdpDescription = preferCodec(sdpDescription!!, AUDIO_CODEC_ISAC, true)
            }
            if (isVideoCallEnabled) {
                sdpDescription = preferCodec(sdpDescription!!, preferredVideoCodec, false)
            }
            if (peerConnectionParameters!!.audioStartBitrate > 0) {
                sdpDescription = setStartBitrate(
                    AUDIO_CODEC_OPUS, false, sdpDescription!!, peerConnectionParameters!!.audioStartBitrate)
            }
            Log.d(TAG, "Set remote SDP.")
            val sdpRemote = SessionDescription(sdp?.type, sdpDescription)
            peerConnection!!.setRemoteDescription(sdpObserver, sdpRemote)
        })
    }

    fun stopVideoSource() {
        executor.execute { if (videoCapturer != null && !videoCapturerStopped) {
            Log.d(TAG, "Stop video source.")
            try {
                videoCapturer!!.stopCapture()
            } catch (e:InterruptedException) {}

            videoCapturerStopped = true
        } }
    }

    fun startVideoSource() {
        executor.execute { if (videoCapturer != null && videoCapturerStopped) {
            Log.d(TAG, "Restart video source.")
            videoCapturer!!.startCapture(videoWidth, videoHeight, videoFps)
            videoCapturerStopped = false
        } }
    }

    fun setVideoMaxBitrate(maxBitrateKbps:Int?) {
        executor.execute(Runnable {
            if (peerConnection == null || localVideoSender == null || isError) {
                return@Runnable
            }
            Log.d(TAG, "Requested max video bitrate: " + maxBitrateKbps!!)
            if (localVideoSender == null) {
                Log.w(TAG, "Sender is not ready.")
                return@Runnable
            }

            val parameters = localVideoSender!!.parameters
            if (parameters.encodings.size === 0) {
                Log.w(TAG, "RtpParameters are not ready.")
                return@Runnable
            }

            for (encoding in parameters.encodings) {
                // Null value means no limit.
                encoding.maxBitrateBps = if (maxBitrateKbps == null) null else maxBitrateKbps * BPS_IN_KBPS
            }
            if (!localVideoSender!!.setParameters(parameters)) {
                Log.e(TAG, "RtpSender.setParameters failed.")
            }
            Log.d(TAG, "Configured max video bitrate to: " + maxBitrateKbps)
        })
    }

    private fun reportError(errorMessage:String) {
        Log.e(TAG, "Peerconnection error: $errorMessage")
        executor.execute { if (!isError) {
            events!!.onPeerConnectionError(errorMessage)
            isError = true
        } }
    }

    private fun createAudioTrack():AudioTrack {
        audioSource = factory!!.createAudioSource(audioConstraints)
        localAudioTrack = factory!!.createAudioTrack(AUDIO_TRACK_ID, audioSource)
        localAudioTrack!!.setEnabled(enableAudio)
        return localAudioTrack as AudioTrack
    }

    private fun createVideoTrack(capturer:VideoCapturer?):VideoTrack {
        videoSource = factory!!.createVideoSource(capturer)
        capturer!!.startCapture(videoWidth, videoHeight, videoFps)

        localVideoTrack = factory!!.createVideoTrack(VIDEO_TRACK_ID, videoSource)
        localVideoTrack!!.setEnabled(renderVideo)
        localVideoTrack!!.addRenderer(VideoRenderer(localRender))
        return localVideoTrack  as VideoTrack
    }

    private fun findVideoSender() {
        for (sender in peerConnection!!.senders)
        {
            if (sender.track() != null)
            {
                val trackType = sender.track().kind()
                if (trackType == VIDEO_TRACK_TYPE)
                {
                    Log.d(TAG, "Found video sender.")
                    localVideoSender = sender
                }
            }
        }
    }

    private fun drainCandidates() {
        if (queuedRemoteCandidates != null)
        {
            Log.d(TAG, "Add " + queuedRemoteCandidates!!.size + " remote candidates")
            for (candidate in queuedRemoteCandidates!!)
            {
                peerConnection!!.addIceCandidate(candidate)
            }
            queuedRemoteCandidates = null
        }
    }

    private fun switchCameraInternal() {
        if (videoCapturer is CameraVideoCapturer)
        {
            if (!isVideoCallEnabled || isError || videoCapturer == null)
            {
                Log.e(TAG, "Failed to switch camera. Video: $isVideoCallEnabled. Error : $isError")
                return  // No video is sent or only one camera is available or error happened.
            }
            Log.d(TAG, "Switch camera")
            val cameraVideoCapturer = videoCapturer as CameraVideoCapturer?
            cameraVideoCapturer!!.switchCamera(null)
        }
        else
        {
            Log.d(TAG, "Will not switch camera, video caputurer is not a camera")
        }
    }

    fun switchCamera() {
        executor.execute { switchCameraInternal() }
    }

    fun changeCaptureFormat(width:Int, height:Int, framerate:Int) {
        executor.execute { changeCaptureFormatInternal(width, height, framerate) }
    }

    private fun changeCaptureFormatInternal(width:Int, height:Int, framerate:Int) {
        if (!isVideoCallEnabled || isError || videoCapturer == null)
        {
            Log.e(TAG,
                "Failed to change capture format. Video: $isVideoCallEnabled. Error : $isError"
            )
            return
        }
        Log.d(TAG, "changeCaptureFormat: " + width + "x" + height + "@" + framerate)
        videoSource!!.adaptOutputFormat(width, height, framerate)
    }

    // Implementation detail: observe ICE & stream changes and react accordingly.
    private inner class PCObserver:PeerConnection.Observer {
        override fun onIceCandidate(candidate:IceCandidate) {
            executor.execute { events!!.onIceCandidate(candidate) }
        }

        override fun onIceCandidatesRemoved(candidates:Array<IceCandidate>) {
            executor.execute { events!!.onIceCandidatesRemoved(candidates) }
        }

        override fun onSignalingChange(newState:PeerConnection.SignalingState) {
            Log.d(TAG, "SignalingState: $newState")
        }

        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
            executor.execute {
                Log.d(TAG, "IceConnectionState: $newState")
                if (newState === PeerConnection.IceConnectionState.CONNECTED) {
                    events!!.onIceConnected()
                } else if (newState === PeerConnection.IceConnectionState.DISCONNECTED) {
                    events!!.onIceDisconnected()
                } else if (newState === PeerConnection.IceConnectionState.FAILED) {
                    reportError("ICE connection failed.")
                }
            }
        }

        override fun onIceGatheringChange(newState:PeerConnection.IceGatheringState) {
            Log.d(TAG, "IceGatheringState: $newState")
        }

        override  fun onIceConnectionReceivingChange(receiving:Boolean) {
            Log.d(TAG, "IceConnectionReceiving changed to $receiving")
        }

        override fun onAddStream(stream:MediaStream) {
            executor.execute(Runnable {
                if (peerConnection == null || isError) {
                    return@Runnable
                }
                if (stream.audioTracks.size > 1 || stream.videoTracks.size > 1) {
                    reportError("Weird-looking stream: $stream")
                    return@Runnable
                }
                if (stream.videoTracks.size === 1) {
                    remoteVideoTrack = stream.videoTracks.get(0)
                    remoteVideoTrack!!.setEnabled(renderVideo)
                    for (remoteRender in remoteRenders!!) {
                        remoteVideoTrack!!.addRenderer(VideoRenderer(remoteRender))
                    }
                }
            })
        }

        override  fun onRemoveStream(stream:MediaStream) {
            executor.execute { remoteVideoTrack = null }
        }

        override fun onDataChannel(dc:DataChannel) {
            Log.d(TAG, "New Data channel " + dc.label())

            if (!dataChannelEnabled)
                return

            dc.registerObserver(object:DataChannel.Observer {
                override fun onBufferedAmountChange(previousAmount:Long) {
                    Log.d(TAG, "Data channel buffered amount changed: " + dc.label() + ": " + dc.state())
                }

                override  fun onStateChange() {
                    Log.d(TAG, "Data channel state changed: " + dc.label() + ": " + dc.state())
                }

                override fun onMessage(buffer:DataChannel.Buffer) {
                    if (buffer.binary)
                    {
                        Log.d(TAG, "Received binary msg over $dc")
                        return
                    }
                    val data = buffer.data
                    val bytes = ByteArray(data.capacity())
                    data.get(bytes)
                    val strData = String(bytes)
                    Log.d(TAG, "Got msg: $strData over $dc")
                }
            })
        }

        override  fun onRenegotiationNeeded() {
            // No need to do anything; AppRTC follows a pre-agreed-upon
            // signaling/negotiation protocol.
        }
    }

    // Implementation detail: handle offer creation/signaling and answer setting,
    // as well as adding remote ICE candidates once the answer SDP is set.
    private inner class SDPObserver:SdpObserver {
        override fun onCreateSuccess(origSdp:SessionDescription) {
            if (localSdp != null)
            {
                reportError("Multiple SDP create.")
                return
            }
            var sdpDescription = origSdp.description
            if (preferIsac)
            {
                sdpDescription = preferCodec(sdpDescription, AUDIO_CODEC_ISAC, true)
            }
            if (isVideoCallEnabled)
            {
                sdpDescription = preferCodec(sdpDescription, preferredVideoCodec, false)
            }
            val sdp = SessionDescription(origSdp.type, sdpDescription)
            localSdp = sdp
            executor.execute {
                if (peerConnection != null && !isError) {
                    Log.d(TAG, "Set local SDP from " + sdp.type)
                    peerConnection!!.setLocalDescription(sdpObserver, sdp)
                }
            }
        }

        override  fun onSetSuccess() {
            executor.execute(Runnable {
                if (peerConnection == null || isError) {
                    return@Runnable
                }
                if (isInitiator) {
                    // For offering peer connection we first create offer and set
                    // local SDP, then after receiving answer set remote SDP.
                    if (peerConnection!!.remoteDescription == null) {
                        // We've just set our local SDP so time to send it.
                        Log.d(TAG, "Local SDP set succesfully")
                        events!!.onLocalDescription(localSdp)
                    } else {
                        // We've just set remote description, so drain remote
                        // and send local ICE candidates.
                        Log.d(TAG, "Remote SDP set succesfully")
                        drainCandidates()
                    }
                } else {
                    // For answering peer connection we set remote SDP and then
                    // create answer and set local SDP.
                    if (peerConnection!!.localDescription != null) {
                        // We've just set our local SDP so time to send it, drain
                        // remote and send local ICE candidates.
                        Log.d(TAG, "Local SDP set succesfully")
                        events!!.onLocalDescription(localSdp)
                        drainCandidates()
                    } else {
                        // We've just set remote SDP - do nothing for now -
                        // answer will be created soon.
                        Log.d(TAG, "Remote SDP set succesfully")
                    }
                }
            })
        }

        override fun onCreateFailure(error:String) {
            reportError("createSDP error: $error")
        }

        override fun onSetFailure(error:String) {
            reportError("setSDP error: $error")
        }
    }

    companion object {
        private const val VIDEO_TRACK_ID = "ARDAMSv0"
        private const val AUDIO_TRACK_ID = "ARDAMSa0"
        private const val VIDEO_TRACK_TYPE = "video"
        private const val TAG = "PCRTCClient"
        private const val VIDEO_CODEC_VP8 = "VP8"
        private const val VIDEO_CODEC_VP9 = "VP9"
        private const val VIDEO_CODEC_H264 = "H264"
        private const val AUDIO_CODEC_OPUS = "opus"
        private const val AUDIO_CODEC_ISAC = "ISAC"
        private const val VIDEO_CODEC_PARAM_START_BITRATE = "x-google-start-bitrate"
        private const val VIDEO_FLEXFEC_FIELDTRIAL = "WebRTC-FlexFEC-03/Enabled/"
        private const val AUDIO_CODEC_PARAM_BITRATE = "maxaveragebitrate"
        private const val AUDIO_ECHO_CANCELLATION_CONSTRAINT = "googEchoCancellation"
        private const val AUDIO_AUTO_GAIN_CONTROL_CONSTRAINT = "googAutoGainControl"
        private const val AUDIO_HIGH_PASS_FILTER_CONSTRAINT = "googHighpassFilter"
        private const val AUDIO_NOISE_SUPPRESSION_CONSTRAINT = "googNoiseSuppression"
        private const val AUDIO_LEVEL_CONTROL_CONSTRAINT = "levelControl"
        private const val DTLS_SRTP_KEY_AGREEMENT_CONSTRAINT = "DtlsSrtpKeyAgreement"
        private const val HD_VIDEO_WIDTH = 1280
        private const val HD_VIDEO_HEIGHT = 720
        private const val BPS_IN_KBPS = 1000

        private fun setStartBitrate(
            codec:String, isVideoCodec:Boolean, sdpDescription:String, bitrateKbps:Int):String {
            val lines = sdpDescription.split("\r\n".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            var rtpmapLineIndex = -1
            var sdpFormatUpdated = false
            var codecRtpMap:String? = null
            // Search for codec rtpmap in format
            // a=rtpmap:<payload type> <encoding name>/<clock rate> [/<encoding parameters>]
            var regex = "^a=rtpmap:(\\d+) $codec(/\\d+)+[\r]?$"
            var codecPattern = Pattern.compile(regex)
            for (i in lines.indices)
            {
                val codecMatcher = codecPattern.matcher(lines[i])
                if (codecMatcher.matches())
                {
                    codecRtpMap = codecMatcher.group(1)
                    rtpmapLineIndex = i
                    break
                }
            }
            if (codecRtpMap == null)
            {
                Log.w(TAG, "No rtpmap for $codec codec")
                return sdpDescription
            }
            Log.d(TAG, "Found " + codec + " rtpmap " + codecRtpMap + " at " + lines[rtpmapLineIndex])

            // Check if a=fmtp string already exist in remote SDP for this codec and
            // update it with new bitrate parameter.
            regex = "^a=fmtp:$codecRtpMap \\w+=\\d+.*[\r]?$"
            codecPattern = Pattern.compile(regex)
            for (i in lines.indices)
            {
                val codecMatcher = codecPattern.matcher(lines[i])
                if (codecMatcher.matches())
                {
                    Log.d(TAG, "Found " + codec + " " + lines[i])
                    if (isVideoCodec)
                    {
                        lines[i] += "; $VIDEO_CODEC_PARAM_START_BITRATE=$bitrateKbps"
                    }
                    else
                    {
                        lines[i] += "; " + AUDIO_CODEC_PARAM_BITRATE + "=" + bitrateKbps * 1000
                    }
                    Log.d(TAG, "Update remote SDP line: " + lines[i])
                    sdpFormatUpdated = true
                    break
                }
            }

            val newSdpDescription = StringBuilder()
            for (i in lines.indices)
            {
                newSdpDescription.append(lines[i]).append("\r\n")
                // Append new a=fmtp line if no such line exist for a codec.
                if (!sdpFormatUpdated && i == rtpmapLineIndex)
                {
                    val bitrateSet:String
                    if (isVideoCodec)
                    {
                        bitrateSet = "a=fmtp:$codecRtpMap $VIDEO_CODEC_PARAM_START_BITRATE=$bitrateKbps"
                    }
                    else
                    {
                        bitrateSet = ("a=fmtp:" + codecRtpMap + " " + AUDIO_CODEC_PARAM_BITRATE + "="
                                + bitrateKbps * 1000)
                    }
                    Log.d(TAG, "Add remote SDP line: $bitrateSet")
                    newSdpDescription.append(bitrateSet).append("\r\n")
                }
            }
            return newSdpDescription.toString()
        }

        private fun preferCodec(sdpDescription:String, codec:String?, isAudio:Boolean):String {
            val lines = sdpDescription.split("\r\n".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            var mLineIndex = -1
            var codecRtpMap:String? = null
            // a=rtpmap:<payload type> <encoding name>/<clock rate> [/<encoding parameters>]
            val regex = "^a=rtpmap:(\\d+) $codec(/\\d+)+[\r]?$"
            val codecPattern = Pattern.compile(regex)
            var mediaDescription = "m=video "
            if (isAudio)
            {
                mediaDescription = "m=audio "
            }
            var i = 0
            while (i < lines.size && (mLineIndex == -1 || codecRtpMap == null))
            {
                if (lines[i].startsWith(mediaDescription))
                {
                    mLineIndex = i
                    i++
                    continue
                }
                val codecMatcher = codecPattern.matcher(lines[i])
                if (codecMatcher.matches())
                {
                    codecRtpMap = codecMatcher.group(1)
                }
                i++
            }
            if (mLineIndex == -1)
            {
                Log.w(TAG, "No $mediaDescription line, so can't prefer $codec")
                return sdpDescription
            }
            if (codecRtpMap == null)
            {
                Log.w(TAG, "No rtpmap for " + codec!!)
                return sdpDescription
            }
            Log.d(TAG, "Found " + codec + " rtpmap " + codecRtpMap + ", prefer at " + lines[mLineIndex])
            val origMLineParts = lines[mLineIndex].split(" ".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            if (origMLineParts.size > 3)
            {
                val newMLine = StringBuilder()
                var origPartIndex = 0
                // Format is: m=<media> <port> <proto> <fmt> ...
                newMLine.append(origMLineParts[origPartIndex++]).append(" ")
                newMLine.append(origMLineParts[origPartIndex++]).append(" ")
                newMLine.append(origMLineParts[origPartIndex++]).append(" ")
                newMLine.append(codecRtpMap)
                while (origPartIndex < origMLineParts.size)
                {
                    if (origMLineParts[origPartIndex] != codecRtpMap)
                    {
                        newMLine.append(" ").append(origMLineParts[origPartIndex])
                    }
                    origPartIndex++
                }
                lines[mLineIndex] = newMLine.toString()
                Log.d(TAG, "Change media description: " + lines[mLineIndex])
            }
            else
            {
                Log.e(TAG, "Wrong SDP media description format: " + lines[mLineIndex])
            }
            val newSdpDescription = StringBuilder()
            for (line in lines)
            {
                newSdpDescription.append(line).append("\r\n")
            }
            return newSdpDescription.toString()
        }
    }
}