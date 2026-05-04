// app/src/main/java/com/von/tracer/server/TraceServer.kt

package com.von.tracer.server

import android.util.Log
import com.von.tracer.model.NodeType
import com.von.tracer.model.TraceNode
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket

class TraceServer(
    private val port: Int = 27043,
    private val onNodeReceived: (TraceNode) -> Unit,
    private val onServerLog: (String) -> Unit = {}
) {

    companion object {
        private const val TAG = "TraceServer"
        private const val MAX_CONNECTIONS = 20
        private const val SOCKET_TIMEOUT_MS = 5000
    }

    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val parser = TraceParser()

    var isRunning = false
        private set

    // ──────────────────────────────────────────────
    // LIFECYCLE
    // ──────────────────────────────────────────────

    fun start() {
        if (isRunning) {
            log("Server sudah jalan di port $port")
            return
        }

        scope.launch {
            try {
                serverSocket = ServerSocket(port)
                isRunning = true
                log("Server listening di port $port")

                var connectionCount = 0

                while (isRunning) {
                    try {
                        val client = serverSocket!!.accept()
                        client.soTimeout = SOCKET_TIMEOUT_MS

                        if (connectionCount >= MAX_CONNECTIONS) {
                            log("Max connections reached, drop client")
                            client.close()
                            continue
                        }

                        connectionCount++
                        launch {
                            handleClient(client)
                            connectionCount--
                        }

                    } catch (e: Exception) {
                        if (isRunning) log("Accept error: ${e.message}")
                    }
                }

            } catch (e: Exception) {
                log("Server error: ${e.message}")
                isRunning = false
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
            serverSocket = null
            scope.coroutineContext.cancelChildren()
            log("Server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Stop error", e)
        }
    }

    // ──────────────────────────────────────────────
    // CLIENT HANDLER
    // ──────────────────────────────────────────────

    private suspend fun handleClient(socket: Socket) {
        val clientAddr = socket.inetAddress.hostAddress
        Log.d(TAG, "Client connected: $clientAddr")

        try {
            val reader = BufferedReader(
                InputStreamReader(socket.getInputStream(), Charsets.UTF_8)
            )

            // Satu koneksi bisa kirim multiple trace lines
            var line = reader.readLine()
            while (line != null && line.isNotBlank()) {
                processLine(line)
                line = reader.readLine()
            }

        } catch (e: Exception) {
            Log.d(TAG, "Client handler error: ${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    private suspend fun processLine(json: String) {
        try {
            val node = parser.parse(json)
            if (node != null) {
                withContext(Dispatchers.Main) {
                    onNodeReceived(node)
                }
                Log.d(TAG, "Node: ${node.type} | ${node.label} | ${node.offset}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}\nJSON: $json")
        }
    }

    private fun log(msg: String) {
        Log.d(TAG, msg)
        onServerLog(msg)
    }
}