package com.seagull.boxcast.net

import android.content.Context
import android.util.Log
import com.seagull.boxcast.crypto.VerifyCode
import com.seagull.boxcast.frame.CarlifeFrame
import com.seagull.boxcast.frame.FrameReader
import com.seagull.boxcast.frame.parseInner
import com.seagull.boxcast.inject.InjectorRegistry
import com.seagull.boxcast.inject.TouchProto
import com.seagull.boxcast.proto.BoxCheckUpdate
import com.seagull.boxcast.proto.BoxH264List
import com.seagull.boxcast.proto.BoxHello
import com.seagull.boxcast.proto.BoxVehicleState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue

/**
 * 盒子连接（复刻 BoxConnectClient + ConnectManager 行为）。
 *
 * 流程（逆向实证）：
 *   1. TCP 连盒子
 *   2. 收 Hello(102403) → verifyBoxTag 认证 → 授权分级
 *   3. 起视频心跳(102402)
 *   4. checkBoxReset：isResetResolution / isHuRate → VideoResolution.reset() + sendWH2Box(w,h)
 *   5. 车辆状态(102418) 回调；OTA(102416) 请求
 *
 * TODO-CALIBRATE:
 *   - [DATA_PORT]：盒子 TCP 数据端口。OTA 是 HTTP(80)。CarLife 标准 cmd=7240，
 *     盒子自有端口需抓包确认（先用 7240 试，不行扫 7240/8240/9340/自定义高端口）。
 *   - 角色：本实现按"手机=客户端、盒子=服务端"连。若实测盒子主动连手机，
 *     把 [connect] 换成监听 Accept（协议层不变）。
 *   - 内层帧是否真用于盒子通道：CarLife 标准通道用外层+内层；若盒子裸传 protobuf，
 *     把 [send] 里的 inner() 去掉即可（一行开关，见 [USE_INNER_FRAME]）。
 */
class BoxConnection(private val appContext: Context) {

    companion object {
        private const val TAG = "BoxConnection"

        /** TODO-CALIBRATE: 盒子数据端口 */
        val DATA_PORT_CANDIDATES = listOf(7240, 8240, 9340, 9800, 18899, 40001)

        /** 内层帧开关：true=标准 CarLife 双层；false=外层直接裸 protobuf */
        const val USE_INNER_FRAME = true

        private const val HEARTBEAT_MS = 3000L

        /** 收发缓冲（ConnectSocket 实证 320KB） */
        private const val SEND_BUFFER_SIZE = 327680
        private const val RECV_BUFFER_SIZE = 327680

        /**
         * 服务端端口表——**真机日志实证**（ConnectManager: Create Cmd/Video/TTS/VR/Touch/Data
         * AcceptThread ×6）：手机 app 是 TCP 服务端，盒子主动连这些端口。
         * 端口值沿用 2021 a/c.java WiFi 端口表实证。
         */
        val SERVER_PORTS = mapOf(
            CarlifeFrame.Channel.CMD to 7240,
            CarlifeFrame.Channel.VIDEO to 8240,
            CarlifeFrame.Channel.MEDIA to 9240,
            CarlifeFrame.Channel.TTS to 9241,
            CarlifeFrame.Channel.VR to 9242,
            CarlifeFrame.Channel.TOUCH to 9340,
            // DataAcceptThread（真机实证存在，端口 TODO-CALIBRATE：日志未打印，邻近段推测）
            7 to 9341,
        )
    }

    sealed interface State {
        data object Idle : State
        data class Connecting(val host: String, val port: Int) : State
        data class HelloReceived(val hello: BoxHello) : State
        data class Authorized(val hello: BoxHello, val code: Int) : State
        data class Failed(val reason: String) : State
        data object Closed : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: Socket? = null
    private var out: OutputStream? = null
    private var reader: FrameReader? = null

    /** 服务端模式：channel → Socket（每通道一条连接，真机 6 AcceptThread 实证） */
    private val serverSockets = java.util.concurrent.ConcurrentHashMap<Int, Socket>()
    private var serverJob: Job? = null
    private var readJob: Job? = null
    private var hbJob: Job? = null
    private var writeThread: Thread? = null
    private var writeActive = false

