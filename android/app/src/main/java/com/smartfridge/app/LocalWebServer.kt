package com.smartfridge.app

import android.content.Context
import java.io.File
import java.net.ServerSocket

/**
 * S3 最终版：迷你本地 HTTP 服务（ServerSocket 手写，零依赖）
 * 响应 GET / → files/web/index.html（热更版）→ assets/public（出厂兜底）
 * Capacitor server.url 指向本服务（官方 remote 模式，桥可用）
 */
class LocalWebServer(private val context: Context, private val webRoot: File) {

    private var serverSocket: ServerSocket? = null
    private var running = false

    fun start(): Boolean {
        return try {
            val ss = ServerSocket(8890)
            ss.reuseAddress = true
            serverSocket = ss
            running = true
            Thread {
                while (running) {
                    try {
                        val sock = ss.accept()
                        try {
                            val input = sock.getInputStream()
                            val buf = ByteArray(1024)
                            val n = input.read(buf)
                            if (n <= 0) { sock.close(); continue }
                            val req = String(buf, 0, n, Charsets.ISO_8859_1)
                            if (!req.startsWith("GET")) { sock.close(); continue }
                            val bytes = pageBytes()
                            val header = "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: text/html; charset=utf-8\r\n" +
                                "Content-Length: ${bytes.size}\r\n" +
                                // 2026-09-04 缓存根修: 禁用一切缓存, 保证 WebView 每次都取磁盘最新页(此前界面长期=旧缓存页)
                                "Cache-Control: no-store, no-cache, must-revalidate\r\n" +
                                "Pragma: no-cache\r\n" +
                                "Expires: 0\r\n" +
                                "Connection: close\r\n\r\n"
                            val out = sock.getOutputStream()
                            out.write(header.toByteArray(Charsets.ISO_8859_1))
                            out.write(bytes)
                            out.flush()
                        } catch (_: Exception) {
                        } finally {
                            try { sock.close() } catch (_: Exception) {}
                        }
                    } catch (e: Exception) {
                        if (running) { try { Thread.sleep(200) } catch (_: Exception) {} } else break
                    }
                }
            }.apply { isDaemon = true; start() }
            Trace.log(context, "localserver: running on 127.0.0.1:8890")
            true
        } catch (e: Exception) {
            Trace.log(context, "localserver: fail ${e.message}")
            false
        }
    }

    private fun pageBytes(): ByteArray {
        val hot = File(webRoot, "index.html")
        if (hot.exists()) return hot.readBytes()
        return context.assets.open("public/index.html").use { it.readBytes() }
    }
}
