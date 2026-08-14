package com.tesla.autostreamer.server

import android.content.Context
import android.util.Log
import com.tesla.autostreamer.input.TeslaTouchEvent
import com.tesla.autostreamer.input.TouchInjector
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Embedded HTTP & WebSocket Server
 * 
 * Serves the Tesla Web Client directly from Android APK assets
 * and streams H.264 video chunks to connected Tesla browsers.
 */
class LocalHttpServer(
    private val context: Context,
    val port: Int = 8080,
    private val touchInjector: TouchInjector
) {
    companion object {
        private const val TAG = "TeslaLocalHttpServer"
    }

    private var serverEngine: ApplicationEngine? = null
    private val activeSessions = Collections.newSetFromMap(ConcurrentHashMap<WebSocketSession, Boolean>())
    private val serverScope = CoroutineScope(Dispatchers.IO)

    var onClientConnected: (() -> Unit)? = null
    var onClientDisconnected: (() -> Unit)? = null

    val connectedClientsCount: Int
        get() = activeSessions.size

    fun start() {
        if (serverEngine != null) return
        Log.i(TAG, "Avvio Server HTTP/WebSocket locale sulla porta $port...")

        serverEngine = embeddedServer(Netty, port = port, host = "0.0.0.0") {
            install(CORS) {
                anyHost()
                allowHeader("*")
                allowMethod(io.ktor.http.HttpMethod.Get)
                allowMethod(io.ktor.http.HttpMethod.Post)
                allowMethod(io.ktor.http.HttpMethod.Options)
            }

            install(WebSockets) {
                pingPeriod = java.time.Duration.ofSeconds(5)
                timeout = java.time.Duration.ofSeconds(15)
                maxFrameSize = 10 * 1024 * 1024 // 10 MB
                masking = false
            }

            configureRouting()
        }.start(wait = false)

        Log.i(TAG, "Server attivo su http://0.0.0.0:$port")
    }

    private fun Application.configureRouting() {
        routing {
            // Serve static files from Android Assets (web/)
            get("/") {
                serveAssetFile(call, "web/index.html", ContentType.Text.Html)
            }
            get("/index.html") {
                serveAssetFile(call, "web/index.html", ContentType.Text.Html)
            }
            get("/style.css") {
                serveAssetFile(call, "web/style.css", ContentType.Text.CSS)
            }
            get("/js/player.js") {
                serveAssetFile(call, "web/js/player.js", ContentType.Application.JavaScript)
            }
            get("/js/touch.js") {
                serveAssetFile(call, "web/js/touch.js", ContentType.Application.JavaScript)
            }
            get("/js/ws-client.js") {
                serveAssetFile(call, "web/js/ws-client.js", ContentType.Application.JavaScript)
            }
            get("/js/hud.js") {
                serveAssetFile(call, "web/js/hud.js", ContentType.Application.JavaScript)
            }

            // WebSocket Stream Endpoint
            webSocket("/stream") {
                activeSessions.add(this)
                Log.i(TAG, "[WS] Tesla browser connesso. Client attivi: ${activeSessions.size}")
                onClientConnected?.invoke()

                try {
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            handleClientTextMessage(this, frame.readText())
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "[WS] Sessione terminata: ${e.message}")
                } finally {
                    activeSessions.remove(this)
                    Log.i(TAG, "[WS] Tesla browser disconnesso. Client rimanenti: ${activeSessions.size}")
                    onClientDisconnected?.invoke()
                }
            }
        }
    }

    private suspend fun serveAssetFile(
        call: io.ktor.server.application.ApplicationCall,
        assetPath: String,
        contentType: ContentType
    ) {
        try {
            val inputStream: InputStream = context.assets.open(assetPath)
            val bytes = inputStream.readBytes()
            inputStream.close()
            call.respondBytes(bytes, contentType, HttpStatusCode.OK)
        } catch (e: Exception) {
            Log.e(TAG, "Impossibile leggere asset $assetPath: ${e.message}")
            call.respondText("File not found", ContentType.Text.Plain, HttpStatusCode.NotFound)
        }
    }

    private suspend fun handleClientTextMessage(session: WebSocketSession, text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type")

            when (type) {
                "touch" -> {
                    val action = json.optInt("action")
                    val x = json.optDouble("x", 0.0).toFloat()
                    val y = json.optDouble("y", 0.0).toFloat()
                    val pointerId = json.optInt("id", 0)
                    val ts = json.optLong("ts", System.currentTimeMillis())

                    touchInjector.injectTouch(
                        TeslaTouchEvent(action, x, y, pointerId, ts)
                    )
                }
                "ping" -> {
                    val pingTs = json.optDouble("ts", 0.0)
                    val pongJson = JSONObject().apply {
                        put("type", "pong")
                        put("ts", pingTs)
                    }
                    session.send(Frame.Text(pongJson.toString()))
                }
                "handshake" -> {
                    Log.i(TAG, "[Handshake] Client: ${json.optString("client")}, Risoluzione: ${json.optInt("screenWidth")}x${json.optInt("screenHeight")}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Errore parsing messaggio client: ${e.message}")
        }
    }

    /**
     * Broadcasts H.264 NAL unit to all connected Tesla browsers
     * Format: [1 byte 0x01 (Video Tag)] + [8 bytes Double Timestamp] + [H.264 NAL Chunk]
     */
    fun broadcastVideoFrame(nalData: ByteArray, presentationTimeUs: Long) {
        if (activeSessions.isEmpty()) return

        val nowMs = System.currentTimeMillis().toDouble()
        val buffer = ByteBuffer.allocate(1 + 8 + nalData.size).apply {
            order(ByteOrder.BIG_ENDIAN)
            put(0x01.toByte())
            putDouble(nowMs)
            put(nalData)
        }
        val binaryPacket = buffer.array()
        val frame = Frame.Binary(true, binaryPacket)

        serverScope.launch {
            for (session in activeSessions) {
                try {
                    session.send(frame)
                } catch (_: ClosedSendChannelException) {
                    activeSessions.remove(session)
                } catch (e: Exception) {
                    Log.w(TAG, "Errore invio frame: ${e.message}")
                }
            }
        }
    }

    fun stop() {
        try {
            serverEngine?.stop(gracePeriodMillis = 500, timeoutMillis = 1500)
        } catch (_: Exception) {}
        serverEngine = null
        activeSessions.clear()
        Log.i(TAG, "Server HTTP/WebSocket arrestato.")
    }
}
