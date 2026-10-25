package com.seagull.boxcast.debug

import android.util.Log
import com.seagull.boxcast.net.BoxConnection
import com.seagull.boxcast.video.BoxConnectionHolder
import com.seagull.boxcast.proto.Wire
import com.seagull.boxcast.root.RootKeeper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 内置 HTTP 调试接口（默认开启，127.0.0.1:9977）。
 *
 * 端点：
 *   GET  /log            App 自身 logcat（--pid 过滤，无需任何权限）
 *   GET  /status         JSON 全状态（连接/授权码/编码/root/服务端/发现列表）
 *   POST /server/start   服务端模式（6 端口 Accept，盒子主动连）
 *   POST /server/stop
 *   POST /cast/start     触发 MediaProjection 授权（走 Activity launcher）
 *   POST /cast/stop
 *   GET  /mock           内置 mock 盒子自测：连自己 7240 → Hello → 点播 → 收 H264 统计
 *   POST /ota            触发 OTA 检查
 *
 * 访问：adb forward tcp:9977 tcp:9977 后 http://127.0.0.1:9977/log
 */
class DebugHttpServer(private val port: Int = 9977) {

    companion object {
        private const val TAG = "DebugHttp"

        /** /cast/start 触发：启动 ProxyActivity 拿授权（appContext，不依赖 Activity 生命周期） */
        @Volatile var appContext: android.content.Context? = null

        /** 单例：DebugService 重启后复用同一实例（防端口 BindException） */
        @Volatile var instance: DebugHttpServer? = null
        @Volatile var stopTrigger: (() -> Unit)? = null

        /** 内存日志环（App 内 Log.i 双写，/log 也能读） */
        val ring = CopyOnWriteArrayList<String>()
        private val ringLock = Any()
        fun log(tag: String, msg: String) {
            synchronized(ringLock) {
                ring.add("${System.currentTimeMillis() / 1000 % 100000} [$tag] $msg")
                while (ring.size > 500) ring.removeAt(0)   // 原子修剪（原 if+removeAt 并发 IOOBE）
            }
        }
    }

    @Volatile private var running = false
    private var serverThread: Thread? = null
    @Volatile private var serverSocket: ServerSocket? = null

