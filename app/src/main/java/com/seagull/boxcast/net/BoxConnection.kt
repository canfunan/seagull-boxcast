package com.seagull.boxcast.net

import android.content.Context
import android.util.Log
import com.seagull.boxcast.crypto.VerifyCode
import com.seagull.boxcast.frame.CarlifeFrame
import com.seagull.boxcast.frame.CmdId
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
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

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
    private var readJob: Job? = null
    private var hbJob: Job? = null

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

    val authorizedCode: Int
        get() = (state.value as? State.Authorized)?.code ?: BoxTypes.IS_NOT_BOX

    // ---------- 连接 ----------

    /**
     * 连盒子并按候选端口逐个尝试。
     * @param host 盒子 IP（发现模块给出，如 192.168.0.1）
     */
    fun connect(host: String) {
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
            s.connect(InetSocketAddress(host, port), 4000)
            socket = s
            out = s.getOutputStream()
            reader = FrameReader()
            startReadLoop(s.getInputStream())
            true
        } catch (e: Exception) {
            runCatching { s.close() }
            false
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
        val body = if (USE_INNER_FRAME) {
            parseInner(payload) ?: return
        } else {
            // 裸 protobuf 模式：channel 即 serviceType（联调时按实际调整）
            com.seagull.boxcast.frame.CmdMessage(channel, payload)
        }
        Log.i(TAG, "recv serviceType=${body.serviceType} len=${body.data.size}")
        // 单条消息解析失败不影响连接（畸形包/半包保护）
        runCatching {
            when (body.serviceType) {
                CmdId.BOX_HELLO -> onHello(BoxHello.decode(body.data))
                CmdId.BOX_VEHCILE_STATE -> _vehicleState.value = BoxVehicleState.decode(body.data)
                CmdId.BOX_H264LIST_RESPONSE -> onH264List(BoxH264List.decode(body.data))
                CmdId.BOX_CHECK_UPDATE_RES -> { /* OTA 结果，交给上层解析 */ }
                // 盒子→手机 H.264 点播请求（BoxH264Manager 实证方向）
                CmdId.BOX_H264LIST_REQUEST -> onH264ListRequest?.invoke()
                CmdId.BOX_SEND_H264_REQUEST -> {
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
                CmdId.TOUCH_ACTION ->
                    InjectorRegistry.injector?.inject(TouchProto.TouchAction.decode(body.data))
                CmdId.TOUCH_CAR_HARD_KEY_CODE ->
                    InjectorRegistry.injector?.injectKey(TouchProto.HardKeyCode.decode(body.data).keycode)
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

        // Hello 后起心跳
        startHeartbeat()
    }

    private fun onH264List(list: BoxH264List) {
        Log.i(TAG, "h264 list cnt=${list.cnt} rsCode=${list.rsCode} rsId=${list.rsId} ver=${list.versionCode}")
    }

    // ---------- 发送 ----------

    private fun send(serviceType: Int, payload: ByteArray, channel: Int = CarlifeFrame.Channel.CMD) {
        val body = if (USE_INNER_FRAME) {
            CarlifeFrame.inner(serviceType, payload)
        } else payload
        val frame = CarlifeFrame.outer(channel, body)
        synchronized(this) { out?.write(frame); out?.flush() }
    }

    /** 102401 改帧宽高（sendWH2Box 复刻） */
    fun sendFrameWH(width: Int, height: Int) {
        // 原版走 VideoSettingFragment 的 sendWH2Box；消息体以 102401 定义为准
        send(CmdId.BOX_CHANGE_FRAME_WH, com.seagull.boxcast.proto.Wire.Writer()
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
        synchronized(this) { out?.write(frame); out?.flush() }
    }

    /** 102402 视频心跳 */
    fun sendHeartbeat() {
        send(CmdId.BOX_VIDEO_HEARTBEAT, ByteArray(0))
    }

    /** 102405 请求 H.264 列表 */
    fun requestH264List() {
        send(CmdId.BOX_H264LIST_REQUEST, BoxH264List().encode())
    }

    /** 102416 OTA 检查更新 */
    fun sendCheckUpdate(req: BoxCheckUpdate) {
        send(CmdId.BOX_CHECK_UPDATE_REQ, req.encode())
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
        runCatching { socket?.close() }
        hbJob?.cancel(); readJob?.cancel()
        _state.value = State.Closed
    }

    fun destroy() {
        close()
        scope.cancel()
    }
}
