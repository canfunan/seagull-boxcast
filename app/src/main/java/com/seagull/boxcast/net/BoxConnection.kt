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
            // Data 通道（WirlessConnector.java:22-29 权威：channel7=9440）
            7 to 9440,
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
    @Volatile private var writeActive = false

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
    /** 盒→手 102411 全部完成 */
    var onH264AllDone: (() -> Unit)? = null

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
        // 必坏级修复：服务端模式此前从不调 startWriteThread() → writeActive=false →
        // 所有 enqueue 静默丢弃（MD_INFO/心跳/102406/102408 一律发不出，无日志）。
        if (!writeActive) startWriteThread()
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
                            serverSockets.put(channel, s)?.let { old ->   // 覆盖前关旧（FD 泄漏）
                                if (old !== s) runCatching { old.close() }
                            }
                            Log.i(TAG, "AcceptThread ch=$channel accepted ${s.inetAddress}")
                            // 该通道的读循环（TCP：内层头直读，读出的 channel 是推断值，
                            // 用真实 socket 通道覆盖——服务端每 socket 一个通道）
                            val r = FrameReader(useAoaFraming = false)
                            launch(Dispatchers.IO) {
                                try {
                                    while (isActive) {
                                        val (_chInferred, payload) = r.read(s.getInputStream()) ?: break
                                        // TCP 读侧 channel 是推断值（8B/12B 判定），以 socket 通道为准
                                        handleFrame(channel, payload)
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "ch=$channel read end: ${e.message}")
                                }
                                runCatching { s.close() }
                                // identity 比对：只删自己这条（新连接的映射可能已覆盖）
                                serverSockets.remove(channel)?.let { cur ->
                                    if (cur !== s) serverSockets[channel] = cur
                                }
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
        serverSockets[channel]?.takeIf { !it.isClosed && it.isConnected }?.getOutputStream()

    // ---------- 连接 ----------

    /**
     * 连盒子并按候选端口逐个尝试。
     * @param host 盒子 IP（发现模块给出，如 192.168.0.1）
     */
    /** 断开后自动重连（退避 1s→2s→4s…30s 封顶）；destroy() 停止 */
    @Volatile var autoReconnect = true
    private var lastHost: String? = null
    @Volatile private var reconnectJob: Job? = null
    private var reconnectDelay = 1000L
    private val reconnectLock = Any()

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
            reader = FrameReader(useAoaFraming = false)   // TCP：内层头直读
            if (!writeActive) startWriteThread()   // 幂等：客户端/服务端共用一条写线程
            startReadLoop(s.getInputStream())
            reconnectDelay = 1000L                  // 手动连上也重置退避（原仅 reconnect 重置）
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
            // **bos 可空**：服务端模式没有客户端 socket（out=null）——
            // 原 BufferedOutputStream(out) NPE → 未捕获异常杀整个进程（server/start 即崩）
            val bos = out?.let { BufferedOutputStream(it, SEND_BUFFER_SIZE) }
            // poll(超时) 而非 take()：close() 置 false + interrupt 后最多 200ms 退出
            // （take() 吞 InterruptedException 且清中断标志 → 线程永久阻塞泄漏）
            while (writeActive) {
                val item = runCatching { writeQueue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrNull()
                    ?: continue
                val (ch, frame) = item
                runCatching {
                    // 服务端模式：按 channel 路由到对应 socket；客户端模式：bos
                    val target = channelOut(ch) ?: if (serverSockets.isNotEmpty()) return@runCatching else bos ?: return@runCatching
                    target.write(frame)
                    target.flush()
                }.onFailure {
                    Log.e(TAG, "write fail, disconnect: ${it.message}")
                    close()   // 原版：写失败 → setIsConnected(false, WRITE_STREAM_ERROR)
                    return@Thread
                }
            }
            Log.i(TAG, "write thread exit")
        }, "BoxWrite").apply {
            // 兜底：写线程任何未捕获异常不再杀进程（Android 默认 handler 会杀）
            setUncaughtExceptionHandler { _, e -> Log.e(TAG, "write thread crash", e) }
            start()
        }
    }

    /** 组帧入队。视频/音频大数据帧可弃（拥塞时丢帧保连接），控制帧（ch1/ch6）满才断 */
    private fun enqueue(frame: ByteArray, channel: Int = CarlifeFrame.Channel.CMD) {
        if (!writeActive) return
        if (writeQueue.offer(channel to frame)) return
        if (channel == CarlifeFrame.Channel.CMD || channel == CarlifeFrame.Channel.TOUCH) {
            Log.e(TAG, "ctrl queue full, disconnect")
            close()
        } else {
            Log.w(TAG, "data queue full, drop frame (ch=$channel size=${frame.size})")
        }
    }

    // ---------- 读循环 ----------

    private fun startReadLoop(input: InputStream) {
        readJob = scope.launch {
            try {
                while (isActive) {
                    val (channelInferred, payload) = reader!!.read(input) ?: break
                    handleFrame(channelInferred, payload)
                }
            } catch (e: Exception) {
                Log.e(TAG, "read loop end: ${e.message}")
            }
            close()
        }
    }

    private fun handleFrame(channel: Int, payload: ByteArray) {
        var body = if (USE_INNER_FRAME) {
            parseInner(payload, channel) ?: return
        } else {
            // 裸 protobuf 模式：channel 即 serviceType（联调时按实际调整）
            com.seagull.boxcast.frame.CmdMessage(channel, payload)
        }
        // 加密启用时解密（ConnectSocket 读侧对称处理）
        body = com.seagull.boxcast.frame.CmdMessage(body.serviceType,
            encryptHandshake.decryptIfEnabled(body.data))
        if (body.serviceType == 131074) {
            lastVideoHeartbeat = System.currentTimeMillis()   // 视频心跳（6s 监控）
            return
        }
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
                // 盒→手 102404 分辨率列表 → 回 confirmResolution（BoxH264Manager.checkResolution:161-182）
                CarlifeFrame.CmdId.BOX_H264_LIST -> {
                    val list = BoxH264List.decode(body.data)
                    Log.i(TAG, "box h264 list cnt=${list.cnt} infos=${list.h264Info}")
                    if (list.cnt > 0 && list.h264Info.isNotEmpty()) {
                        // 取第一个（最低档）确认，同 checkResolution 默认 848x480
                        val (w0, h0) = list.h264Info.first()
                        sendFrameWH(w0, h0)
                        send(CarlifeFrame.CmdId.BOX_H264_LIST, BoxH264List(
                            cnt = 1, h264Info = listOf(w0 to h0)).encode())
                    }
                }
                // 盒→手 102411 全部完成（onH264AllDone）
                CarlifeFrame.CmdId.BOX_H264_DONE -> {
                    Log.i(TAG, "box h264 all done (102411)")
                    onH264AllDone?.invoke()
                }
                // 标准 HU 握手起点（2026 ConnectionEstablishHandler.java:223-225）：
                // HU 发 98305 → 手机回 65538 MATCH_STATUS{matchStatus=1}
                CarlifeFrame.CmdId.MSG_CMD_HU_PROTOCOL_VERSION -> {
                    Log.i(TAG, "HU protocol version (98305) → reply 65538 matchStatus=1")
                    send(CarlifeFrame.CmdId.MSG_CMD_PROTOCOL_VERSION_MATCH_STATUS,
                        com.seagull.boxcast.proto.Wire.Writer().int32(1, 1).toByteArray())
                }
                // 盗版盒/未激活处理（CarlifeConnectHsgHandler.java:931-995）：
                // 0/1/2 → 日志警示（对话框 UI 后续加）
                else if (body.serviceType == 0) { }
                // HU 车辆激活（98432）回执占位
                CarlifeFrame.CmdId.BOX_SEND_H264_END -> { /* END 由 sender 处理，忽略重复 */ }
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
                // 反控：车机/盒子触摸与硬按键 → 注入手机（协议族全集 f/y.java:46-104）
                CarlifeFrame.CmdId.TOUCH_ACTION ->
                    InjectorRegistry.injector?.inject(TouchProto.TouchAction.decode(body.data))
                CarlifeFrame.CmdId.TOUCH_ACTION_DOWN,
                CarlifeFrame.CmdId.TOUCH_ACTION_MOVE,
                CarlifeFrame.CmdId.TOUCH_ACTION_UP -> {
                    // 单指 down/move/up：action 参数区分（1=down 2=move 3=up）
                    val act = if (body.serviceType == CarlifeFrame.CmdId.TOUCH_ACTION_DOWN) 1
                              else if (body.serviceType == CarlifeFrame.CmdId.TOUCH_ACTION_MOVE) 2 else 3
                    val t = TouchProto.TouchAction.decode(body.data)
                    InjectorRegistry.injector?.inject(t.copy(action = act))
                }
                CarlifeFrame.CmdId.TOUCH_ACTION_CLICK -> {
                    // 单击： down+up（f/y.java 同款合成）
                    val t = TouchProto.TouchAction.decode(body.data)
                    InjectorRegistry.injector?.inject(t.copy(action = 1))
                    InjectorRegistry.injector?.inject(t.copy(action = 3))
                }
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
                else -> {}   // 其余消息忽略（Kotlin when 穷尽）
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

        // 权威 verifyBoxTag:126-142：tag/verifyCode 为空或 !hasActive → 2(未激活)；
        // tag≠code → 0(未授权)；active==0 → **1**(未激活，2026 车机报告修正)；
        // 否则 3(已授权)
        var code = when {
            verifyCode.isNullOrEmpty() || hello.tag.isEmpty() ->
                BoxTypes.IS_BOX_NOT_ACTIVATED          // 2
            hello.tag != verifyCode -> BoxTypes.IS_BOX_NOT_AUTHORIZED   // 0
            hello.active == 0 -> BoxTypes.IS_BOX_VERSION_TOO_OLD        // 1（active 未开启）
            else -> BoxTypes.IS_BOX_AND_AUTHORIZED                      // 3
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
            sendRaw(com.seagull.boxcast.frame.CarlifeFrame.CmdId.MSG_CMD_MD_INFO,
                DeviceInfo.encodeMdInfo(), CarlifeFrame.Channel.CMD)
        }

        // ModuleStatus 上报（公签 f/i.java:53-76：modules 1/2/3/4/6 全 1）
        runCatching {
            val ms = com.seagull.boxcast.proto.Wire.Writer().apply {
                int32(1, 1); int32(2, 1); int32(3, 1); int32(4, 1); int32(6, 1)
            }.toByteArray()
            send(CarlifeFrame.CmdId.MSG_CMD_MODULE_STATUS, ms)
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
        // **TCP 直写内层头**（三方权威实证：2026 SocketCommunicator.java:98-112 只读内层头；
        // 公签 d/o.java:83-91 socket 模式只写数据本身；vivo ConnectSocket.java:169-205
        // 只写 CarlifeCmdMessage+data）——外层 [channel][len] 是 USB AOA 专属，
        // 原来每帧套外层 → 对真盒子/车机首帧即错位
        val body = if (USE_INNER_FRAME) {
            CarlifeFrame.inner(serviceType, secured, channel)
        } else secured
        enqueue(body, channel)
    }

    /**
     * 数据通道裸写（权威 ConnectSocket.writeData/AoaWriteTransport 实证）：
     * 音频/视频数据帧 = [12B PCM/视频头][data]，**不套** CarlifeCmdMessage 内层头。
     * 此前把 inner() 套在数据帧上 → 盒子按裸格式解必错。
     */
    fun sendRawData(channel: Int, head12: ByteArray, data: ByteArray) {
        require(head12.size == 12) { "data head must be 12B" }
        // TCP：12B 业务头+data 直写（外层头仅 AOA；权威 ConnectSocket.writeData 实证）
        val frame = ByteArray(12 + data.size)
        head12.copyInto(frame, 0)
        data.copyInto(frame, 12)
        enqueue(frame, channel)
    }

    /** 102401 改帧宽高（BoxConnectClient.java:178-186 权威：1=w,2=h,3=frameRate=10） */
    fun sendFrameWH(width: Int, height: Int) {
        send(CarlifeFrame.CmdId.BOX_CHANGE_FRAME_WH, com.seagull.boxcast.proto.Wire.Writer()
            .int32(1, width).int32(2, height).int32(3, 10).toByteArray())
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
            CarlifeFrame.inner(serviceType, meta, channel)   // 双格式
        } else meta
        // TCP：内层头+raw 直写（外层头仅 AOA）
        enqueue(body + raw, channel)
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
        // 入站心跳监测（权威 ConnectManager.java:592-620：视频通道 12B 头
        // serviceType==131074，6s 超时断链）
        hbJob = scope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(6000)
                if (System.currentTimeMillis() - lastVideoHeartbeat > 6000 &&
                    state.value is State.Authorized) {
                    Log.e(TAG, "video heartbeat timeout >6s → close")
                    close()
                    break
                }
            }
        }
    }

    /** 最近一次视频心跳（131074）时间戳；handleFrame 里收到就刷新 */
    @Volatile private var lastVideoHeartbeat = 0L

    // ---------- 关闭 ----------

    @Synchronized
    fun close() {
        val wasServer = lastHost == "server" && serverSockets.isNotEmpty()
        writeActive = false
        writeQueue.clear()
        runCatching { writeThread?.interrupt() }
        runCatching { socket?.close() }
        runCatching { out?.close() }
        socket = null; out = null; reader = null
        // 服务端模式：6 个 Accept/读循环必须停（否则 close 后继续跑并重填 serverSockets）
        runCatching { serverJob?.cancel() }
        serverSockets.values.forEach { runCatching { it.close() } }
        serverSockets.clear()
        hbJob?.cancel(); readJob?.cancel()
        encryptHandshake.onDisconnection()
        _state.value = State.Closed
        // 服务端模式不 scheduleReconnect（lastHost="server" 是假 host，DNS 永远失败 → 30s 空转）
        // 且服务端模式下任一条读循环结束就 close 会把 6 连接全关（误杀）
        if (autoReconnect && !wasServer) scheduleReconnect()
    }

    /** 退避重连（读循环异常退出/写失败都会走到 close → 这里接住） */
    private fun scheduleReconnect() {
        if (!autoReconnect) return
        val host = lastHost?.takeIf { it != "server" && it.isNotBlank() } ?: return
        synchronized(reconnectLock) {
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
    }

    fun destroy() {
        autoReconnect = false
        reconnectJob?.cancel()
        close()
        scope.cancel()
    }
}