    fun start() {
        if (running) return
        running = true
        serverThread = Thread({
            try {
                val ss = ServerSocket(port, 50, java.net.InetAddress.getByName("127.0.0.1"))
                serverSocket = ss
                Log.i(TAG, "debug http on 127.0.0.1:$port")
                ss.soTimeout = 1000   // 1s 轮询：stop() 后最多 1s 退出（accept 不可 interrupt）
                while (running) {
                    val s = runCatching { ss.accept() }.getOrNull() ?: continue
                    Thread({ handle(s) }, "dbg").start()
                }
            } catch (e: Exception) {
                Log.e(TAG, "server stop: ${e.message}")
            } finally {
                runCatching { serverSocket?.close() }   // **必须 close**：否则端口永久占，
                serverSocket = null                     // 重启 start() 必 BindException（9977 假死）
            }
            Log.i(TAG, "debug http exit")
        }, "DebugHttp").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }   // close 唤醒 accept（interrupt 无效）
    }

    private fun handle(s: Socket) {
        runCatching {
            s.soTimeout = 10_000
            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0) ?: "GET"
            val path = parts.getOrNull(1) ?: "/"
            // 读掉 header
            while (true) { val l = reader.readLine() ?: break; if (l.isEmpty()) break }

            val (code, body) = route(method, path)
            val out = s.getOutputStream()
            val resp = body.toByteArray()
            out.write("HTTP/1.1 $code OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${resp.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(resp)
            out.flush()
        }
        runCatching { s.close() }
    }

    private fun route(method: String, path: String): Pair<String, String> {
        val conn = BoxConnectionHolder.connection
        return when {
            path.startsWith("/log") -> {
                val n = Regex("n=(\\d+)").find(path)?.groupValues?.get(1)?.toIntOrNull() ?: 300
                val text = runCatching {
                    val p = ProcessBuilder("logcat", "-d", "-t", "$n", "--pid=${android.os.Process.myPid()}")
                        .redirectErrorStream(true).start()
                    try { p.inputStream.bufferedReader().readText() }
                    finally { runCatching { p.destroy() } }   // 防僵尸进程累积
                }.getOrElse { ring.takeLast(n).joinToString("\n") }
                "200" to escape(text)
            }
            path.startsWith("/status") -> "200" to statusJson(conn)
            path == "/server/start" && method == "POST" -> {
                conn?.startServer(); "200" to """{"ok":true,"msg":"server started"}"""
            }
            path == "/cast/start" && method == "POST" -> {
                val ctx = appContext
                if (ctx != null) {
                    val i = android.content.Intent(ctx, com.seagull.boxcast.debug.ProxyActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(i)
                    "200" to """{"ok":true,"msg":"proxy activity started"}"""
                } else "500" to """{"ok":false,"msg":"no context"}"""
            }
            path == "/cast/stop" && method == "POST" -> {
                stopTrigger?.invoke(); "200" to """{"ok":true,"msg":"cast stopped"}"""
            }
            path == "/mock" -> {
                Thread({ MockSelf.run(conn) }, "mock").start()
                "200" to """{"ok":true,"msg":"mock started, see /log"}"""
            }
            else -> "404" to """{"endpoints":["/log?n=300","/status","/server/start","/cast/start","/cast/stop","/mock"]}"""
        }
    }

    private fun statusJson(conn: com.seagull.boxcast.net.BoxConnection?): String {
        val st = conn?.state?.value
        val hello = conn?.lastHello
        val rs = RootKeeper.status.value
        return """{"connection":"${st?.javaClass?.simpleName}","authorized":${st is com.seagull.boxcast.net.BoxConnection.State.Authorized},"code":${(st as? com.seagull.boxcast.net.BoxConnection.State.Authorized)?.code ?: -1},"hello":{"type":"${hello?.type ?: ""}","tag":"${hello?.tag ?: ""}"},"root":{"su":${rs.suAvailable},"keepalive":${rs.keepaliveInstalled},"oomImmune":${rs.oomImmune},"accessibility":${rs.accessibilityOn}},"vehicleState":${conn?.vehicleState?.value?.connectState ?: -1}}"""
    }

    private fun escape(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("  ")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}

/**
 * 内置 mock 盒子（mockbox.py 的 Kotlin 版）——App 连自己的 7240 跑全链路自测。
 * Hello tag 用 VerifyCode 实证算法（md5(input+"CARLIFE")）。
 */
object MockSelf {
    fun run(conn: BoxConnection?) {
        val log = { m: String -> DebugHttpServer.log("mock", m) }
        log("连接 127.0.0.1:7240 ...")
        var sock: java.net.Socket? = null
        runCatching {
            val s = java.net.Socket("127.0.0.1", 7240)
            sock = s
            s.tcpNoDelay = true
            log("已连，发 Hello")
            // 帧构造直接用 CarlifeFrame（外层 [channel:u32][len:u32] + 内层 8B 头）
            val cf = com.seagull.boxcast.frame.CarlifeFrame
            fun outer(ch: Int, p: ByteArray) = cf.outer(ch, p)
            fun inner(st: Int, p: ByteArray) = cf.inner(st, p)
            fun helloProto(tag: String) = Wire.Writer().apply {
                bool(1, false); string(2, "yundian"); int32(3, 1)
                string(4, "AA:BB:CC:DD:EE:FF"); int32(5, 12345)
                string(6, tag); bool(7, false); string(8, "2.0.0"); string(9, "127.0.0.1")
            }.toByteArray()
            val tag = md5hex("yundian_AA:BB:CC:DD:EE:FF_1_12345" + "CARLIFE")
            s.getOutputStream().write(outer(1, inner(102403, helloProto(tag))))
            log("Hello 发出 tag=$tag")
            Thread.sleep(2000)
            // 102405 点播列表请求
            s.getOutputStream().write(outer(1, inner(102405, ByteArray(0))))
            log("102405 H264LIST_REQUEST 发出")
            Thread.sleep(2000)
            // 102407 发送请求 832x480
            val req = Wire.Writer().apply {
                int32(1, 832); int32(2, 480); int32(3, 1); int64(4, 0)
                string(5, ""); bool(6, false); int32(7, 1)
            }.toByteArray()
            s.getOutputStream().write(outer(1, inner(102407, req)))
            log("102407 SEND_H264_REQUEST 发出")
            // 收流统计 15s
            s.soTimeout = 15000
            var frames = 0; var bytes = 0; var init = false; var start = false
            val buf = java.io.ByteArrayOutputStream()
            val t0 = System.currentTimeMillis()
            val b = ByteArray(65536)
            while (System.currentTimeMillis() - t0 < 15000) {
                val n = runCatching { s.getInputStream().read(b) }.getOrNull() ?: break
                if (n <= 0) break
                buf.write(b, 0, n)
                // 粗解析外层+内层（处理完整帧）
                var data = buf.toByteArray()
                while (data.size >= 8) {
                    val len = com.seagull.boxcast.frame.CarlifeFrame.u32BE(data, 4)
                    if (data.size < 8 + len) break
                    val payload = data.copyOfRange(8, 8 + len.toInt())
                    data = data.copyOfRange(8 + len.toInt(), data.size)
                    if (payload.size >= 8) {
                        val st = com.seagull.boxcast.frame.CarlifeFrame.u32BE(payload, 4).toInt()
                        val body = payload.copyOfRange(8, payload.size)
                        when (st) {
                            102406 -> { init = true; log("← 102406 H264LIST_RESPONSE 收到") }
                            102408 -> { start = true; log("← 102408 SEND_H264_START 收到") }
                            102409 -> { frames++; bytes += body.size }
                            102410 -> log("← 102410 END 收到")
                        }
                    }
                }
                buf.reset(); buf.write(data)
            }
            log("===== mock 结果：init=$init start=$start 帧数=$frames 字节=$bytes =====")
            if (frames > 0) log("✅ 全链路自测通过（Hello→认证→点播→H264 收流）")
            else log("❌ 没收到 H264——看 /log 找 BoxConnection/ScreenCast 日志")
            s.close()
        }.onFailure { log("mock 异常: ${it.message}") }
        // 异常路径也关 socket（Kotlin runCatching 无 finally 成员，用外层 try/finally）
        runCatching { sock?.close() }
    }

    private fun md5hex(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