    /**
     * 独立写队列（对照原版 ConnectSocket.WriteThread 实证：priority 10 + BlockingQueue）。
     * 视频/音频大数据不再阻塞读循环（心跳/控制消息延迟是掉线主因）。
     */
    private val writeQueue = LinkedBlockingQueue<Pair<Int, ByteArray>>(256)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val _vehicleState = MutableStateFlow<BoxVehicleState?>(null)
    val vehicleState: StateFlow<BoxVehicleState?> = _vehicleState

    /** 最近一次 Hello（授权判据都从这里出） */
    @Volatile
    var lastHello: BoxHello? = null
        private set

    /** 盒子→手机 102405（请求 H.264 初始化）：应回 confirmH264Init() */
    var onH264ListRequest: (() -> Unit)? = null

    /** 盒子→手机 102407（请求发送，带宽高/长度）：应回 sendH264Start() */
    var onH264SendRequest: ((com.seagull.boxcast.proto.BoxH264Send) -> Unit)? = null

    // 标准视频握手（VideoMsgHandler 实证）
    var onVideoEncoderInit: ((width: Int, height: Int, fps: Int) -> Unit)? = null
    var onVideoEncoderStart: (() -> Unit)? = null
    var onVideoEncoderPause: (() -> Unit)? = null
    var onVideoEncoderReset: (() -> Unit)? = null
    var onVideoFrameRateChange: ((fps: Int) -> Unit)? = null
    var onVersionMatch: (() -> Unit)? = null

    /** 加密握手（标准 HU；盒子场景 configSwitch=false 不走） */
    val encryptHandshake = com.seagull.boxcast.crypto.EncryptHandshake(this)

    // 设备信息/亮灭屏/前后台/索权（CarlifeConnectHsgHandler 60 消息全集补齐）
    var onHuInfo: ((Map<String, String>) -> Unit)? = null
    var onScreenOn: (() -> Unit)? = null
    var onScreenOff: (() -> Unit)? = null
    var onVehicleForeground: (() -> Unit)? = null
    var onVehicleBackground: (() -> Unit)? = null
    /** HU 索权：type = location/read_write/record（MSG_PERMISSION_* 65579-65594） */
    var onPermissionRequest: ((type: String) -> Unit)? = null
    var onHuStatistics: ((com.seagull.boxcast.net.StatisticsInfo.Info) -> Unit)? = null

    val authorizedCode: Int
        get() = (state.value as? State.Authorized)?.code ?: BoxTypes.IS_NOT_BOX

    // ---------- 服务端模式（真机实证：盒子主动连手机） ----------

    /**
     * 起 6 个 Accept 线程（Cmd/Video/TTS/VR/Touch），等盒子接入。
     * 每个通道独立 socket + 独立读循环；发送按 channel 路由到对应 socket。
     */
    fun startServer() {
        if (serverJob?.isActive == true) return
        lastHost = "server"
        serverJob = scope.launch {
            for ((channel, port) in SERVER_PORTS) {
                launch(Dispatchers.IO) {
                    runCatching {
                        val ss = java.net.ServerSocket(port)
                        Log.i(TAG, "AcceptThread ch=$channel listening :$port")
                        while (isActive) {
                            val s = ss.accept()
                            s.tcpNoDelay = true
                            s.keepAlive = true
                            s.receiveBufferSize = RECV_BUFFER_SIZE
                            s.sendBufferSize = SEND_BUFFER_SIZE
                            serverSockets[channel] = s
                            Log.i(TAG, "AcceptThread ch=$channel accepted ${s.inetAddress}")
                            // 该通道的读循环
                            val r = FrameReader()
                            launch(Dispatchers.IO) {
                                try {
                                    while (isActive) {
                                        val (ch, payload) = r.read(s.getInputStream()) ?: break
                                        handleFrame(ch, payload)
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "ch=$channel read end: ${e.message}")
                                }
                                serverSockets.remove(channel)
                            }
                            // cmd 通道连上即视为连接建立（Hello 会随后到）
                            if (channel == CarlifeFrame.Channel.CMD) {
                                _state.value = State.Connecting("server", port)
                            }
                        }
                    }.onFailure { Log.e(TAG, "AcceptThread ch=$channel fail: ${it.message}") }
                }
            }
        }
    }

