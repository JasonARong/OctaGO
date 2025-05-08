package com.yason.octago.camera

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI

class LightingWebSocketManager(
    private val serverUrl: String,
    private val onShutterPress: () -> Unit
) {
    private var webSocketClient: WebSocketClient? = null
    private var isManuallyClosed = false
    private val reconnectDelayMillis = 3000L // 3 seconds
    private var reconnectJob: Job? = null

    fun connect() {
        if (webSocketClient != null && webSocketClient!!.isOpen) {
            Log.d("LightingWebSocket", "Already connected")
            return
        }

        isManuallyClosed = false

        val uri = URI(serverUrl)

        webSocketClient = object : WebSocketClient(uri) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                Log.d("LightingWebSocket", "Connected to ESP WebSocket")
                cancelReconnect()
            }

            override fun onMessage(message: String?) {
                Log.d("LightingWebSocket", "Received: $message")
                if (message?.trim() == "SHUTTER_PRESS") {
                    onShutterPress()
                }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                Log.w("LightingWebSocket", "WebSocket closed: $reason")
                if (!isManuallyClosed) {
                    scheduleReconnect()
                }
            }

            override fun onError(ex: Exception?) {
                Log.e("LightingWebSocket", "WebSocket error: ${ex?.message}")
                if (!isManuallyClosed) {
                    scheduleReconnect()
                }
            }
        }

        webSocketClient?.connect()
    }

    fun disconnect() {
        isManuallyClosed = true
        cancelReconnect()
        webSocketClient?.close()
        webSocketClient = null
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return

        reconnectJob = CoroutineScope(Dispatchers.IO).launch {
            delay(reconnectDelayMillis)
            Log.d("LightingWebSocket", "Trying to reconnect...")
            connect()
        }
    }

    private fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
    }
}