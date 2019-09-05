package com.aqil.webrtc.web_rtc

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.aqil.webrtc.interfeces.AppRTCClient
import com.aqil.webrtc.utils.AsyncHttpURLConnection
import com.aqil.webrtc.utils.AsyncHttpURLConnection.AsyncHttpEvents
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

/**
 * Negotiates signaling for chatting with https://appr.tc "rooms".
 * Uses the client<->server specifics of the apprtc AppEngine webapp.
 *
 *
 *
 * To use: create an instance of this object (registering a message handler) and
 * call connectToRoom().  Once room connection is established
 * onConnectedToRoom() callback with room parameters is invoked.
 * Messages to other party (with local Ice candidates and answer SDP) can
 * be sent after WebSocket connection is established.
 */
 class WebSocketRTCClient(private val events: AppRTCClient.SignalingEvents) : AppRTCClient,
    WebSocketChannelClient.WebSocketChannelEvents {

    private val handler: Handler
    private var initiator: Boolean = false
    private var wsClient: WebSocketChannelClient? = null
    private var roomState: ConnectionState? = null
    private var connectionParameters: AppRTCClient.RoomConnectionParameters? = null
    private var messageUrl: String? = null
    private var leaveUrl: String? = null

    private enum class ConnectionState {
        NEW, CONNECTED, CLOSED, ERROR
    }

    private enum class MessageType {
        MESSAGE, LEAVE
    }

    init {
        roomState = ConnectionState.NEW
        val handlerThread = HandlerThread(TAG)
        handlerThread.start()
        handler = Handler(handlerThread.looper)
    }

    // --------------------------------------------------------------------
    // AppRTCClient interface implementation.
    // Asynchronously connect to an AppRTC room URL using supplied connection
    // parameters, retrieves room parameters and connect to WebSocket server.
    override   fun connectToRoom(connectionParameters: AppRTCClient.RoomConnectionParameters) {
        this.connectionParameters = connectionParameters
        handler.post { this.connectToRoomInternal() }
    }

    override  fun disconnectFromRoom() {
        handler.post {
            disconnectFromRoomInternal()
            handler.looper.quit()
        }
    }

    // Connects to room - function runs on a local looper thread.
    private fun connectToRoomInternal() {
        val connectionUrl = getConnectionUrl(connectionParameters!!)
        Log.d(TAG, "Connect to room: $connectionUrl")
        roomState = ConnectionState.NEW
        wsClient = WebSocketChannelClient(handler, this)

        val callbacks = object : RoomParametersFetcher.RoomParametersFetcherEvents {
            override    fun onSignalingParametersReady(params: AppRTCClient.SignalingParameters) {
                this@WebSocketRTCClient.handler.post { this@WebSocketRTCClient.signalingParametersReady(params) }
            }

            override fun onSignalingParametersError(description: String) {
                Log.v("onChannelError",description)
                this@WebSocketRTCClient.reportError(description)
            }
        }

        RoomParametersFetcher(connectionUrl, null.toString(), callbacks).makeRequest()
    }

    // Disconnect from room and send bye messages - runs on a local looper thread.
    private fun disconnectFromRoomInternal() {
        Log.d(TAG, "Disconnect. Room state: " + roomState!!)
        if (roomState == ConnectionState.CONNECTED) {
            Log.d(TAG, "Closing room.")
            sendPostMessage(MessageType.LEAVE, leaveUrl, null)
        }
        roomState = ConnectionState.CLOSED
        if (wsClient != null) {
            wsClient!!.disconnect(true)
        }
    }

    // Helper functions to get connection, post message and leave message URLs
    private fun getConnectionUrl(connectionParameters: AppRTCClient.RoomConnectionParameters): String {
        return connectionParameters.roomUrl + "/" + ROOM_JOIN + "/" + connectionParameters.roomId
    }

    private fun getMessageUrl(
        connectionParameters: AppRTCClient.RoomConnectionParameters, signalingParameters: AppRTCClient.SignalingParameters
    ): String {
        return (connectionParameters.roomUrl + "/" + ROOM_MESSAGE + "/" + connectionParameters.roomId
                + "/" + signalingParameters.clientId)
    }

    private fun getLeaveUrl(
        connectionParameters: AppRTCClient.RoomConnectionParameters, signalingParameters: AppRTCClient.SignalingParameters
    ): String {
        return (connectionParameters.roomUrl + "/" + ROOM_LEAVE + "/" + connectionParameters.roomId + "/"
                + signalingParameters.clientId)
    }

    // Callback issued when room parameters are extracted. Runs on local
    // looper thread.
    private fun signalingParametersReady(signalingParameters: AppRTCClient.SignalingParameters) {
        Log.d(TAG, "Room connection completed.")
        if (connectionParameters!!.loopback && (!signalingParameters.initiator || signalingParameters.offerSdp != null)) {
            reportError("Loopback room is busy.")
            return
        }
        if (!connectionParameters!!.loopback && !signalingParameters.initiator
            && signalingParameters.offerSdp == null
        ) {
            Log.w(TAG, "No offer SDP in room response.")
        }
        initiator = signalingParameters.initiator
        messageUrl = getMessageUrl(connectionParameters!!, signalingParameters)
        leaveUrl = getLeaveUrl(connectionParameters!!, signalingParameters)
        Log.d(TAG, "Message URL: " + messageUrl!!)
        Log.d(TAG, "Leave URL: " + leaveUrl!!)
        roomState = ConnectionState.CONNECTED

        // Fire connection and signaling parameters events.
        events.onConnectedToRoom(signalingParameters)

        // Connect and register WebSocket client.
        wsClient!!.connect(signalingParameters.wssUrl, signalingParameters.wssPostUrl)
        wsClient!!.register(connectionParameters!!.roomId, signalingParameters.clientId)
    }

    // Send local offer SDP to the other participant.
    override fun sendOfferSdp(sdp: SessionDescription) {
        handler.post {
            if (roomState != ConnectionState.CONNECTED) {
                reportError("Sending offer SDP in non connected state.")
                return@post
            }
            val json = JSONObject()
            jsonPut(json, "sdp", sdp.description)
            jsonPut(json, "type", "offer")
            sendPostMessage(MessageType.MESSAGE, messageUrl, json.toString())
            if (connectionParameters!!.loopback) {
                // In loopback mode rename this offer to answer and route it back.
                val sdpAnswer = SessionDescription(
                    SessionDescription.Type.fromCanonicalForm("answer"), sdp.description
                )
                events.onRemoteDescription(sdpAnswer)
            }
        }
    }

    // Send local answer SDP to the other participant.
    override fun sendAnswerSdp(sdp: SessionDescription) {
        handler.post {
            if (connectionParameters!!.loopback) {
                Log.e(TAG, "Sending answer in loopback mode.")
                return@post
            }
            val json = JSONObject()
            jsonPut(json, "sdp", sdp.description)
            jsonPut(json, "type", "answer")
            wsClient!!.send(json.toString())
        }
    }

    // Send Ice candidate to the other participant.
    override  fun sendLocalIceCandidate(candidate: IceCandidate) {
        handler.post {
            val json = JSONObject()
            jsonPut(json, "type", "candidate")
            jsonPut(json, "label", candidate.sdpMLineIndex)
            jsonPut(json, "id", candidate.sdpMid)
            jsonPut(json, "candidate", candidate.sdp)
            if (initiator) {
                // Call initiator sends ice candidates to GAE server.
                if (roomState != ConnectionState.CONNECTED) {
                    reportError("Sending ICE candidate in non connected state.")
                    return@post
                }
                sendPostMessage(MessageType.MESSAGE, messageUrl, json.toString())
                if (connectionParameters!!.loopback) {
                    events.onRemoteIceCandidate(candidate)
                }
            } else {
                // Call receiver sends ice candidates to websocket server.
                wsClient!!.send(json.toString())
            }
        }
    }

    // Send removed Ice candidates to the other participant.
    override  fun sendLocalIceCandidateRemovals(candidates: Array<IceCandidate>) {
        handler.post {
            val json = JSONObject()
            jsonPut(json, "type", "remove-candidates")
            val jsonArray = JSONArray()
            for (candidate in candidates) {
                jsonArray.put(toJsonCandidate(candidate!!))
            }
            jsonPut(json, "candidates", jsonArray)
            if (initiator) {
                // Call initiator sends ice candidates to GAE server.
                if (roomState != ConnectionState.CONNECTED) {
                    reportError("Sending ICE candidate removals in non connected state.")
                    return@post
                }
                sendPostMessage(MessageType.MESSAGE, messageUrl, json.toString())
                if (connectionParameters!!.loopback) {
                    events.onRemoteIceCandidatesRemoved(candidates)
                }
            } else {
                // Call receiver sends ice candidates to websocket server.
                wsClient!!.send(json.toString())
            }
        }
    }

    // --------------------------------------------------------------------
    // WebSocketChannelEvents interface implementation.
    // All events are called by WebSocketChannelClient on a local looper thread
    // (passed to WebSocket client constructor).
    override fun onWebSocketMessage(msg: String) {
        if (wsClient!!.state !== WebSocketChannelClient.WebSocketConnectionState.REGISTERED) {
            Log.e(TAG, "Got WebSocket message in non registered state.")
            return
        }
        try {
            var json = JSONObject(msg)
            val msgText = json.getString("msg")
            val errorText = json.optString("error")
            if (msgText.isNotEmpty()) {
                json = JSONObject(msgText)
                val type = json.optString("type")
                if (type == "candidate") {
                    events.onRemoteIceCandidate(toJavaCandidate(json))
                } else if (type == "remove-candidates") {
                    val candidateArray = json.getJSONArray("candidates")
                    val candidates = arrayOfNulls<IceCandidate>(candidateArray.length())
                    for (i in 0 until candidateArray.length()) {
                        candidates[i] = toJavaCandidate(candidateArray.getJSONObject(i))
                    }
                    events.onRemoteIceCandidatesRemoved(candidates as Array<IceCandidate>)
                } else if (type == "answer") {
                    if (initiator) {
                        val sdp = SessionDescription(
                            SessionDescription.Type.fromCanonicalForm(type), json.getString("sdp")
                        )
                        events.onRemoteDescription(sdp)
                    } else {
                        reportError("Received answer for call initiator: $msg")
                    }
                } else if (type == "offer") {
                    if (!initiator) {
                        val sdp = SessionDescription(
                            SessionDescription.Type.fromCanonicalForm(type), json.getString("sdp")
                        )
                        events.onRemoteDescription(sdp)
                    } else {
                        reportError("Received offer for call receiver: $msg")
                    }
                } else if (type == "bye") {
                    events.onChannelClose()
                } else {
                    reportError("Unexpected WebSocket message: $msg")
                }
            } else {
                if (errorText.isNotEmpty()) {
                    reportError("WebSocket error message: $errorText")
                } else {
                    reportError("Unexpected WebSocket message: $msg")
                }
            }
        } catch (e: JSONException) {
            reportError("WebSocket message JSON parsing error: $e")
        }

    }

    override fun onWebSocketClose() {
        events.onChannelClose()
    }

    override fun onWebSocketError(description: String) {
        reportError("WebSocket error: $description")
    }

    // --------------------------------------------------------------------
    // Helper functions.
    private fun reportError(errorMessage: String) {
        Log.e(TAG, errorMessage)
        handler.post {
            if (roomState != ConnectionState.ERROR) {
                roomState = ConnectionState.ERROR
                events.onChannelError(errorMessage)
            }
        }
    }

    // Send SDP or ICE candidate to a room server.
    private fun sendPostMessage(
        messageType: MessageType, url: String?, message: String?
    ) {
        var logInfo = url
        if (message != null) {
            logInfo += ". Message: $message"
        }
        Log.d(TAG, "C->GAE: " + logInfo!!)
        val httpConnection = AsyncHttpURLConnection("POST", url.toString(), message, object : AsyncHttpEvents {
            override   fun onHttpError(errorMessage: String) {
                reportError("GAE POST error: $errorMessage")
            }

            override  fun onHttpComplete(response: String) {
                if (messageType == MessageType.MESSAGE) {
                    try {
                        val roomJson = JSONObject(response)
                        val result = roomJson.getString("result")
                        if (result != "SUCCESS") {
                            reportError("GAE POST error: $result")
                        }
                    } catch (e: JSONException) {
                        reportError("GAE POST JSON error: $e")
                    }

                }
            }
        })
        httpConnection.send()
    }

    // Converts a Java candidate to a JSONObject.
    private fun toJsonCandidate(candidate: IceCandidate): JSONObject {
        val json = JSONObject()
        jsonPut(json, "label", candidate.sdpMLineIndex)
        jsonPut(json, "id", candidate.sdpMid)
        jsonPut(json, "candidate", candidate.sdp)
        return json
    }

    // Converts a JSON candidate to a Java object.
    @Throws(JSONException::class)
    private fun toJavaCandidate(json: JSONObject): IceCandidate {
        return IceCandidate(
            json.getString("id"), json.getInt("label"), json.getString("candidate")
        )
    }

    companion object {
        private const val TAG = "WSRTCClient"
        private const val ROOM_JOIN = "join"
        private const val ROOM_MESSAGE = "message"
        private const val ROOM_LEAVE = "leave"

        // Put a |key|->|value| mapping in |json|.
        private fun jsonPut(json: JSONObject, key: String, value: Any) {
            try {
                json.put(key, value)
            } catch (e: JSONException) {
                throw RuntimeException(e)
            }

        }
    }
}