package com.fakelocation.app.bridge

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Tiny LAN HTTP server so a WeChat mini program (dev build) can poll mock coordinates.
 * WeChat often ignores Android system mock GPS; this bridge is the reliable test path.
 */
class LocationHttpServer(private val port: Int = DEFAULT_PORT) {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    val isAlive: Boolean get() = running.get()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        acceptThread = thread(name = "loc-bridge-http", isDaemon = true) {
            try {
                ServerSocket(port).use { server ->
                    serverSocket = server
                    server.reuseAddress = true
                    Log.i(TAG, "Location bridge listening on :$port")
                    while (running.get()) {
                        try {
                            val client = server.accept()
                            thread(name = "loc-bridge-req", isDaemon = true) {
                                handleClient(client)
                            }
                        } catch (t: Throwable) {
                            if (running.get()) {
                                Log.w(TAG, "accept failed: ${t.message}")
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "server failed: ${t.message}")
                running.set(false)
            } finally {
                serverSocket = null
            }
        }
    }

    fun stop() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: Throwable) {
        }
        serverSocket = null
        acceptThread = null
    }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 5_000
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            // Drain headers
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            val path = requestLine.split(" ").getOrNull(1)?.substringBefore("?") ?: "/"
            when {
                path == "/location" || path == "/v1/location" -> {
                    writeJson(s, 200, locationJson())
                }
                path == "/health" -> {
                    writeJson(s, 200, JSONObject().put("ok", true).put("port", port).toString())
                }
                path == "/" || path == "/index.html" -> {
                    writeHtml(s, 200, helpHtml())
                }
                else -> writeJson(s, 404, JSONObject().put("error", "not_found").toString())
            }
        }
    }

    private fun locationJson(): String {
        val gcj = LocationBridgeStore.mapGcj
        val wgs = LocationBridgeStore.wgs
        return JSONObject()
            .put("ok", true)
            .put("running", LocationBridgeStore.running)
            .put("provider", "fakelocation-bridge")
            .put("type", "gcj02")
            .put("latitude", gcj?.latitude ?: JSONObject.NULL)
            .put("longitude", gcj?.longitude ?: JSONObject.NULL)
            .put("altitude", 10.0)
            .put("accuracy", 8.0)
            .put("horizontalAccuracy", 8.0)
            .put("speed", LocationBridgeStore.speedMps.toDouble())
            .put("bearing", LocationBridgeStore.bearing.toDouble())
            .put("progress", LocationBridgeStore.progress.toDouble())
            .put("updatedAt", LocationBridgeStore.updatedAtMs)
            .put(
                "wgs84",
                if (wgs == null) JSONObject.NULL
                else JSONObject()
                    .put("latitude", wgs.latitude)
                    .put("longitude", wgs.longitude)
            )
            .toString()
    }

    private fun helpHtml(): String = """
        <!doctype html><html><head><meta charset="utf-8"/>
        <meta name="viewport" content="width=device-width,initial-scale=1"/>
        <title>FakeLocation Bridge</title>
        <style>
          body{font-family:sans-serif;background:#0F766E;color:#F0FDFA;padding:20px;line-height:1.5}
          code{background:#134E4A;padding:2px 6px;border-radius:6px}
          a{color:#99F6E4}
        </style></head><body>
        <h2>小程序定位桥接</h2>
        <p>微信常会忽略系统模拟 GPS。请让<strong>你自己的小程序</strong>在开发模式下请求：</p>
        <p><code>GET /location</code></p>
        <p>返回 GCJ-02 坐标 JSON，可直接替代 <code>wx.getLocation</code>。</p>
        <p><a href="/location">点击查看 /location</a></p>
        </body></html>
    """.trimIndent()

    private fun writeJson(socket: Socket, code: Int, body: String) {
        writeResponse(socket, code, "application/json; charset=utf-8", body)
    }

    private fun writeHtml(socket: Socket, code: Int, body: String) {
        writeResponse(socket, code, "text/html; charset=utf-8", body)
    }

    private fun writeResponse(socket: Socket, code: Int, contentType: String, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val status = when (code) {
            200 -> "200 OK"
            404 -> "404 Not Found"
            else -> "$code"
        }
        val header = buildString {
            append("HTTP/1.1 $status\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, OPTIONS\r\n")
            append("Connection: close\r\n")
            append("Cache-Control: no-store\r\n")
            append("\r\n")
        }
        OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8).use { out ->
            out.write(header)
            out.flush()
        }
        socket.getOutputStream().write(bytes)
        socket.getOutputStream().flush()
    }

    companion object {
        const val DEFAULT_PORT = 18765
        private const val TAG = "LocationHttpServer"

        fun findLanIpv4(): String? {
            return try {
                NetworkInterface.getNetworkInterfaces()?.toList()
                    ?.flatMap { it.inetAddresses.toList() }
                    ?.firstOrNull { addr ->
                        !addr.isLoopbackAddress && addr is Inet4Address && !addr.isLinkLocalAddress
                    }
                    ?.hostAddress
            } catch (_: Throwable) {
                null
            }
        }
    }
}
