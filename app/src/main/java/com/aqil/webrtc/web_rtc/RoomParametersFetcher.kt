package com.aqil.webrtc.web_rtc

import android.util.Log
import com.aqil.webrtc.interfeces.AppRTCClient
import com.aqil.webrtc.utils.AsyncHttpURLConnection
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.*


/**
 * AsyncTask that converts an AppRTC room URL into the set of signaling
 * parameters to use with that room.
 */
internal class RoomParametersFetcher(
    private val roomUrl: String, private val roomMessage: String, private val events: RoomParametersFetcherEvents
) {
    private var httpConnection: AsyncHttpURLConnection? = null

    /**
     * Room parameters fetcher callbacks.
     */
    interface RoomParametersFetcherEvents {
        /**
         * Callback fired once the room's signaling parameters
         * SignalingParameters are extracted.
         */
        fun onSignalingParametersReady(params: AppRTCClient.SignalingParameters)

        /**
         * Callback for room parameters extraction error.
         */
        fun onSignalingParametersError(description: String)
    }

    fun makeRequest() {
        Log.d(TAG, "Connecting to room: $roomUrl")
        httpConnection = AsyncHttpURLConnection("POST", roomUrl, roomMessage, object : AsyncHttpURLConnection.AsyncHttpEvents {
            override fun onHttpError(errorMessage: String) {
                Log.e(TAG, "Room connection error: $errorMessage")
                events.onSignalingParametersError(errorMessage)
            }

            override fun onHttpComplete(response: String) {
                roomHttpResponseParse(response)
            }
        })
        httpConnection!!.send()
    }

    private fun roomHttpResponseParse(response: String) {
        var response = response
        Log.d(TAG, "Room response: $response")
        try {
            var iceCandidates: LinkedList<IceCandidate>? = null
            var offerSdp: SessionDescription? = null
            var roomJson = JSONObject(response)

            val result = roomJson.getString("result")

            if (result != "SUCCESS") {
                events.onSignalingParametersError("Room response error: $result")
                return
            }

            response = roomJson.getString("params")
            roomJson = JSONObject(response)
            val roomId = roomJson.getString("room_id")
            val clientId = roomJson.getString("client_id")
            val wssUrl = roomJson.getString("wss_url")
            val wssPostUrl = roomJson.getString("wss_post_url")
            val initiator = roomJson.getBoolean("is_initiator")
            if (!initiator) {
                iceCandidates = LinkedList()
                val messagesString = roomJson.getString("messages")
                val messages = JSONArray(messagesString)
                for (i in 0 until messages.length()) {
                    val messageString = messages.getString(i)
                    val message = JSONObject(messageString)
                    val messageType = message.getString("type")
                    Log.d(TAG, "GAE->C #$i : $messageString")
                    if (messageType == "offer") {
                        offerSdp = SessionDescription(
                            SessionDescription.Type.fromCanonicalForm(messageType), message.getString("sdp")
                        )
                    } else if (messageType == "candidate") {
                        val candidate = IceCandidate(
                            message.getString("id"), message.getInt("label"), message.getString("candidate")
                        )
                        iceCandidates.add(candidate)
                    } else {
                        Log.e(TAG, "Unknown message: $messageString")
                    }
                }
            }


            val iceServers = iceServersFromPCConfigJSON(roomJson.getString("pc_config"))
            var isTurnPresent = false
            for (server in iceServers) {
                Log.d(TAG, "IceServer: $server")
                if (server.uri.startsWith("turn:")) {
                    isTurnPresent = true
                    break
                }
            }
            // Request TURN servers.
            if (!isTurnPresent) {
                val turnServers = requestTurnServers(roomJson.getString("ice_server_url"))
                for (turnServer in turnServers) {
                    Log.d(TAG, "TurnServer: $turnServer")
                    iceServers.add(turnServer)
                }
            }

            Log.d(TAG, "RoomId: $roomId. ClientId: $clientId")
            Log.d(TAG, "iceServers: $iceServers. initiator: $initiator")
            Log.d(TAG, "Initiator: $initiator")
            Log.d(TAG, "WSS url: $wssUrl")
            Log.d(TAG, "WSS POST url: $wssPostUrl")

            val params = AppRTCClient.SignalingParameters(
                iceServers,
                initiator,
                clientId,
                wssUrl,
                wssPostUrl,
                offerSdp,
                iceCandidates)

            events.onSignalingParametersReady(params)
        } catch (e: JSONException) {
            events.onSignalingParametersError("Room JSON parsing error: $e")
        } catch (e: IOException) {
            events.onSignalingParametersError("Room IO error: $e")
        }

    }

    // Requests & returns a TURN ICE Server based on a request URL.  Must be run
    // off the main thread!
    @Throws(IOException::class, JSONException::class)
    private fun requestTurnServers(url: String): LinkedList<PeerConnection.IceServer> {
        val turnServers = LinkedList<PeerConnection.IceServer>()
        Log.d(TAG, "Request TURN from: $url")
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.doOutput = true
        connection.setRequestProperty("REFERER", "https://appr.tc")
        connection.connectTimeout = TURN_HTTP_TIMEOUT_MS
        connection.readTimeout = TURN_HTTP_TIMEOUT_MS
        val responseCode = connection.responseCode
        if (responseCode != 200) {
            throw IOException(
                "Non-200 response when requesting TURN server from " + url + " : "
                        + connection.getHeaderField(null)
            )
        }
        val responseStream = connection.inputStream
        val response = drainStream(responseStream)
        connection.disconnect()
        Log.d(TAG, "TURN response: $response")
        val responseJSON = JSONObject(response)
        val iceServers = responseJSON.getJSONArray("iceServers")
        for (i in 0 until iceServers.length()) {
            val server = iceServers.getJSONObject(i)
            val turnUrls = server.getJSONArray("urls")
            val username = if (server.has("username")) server.getString("username") else ""
            val credential = if (server.has("credential")) server.getString("credential") else ""
            for (j in 0 until turnUrls.length()) {
                val turnUrl = turnUrls.getString(j)
                turnServers.add(PeerConnection.IceServer(turnUrl, username, credential))
            }
        }
        return turnServers
    }

    // Return the list of ICE servers described by a WebRTCPeerConnection
    // configuration string.
    @Throws(JSONException::class)
    private fun iceServersFromPCConfigJSON(pcConfig: String): LinkedList<PeerConnection.IceServer> {
        val json = JSONObject(pcConfig)
        val servers = json.getJSONArray("iceServers")
        val ret = LinkedList<PeerConnection.IceServer>()
        for (i in 0 until servers.length()) {
            val server = servers.getJSONObject(i)
            val url = server.getString("urls")
            val credential = if (server.has("credential")) server.getString("credential") else ""
            ret.add(PeerConnection.IceServer(url, "", credential))
        }
        return ret
    }

    companion object {
        private val TAG = "RoomRTCClient"
        private val TURN_HTTP_TIMEOUT_MS = 5000

        // Return the contents of an InputStream as a String.
        private fun drainStream(`in`: InputStream): String {
            val s = Scanner(`in`).useDelimiter("\\A")
            return if (s.hasNext()) s.next() else ""
        }
    }
}