    /** 按 channel 取输出流（服务端模式路由） */
    private fun channelOut(channel: Int): OutputStream? =
        serverSockets[channel]?.takeIf { it.isConnected }?.getOutputStream()

    // ---------- 连接 ----------

    /**
     * 连盒子并按候选端口逐个尝试。
     * @param host 盒子 IP（发现模块给出，如 192.168.0.1）
     */
    /** 断开后自动重连（退避 1s→2s→4s…30s 封顶）；destroy() 停止 */
    @Volatile var autoReconnect = true
    private var lastHost: String? = null
    private var reconnectJob: Job? = null
    private var reconnectDelay = 1000L

    fun connect(host: String) {
        lastHost = host
        reconnectJob?.cancel()
        if (state.value is State.Connecting || state.value is State.Authorized) return
        scope.launch {
            try {
                for (port in DATA_PORT_CANDIDATES) {
                    _state.value = State.Connecting(host, port)
                    Log.i(TAG, "try $host:$port")
                    if (tryConnect(host, port)) {
                        Log.i(TAG, "connected $host:$port")
                        return@launch
                    }
                }
                _state.value = State.Failed("no data port reachable (tried $DATA_PORT_CANDIDATES)")
            } catch (e: Exception) {
                Log.e(TAG, "connect error", e)
                _state.value = State.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private suspend fun tryConnect(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        val s = Socket()
        return@withContext try {
            s.tcpNoDelay = true
            s.keepAlive = true                          // TCP 保活（原版 Ping 180s 的底层）
            s.receiveBufferSize = RECV_BUFFER_SIZE      // 320KB（原版实证）
            s.sendBufferSize = SEND_BUFFER_SIZE
            s.connect(InetSocketAddress(host, port), 4000)
            socket = s
            out = s.getOutputStream()
            reader = FrameReader()
            startWriteThread()
            startReadLoop(s.getInputStream())
            true
        } catch (e: Exception) {
            runCatching { s.close() }
            false
        }
    }

    // ---------- 写线程（原版 WriteThread 实证） ----------

    private fun startWriteThread() {
        writeActive = true
        writeQueue.clear()
        writeThread = Thread({
            android.os.Process.setThreadPriority(10)   // 原版 setPriority(10)
            val bos = BufferedOutputStream(out, SEND_BUFFER_SIZE)
            while (writeActive) {
                val (ch, frame) = runCatching { writeQueue.take() }.getOrNull() ?: continue
                runCatching {
                    // 客户端模式：统一 bos；服务端模式：按 channel 选 socket
                    val target = channelOut(ch) ?: bos
                    if (target == null) {
                        // 服务端模式该通道未接入（盒子没连这个端口）→ 丢弃该帧
                        if (serverSockets.isNotEmpty()) return@runCatching
                        error("no output stream")
                    }
                    target.write(frame)
                    target.flush()
                }.onFailure {
                    Log.e(TAG, "write fail, disconnect: ${it.message}")
                    close()   // 原版：写失败 → setIsConnected(false, WRITE_STREAM_ERROR)
                    return@Thread
                }
            }
        }, "BoxWrite").apply { start() }
    }

    /** 组帧入队（所有发送统一走这里） */
    private fun enqueue(frame: ByteArray, channel: Int = CarlifeFrame.Channel.CMD) {
        if (!writeActive) return
        if (!writeQueue.offer(channel to frame)) {
            Log.e(TAG, "write queue full, disconnect")
            close()
        }
    }

    // ---------- 读循环 ----------

    private fun startReadLoop(input: InputStream) {
        readJob = scope.launch {
            try {
                while (isActive) {
                    val (channel, payload) = reader!!.read(input) ?: break
                    handleFrame(channel, payload)
                }
            } catch (e: Exception) {
                Log.e(TAG, "read loop end: ${e.message}")
            }
            close()
        }
    }

    private fun handleFrame(channel: Int, payload: ByteArray) {
        var body = if (USE_INNER_FRAME) {
            parseInner(payload) ?: return
        } else {
            // 裸 protobuf 模式：channel 即 serviceType（联调时按实际调整）
            com.seagull.boxcast.frame.CmdMessage(channel, payload)
        }
        // 加密启用时解密（ConnectSocket 读侧对称处理）
        body = com.seagull.boxcast.frame.CmdMessage(body.serviceType,
            encryptHandshake.decryptIfEnabled(body.data))
        Log.i(TAG, "recv serviceType=${body.serviceType} len=${body.data.size}")
        // 单条消息解析失败不影响连接（畸形包/半包保护）
        runCatching {
            when (body.serviceType) {
                CarlifeFrame.CmdId.BOX_HELLO -> onHello(BoxHello.decode(body.data))
                CarlifeFrame.CmdId.BOX_VEHCILE_STATE -> _vehicleState.value = BoxVehicleState.decode(body.data)
                CarlifeFrame.CmdId.BOX_H264LIST_RESPONSE -> onH264List(BoxH264List.decode(body.data))
                CarlifeFrame.CmdId.BOX_CHECK_UPDATE_RES -> { /* OTA 结果，交给上层解析 */ }
                // 盒子→手机 H.264 点播请求（BoxH264Manager 实证方向）
                CarlifeFrame.CmdId.BOX_H264LIST_REQUEST -> onH264ListRequest?.invoke()
                CarlifeFrame.CmdId.BOX_SEND_H264_REQUEST -> {
                    val req = com.seagull.boxcast.proto.BoxH264Send.decode(body.data)
                    // 同步盒子分辨率到反控坐标映射（否则触摸坐标按默认 1920x1080 算全错）
                    if (req.width > 0 && req.height > 0) {
                        InjectorRegistry.injector?.let {
                            it.boxWidth = req.width
                            it.boxHeight = req.height
                        }
                        Log.i(TAG, "box resolution sync: ${req.width}x${req.height}")
                    }
                    onH264SendRequest?.invoke(req)
                }
                // 反控：车机/盒子触摸与硬按键 → 注入手机
                CarlifeFrame.CmdId.TOUCH_ACTION ->
                    InjectorRegistry.injector?.inject(TouchProto.TouchAction.decode(body.data))
                CarlifeFrame.CmdId.TOUCH_CAR_HARD_KEY_CODE ->
                    InjectorRegistry.injector?.injectKey(TouchProto.HardKeyCode.decode(body.data).keycode)
                // 标准视频握手（HU → 手机）
                CarlifeFrame.CmdId.MSG_CMD_VIDEO_ENCODER_INIT -> {
                    val info = com.seagull.boxcast.proto.BoxH264Send.decode(body.data)
                    onVideoEncoderInit?.invoke(info.width, info.height, info.frameType)
                }
                CarlifeFrame.CmdId.MSG_CMD_VIDEO_ENCODER_START -> onVideoEncoderStart?.invoke()
                CarlifeFrame.CmdId.MSG_CMD_VIDEO_ENCODER_PAUSE -> onVideoEncoderPause?.invoke()
                CarlifeFrame.CmdId.MSG_CMD_VIDEO_ENCODER_RESET -> onVideoEncoderReset?.invoke()
                CarlifeFrame.CmdId.MSG_CMD_VIDEO_ENCODER_FRAME_RATE_CHANGE -> {
                    val info = com.seagull.boxcast.proto.BoxH264Send.decode(body.data)
                    onVideoFrameRateChange?.invoke(info.frameType)
                }
                CarlifeFrame.CmdId.MSG_CMD_PROTOCOL_VERSION_MATCH_STATUS -> onVersionMatch?.invoke()
                // 加密握手（标准 HU）
                CarlifeFrame.CmdId.MSG_CMD_HU_RSA_PUBLIC_KEY_RESPONSE ->
                    encryptHandshake.onRsaResponse(body.data)
                CarlifeFrame.CmdId.MSG_CMD_HU_AES_REC_RESPONSE -> encryptHandshake.onAesRecResponse()
                // HU 统计上报（渠道号认证：carNumber/carType = 车型渠道数据源）
                CarlifeFrame.CmdId.MSG_CMD_STATISTIC_INFO -> {
                    val st = com.seagull.boxcast.net.StatisticsInfo.decode(body.data)
                    Log.i(TAG, "HU statistics: cuid=${st.cuid} channel=${st.channel} " +
                        "carNumber=${st.carNumber} carType=${st.carType}")
                    onHuStatistics?.invoke(st)
                }
                // 设备信息/亮灭屏/前后台/索权
                CarlifeFrame.CmdId.MSG_CMD_HU_INFO -> onHuInfo?.invoke(DeviceInfo.decode(body.data))
                CarlifeFrame.CmdId.MSG_CMD_SCREEN_ON -> onScreenOn?.invoke()
                CarlifeFrame.CmdId.MSG_CMD_SCREEN_OFF -> onScreenOff?.invoke()
                CarlifeFrame.CmdId.MSG_CMD_SCREEN_USERPRESENT -> onScreenOn?.invoke()
                CarlifeFrame.CmdId.MSG_CMD_VEHICLE_FOREGROUND -> onVehicleForeground?.invoke()
                CarlifeFrame.CmdId.MSG_CMD_VEHICLE_BACKGROUND -> onVehicleBackground?.invoke()
                CarlifeFrame.CmdId.MSG_PERMISSION_LOCATION -> onPermissionRequest?.invoke("location")
                CarlifeFrame.CmdId.MSG_PERMISSION_READ_WRITE -> onPermissionRequest?.invoke("read_write")
                CarlifeFrame.CmdId.MSG_PERMISSION_RECORD -> onPermissionRequest?.invoke("record")
            }
        }.onFailure { Log.w(TAG, "handle frame ${body.serviceType} fail: ${it.message}") }
    }

    // ---------- Hello / 授权（verifyBoxTag 复刻） ----------

    private fun onHello(hello: BoxHello) {
        lastHello = hello
        _state.value = State.HelloReceived(hello)
        Log.i(TAG, "hello type=${hello.type} mac=${hello.mac} active=${hello.active} " +
            "rand=${hello.randomvalue} tag=${hello.tag} ver=${hello.boxVersion} huRate=${hello.isHuRate}")

        // preMd5 = type_mac_active_randomvalue
        val verifyCode = VerifyCode.compute(appContext, hello.type, hello.mac, hello.active, hello.randomvalue)
        Log.i(TAG, "verifyCode=$verifyCode")

        var code = when {
            verifyCode.isNullOrEmpty() -> BoxTypes.IS_BOX_NOT_AUTHORIZED
            hello.tag.isEmpty() -> BoxTypes.IS_BOX_NOT_ACTIVATED
            hello.tag != verifyCode -> BoxTypes.IS_BOX_NOT_AUTHORIZED
            hello.active == 0 -> BoxTypes.IS_BOX_NOT_ACTIVATED
            hello.boxVersion == BoxTypes.VERSION_YUNDIAN_1 -> BoxTypes.IS_BOX_VERSION_TOO_OLD
            else -> BoxTypes.IS_BOX_AND_AUTHORIZED
        }
        _state.value = State.Authorized(hello, code)
        Log.i(TAG, "boxAuthorized=$code")

        // checkBoxReset：分辨率协商（复刻 VideoResolution.reset + sendWH2Box）
        if (hello.isResetResolution || !hello.isHuRate) {
            Log.i(TAG, "box reset resolution (isResetResolution=${hello.isResetResolution}, isHuRate=${hello.isHuRate})")
        }

        // Hello 后：上报手机身份 MD_INFO(65540)（CarlifeConnectHsgHandler:836 实证）+
        // 起心跳。MD_INFO 内容是 Build 全字段，盒子/HU 可据此适配
        runCatching {
            sendRaw(com.seagull.boxcast.frame.CmdId.MSG_CMD_MD_INFO,
                DeviceInfo.encodeMdInfo(), CarlifeFrame.Channel.CMD)
        }

        // Hello 后起心跳
        startHeartbeat()
    }

    private fun onH264List(list: BoxH264List) {
        Log.i(TAG, "h264 list cnt=${list.cnt} rsCode=${list.rsCode} rsId=${list.rsId} ver=${list.versionCode}")
    }

    // ---------- 发送 ----------

    private fun send(serviceType: Int, payload: ByteArray, channel: Int = CarlifeFrame.Channel.CMD) {
        val secured = encryptHandshake.encryptIfEnabled(payload)
        val body = if (USE_INNER_FRAME) {
            CarlifeFrame.inner(serviceType, secured)
        } else secured
        enqueue(CarlifeFrame.outer(channel, body), channel)
    }

    /** 102401 改帧宽高（sendWH2Box 复刻） */
    fun sendFrameWH(width: Int, height: Int) {
        // 原版走 VideoSettingFragment 的 sendWH2Box；消息体以 102401 定义为准
        send(CarlifeFrame.CmdId.BOX_CHANGE_FRAME_WH, com.seagull.boxcast.proto.Wire.Writer()
            .int32(1, width).int32(2, height).toByteArray())
    }

    /** 公开发送：盒子命令（内层+外层封装，默认 channel 1） */
    fun sendRaw(serviceType: Int, payload: ByteArray, channel: Int = CarlifeFrame.Channel.CMD) =
        send(serviceType, payload, channel)

    /**
     * 两段式发送（对应 2021 d/o.java 的 [header][payload] 双段 bulkTransfer 写）：
     * 内层消息 + 元信息 protobuf + 裸 H.264 数据。
     *
     * @param channel 逻辑 channel。**视频必须走 VIDEO(2)**——2021 a/c.java 端口表实证
     *               VideoSocket=8240=ch2；控制/心跳/列表走 CMD(1)。
     */
    fun sendRawTwoPart(serviceType: Int, meta: ByteArray, raw: ByteArray,
                       channel: Int = CarlifeFrame.Channel.VIDEO) {
        val body = if (USE_INNER_FRAME) {
            CarlifeFrame.inner(serviceType, meta)
        } else meta
        val frame = if (USE_INNER_FRAME) {
            // [channel][len(header+raw)] 头里 len 覆盖两段（与 d/o.java:83-91 一致）
            val h = ByteArray(8)
            CarlifeFrame.putU32BE(h, 0, channel.toLong())
            CarlifeFrame.putU32BE(h, 4, (body.size + raw.size).toLong())
            h + body + raw
        } else {
            CarlifeFrame.outer(channel, meta + raw)
        }
        enqueue(frame, channel)
    }

    /** 102402 视频心跳 */
    fun sendHeartbeat() {
        send(CarlifeFrame.CmdId.BOX_VIDEO_HEARTBEAT, ByteArray(0))
    }

    /** 102405 请求 H.264 列表 */
    fun requestH264List() {
        send(CarlifeFrame.CmdId.BOX_H264LIST_REQUEST, BoxH264List().encode())
    }

    /** 102416 OTA 检查更新 */
    fun sendCheckUpdate(req: BoxCheckUpdate) {
        send(CarlifeFrame.CmdId.BOX_CHECK_UPDATE_REQ, req.encode())
    }

    /** 盒子 OTA 地址（按当前 Hello.type 取） */
    fun otaAddress(): String? = lastHello?.type?.let { BoxTypes.OTA[it] }

    // ---------- 心跳 ----------

    private fun startHeartbeat() {
        if (hbJob?.isActive == true) return
        hbJob = scope.launch {
            while (isActive) {
                runCatching { sendHeartbeat() }
                kotlinx.coroutines.delay(HEARTBEAT_MS)
            }
        }
    }

    // ---------- 关闭 ----------

    fun close() {
        writeActive = false
        writeQueue.clear()
        runCatching { writeThread?.interrupt() }
        runCatching { socket?.close() }
        serverSockets.values.forEach { runCatching { it.close() } }
        serverSockets.clear()
        hbJob?.cancel(); readJob?.cancel()
        encryptHandshake.onDisconnection()
        _state.value = State.Closed
        scheduleReconnect()
    }

    /** 退避重连（读循环异常退出/写失败都会走到 close → 这里接住） */
    private fun scheduleReconnect() {
        if (!autoReconnect) return
        val host = lastHost ?: return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            Log.i(TAG, "reconnect in ${reconnectDelay}ms to $host")
            kotlinx.coroutines.delay(reconnectDelay)
            reconnectDelay = (reconnectDelay * 2).coerceAtMost(30_000L)
            for (port in DATA_PORT_CANDIDATES) {
                _state.value = State.Connecting(host, port)
                if (tryConnect(host, port)) {
                    reconnectDelay = 1000L   // 连上重置退避
                    return@launch
                }
            }
            scheduleReconnect()   // 全端口失败，继续退避
        }
    }

    fun destroy() {
        autoReconnect = false
        reconnectJob?.cancel()
        close()
        scope.cancel()
    }
}
