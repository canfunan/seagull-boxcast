package com.seagull.boxcast

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.seagull.boxcast.inject.CastAccessibilityService
import com.seagull.boxcast.inject.InjectorRegistry
import com.seagull.boxcast.inject.TouchInjector
import com.seagull.boxcast.net.BoxConnection
import com.seagull.boxcast.net.BoxDiscovery
import com.seagull.boxcast.net.BoxOta
import com.seagull.boxcast.net.BoxTypes
import com.seagull.boxcast.proto.BoxCheckUpdate
import com.seagull.boxcast.video.BoxConnectionHolder
import com.seagull.boxcast.video.H264Encoder
import com.seagull.boxcast.video.ScreenCastService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var discovery: BoxDiscovery
    private var connection: BoxConnection? = null
    private var injector: TouchInjector? = null

    // 编码参数（UI 可调）
    private var encWidth = H264Encoder.DEFAULT_WIDTH
    private var encHeight = H264Encoder.DEFAULT_HEIGHT
    private var encBitrate = H264Encoder.DEFAULT_BITRATE
    private var encFps = H264Encoder.DEFAULT_FPS

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val mp: android.media.projection.MediaProjection? =
                getSystemService(MediaProjectionManager::class.java)
                    .getMediaProjection(result.resultCode, result.data!!)
            val intent = Intent(this, ScreenCastService::class.java).apply {
                action = ScreenCastService.ACTION_START
                putExtra(ScreenCastService.EXTRA_PROJECTION, mp)
                putExtra(ScreenCastService.EXTRA_WIDTH, encWidth)
                putExtra(ScreenCastService.EXTRA_HEIGHT, encHeight)
                putExtra(ScreenCastService.EXTRA_BITRATE, encBitrate)
                putExtra(ScreenCastService.EXTRA_FPS, encFps)
            }
            startForegroundService(intent)
        }
    }

    /** 运行时权限（位置=WiFi/网段信息、BLE、通知） */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* 结果不影响主流程，发现模块按权限降级 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        discovery = BoxDiscovery(applicationContext)
        connection = BoxConnection(applicationContext).also { BoxConnectionHolder.connection = it }

        // 反控注入器（root/反射/无障碍 三通道自动降级）
        injector = TouchInjector(applicationContext).also { inj ->
            InjectorRegistry.injector = inj
            inj.accessibilityProvider = { CastAccessibilityService.instance }
            // Window.injectInputEvent 通道：绑定本 Activity 为反控目标窗口（零权限）
            inj.windowInjector.activity = this@MainActivity
            val dm = getSystemService(android.hardware.display.DisplayManager::class.java)
            dm.getDisplay(0)?.let { d ->
                val m = android.util.DisplayMetrics()
                d.getRealMetrics(m)
                inj.screenWidth = m.widthPixels
                inj.screenHeight = m.heightPixels
            }
        }
        requestRuntimePermissions()

        setContent {
            MaterialTheme {
                MainScreen(
                    discovery = discovery,
                    connection = connection!!,
                    injector = injector!!,
                    onParamsChanged = { w, h, br, fps ->
                        encWidth = w; encHeight = h; encBitrate = br; encFps = fps
                    },
                    onRequestProjection = {
                        val mpm = getSystemService(MediaProjectionManager::class.java)
                        projectionLauncher.launch(mpm.createScreenCaptureIntent())
                    },
                    onStopCast = {
                        startService(Intent(this, ScreenCastService::class.java).apply {
                            action = ScreenCastService.ACTION_STOP
                        })
                    }
                )
            }
        }
    }

    private fun requestRuntimePermissions() {
        val need = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            need += Manifest.permission.BLUETOOTH_SCAN
            need += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            need += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = need.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    override fun onDestroy() {
        super.onDestroy()
        discovery.clear()
        connection?.destroy()   // 停止自动重连 + 释放协程（否则 Activity 退了还在重连）
    }
}

/** 分辨率预设（对齐盒子常见规格；宽高须为 16 对齐避免编码器拒绝） */
private val RES_PRESETS = listOf(
    Triple(832, 480, "832x480（逆向默认）"),
    Triple(640, 360, "640x360（流畅）"),
    Triple(1024, 600, "1024x600"),
    Triple(1280, 720, "1280x720（720p）"),
    Triple(1920, 1080, "1920x1080（1080p）"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    discovery: BoxDiscovery,
    connection: BoxConnection,
    injector: TouchInjector,
    onParamsChanged: (Int, Int, Int, Int) -> Unit,
    onRequestProjection: () -> Unit,
    onStopCast: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val boxes by discovery.boxes.collectAsState()
    val state by connection.state.collectAsState()
    val logs = remember { mutableStateListOf<String>() }

    // 编码参数状态
    var w by remember { mutableIntStateOf(H264Encoder.DEFAULT_WIDTH) }
    var h by remember { mutableIntStateOf(H264Encoder.DEFAULT_HEIGHT) }
    var bitrate by remember { mutableIntStateOf(H264Encoder.DEFAULT_BITRATE) }
    var fps by remember { mutableIntStateOf(H264Encoder.DEFAULT_FPS) }

    fun log(s: String) { logs.add(0, s); if (logs.size > 100) logs.removeLast() }

    LaunchedEffect(w, h, bitrate, fps) {
        onParamsChanged(w, h, bitrate, fps)
    }

    LaunchedEffect(Unit) {
        discovery.startUdpListen()
        discovery.probeKnownNetworks()
    }

    Scaffold(topBar = { TopAppBar(title = { Text("BoxCast · 百度互联盒子投屏") }) }) { pad ->
        Column(
            Modifier.padding(pad).padding(12.dp).fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {

            Text("1. 发现盒子（BLE / UDP / 已知网段探测）", fontWeight = FontWeight.Bold)
            Row {
                Button(onClick = {
                    scope.launch { discovery.sendUdpProbe(); discovery.probeKnownNetworks() }
                }) { Text("重新探测") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { discovery.startBleScan() }) { Text("BLE 扫描") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { connection?.startServer() }) { Text("服务端模式") }
            }
            Text("服务端模式：手机监听 7240/8240/9240/9241/9242/9340 等盒子主动连（真机日志实证）",
                fontSize = 11.sp)
            LazyColumn(Modifier.heightIn(max = 120.dp)) {
                items(boxes) { b ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), onClick = {
                        log("连接 ${b.host} ...")
                        connection.connect(b.host)
                    }) {
                        Column(Modifier.padding(8.dp)) {
                            Text("${b.name}  via=${b.via}", fontSize = 14.sp)
                            Text("${b.host}${b.dataPort?.let { ":$it" } ?: ""}", fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace)
                            b.credentials?.let { c ->
                                Text("凭据 ssid=${c.ssid} pwd=${c.password} freq=${c.frequency ?: "-"}",
                                    fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text("2. 连接状态", fontWeight = FontWeight.Bold)
            val stateText = when (val s = state) {
                is BoxConnection.State.Idle -> "空闲"
                is BoxConnection.State.Connecting -> "连接中 ${s.host}:${s.port}…"
                is BoxConnection.State.HelloReceived -> "收到 Hello: type=${s.hello.type} mac=${s.hello.mac}"
                is BoxConnection.State.Authorized -> {
                    val codeName = when (s.code) {
                        BoxTypes.IS_BOX_AND_AUTHORIZED -> "已授权(3)"
                        BoxTypes.IS_BOX_NOT_AUTHORIZED -> "tag 不符(0)"
                        BoxTypes.IS_BOX_NOT_ACTIVATED -> "未激活(1)"
                        BoxTypes.IS_BOX_VERSION_TOO_OLD -> "版本过旧(2)"
                        else -> "非盒子(-1)"
                    }
                    "授权: $codeName  type=${s.hello.type} ver=${s.hello.boxVersion}"
                }
                is BoxConnection.State.Failed -> "失败: ${s.reason}"
                is BoxConnection.State.Closed -> "已关闭"
                else -> ""
            }
            Text(stateText, fontSize = 13.sp)

            Spacer(Modifier.height(8.dp))
            Text("3. 投屏参数（分辨率 / 码率 / 帧率可调）", fontWeight = FontWeight.Bold)

            // 分辨率预设
            Text("分辨率：${w}x${h}", fontSize = 13.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                RES_PRESETS.forEach { (pw, ph, label) ->
                    FilterChip(
                        selected = w == pw && h == ph,
                        onClick = { w = pw; h = ph },
                        label = { Text(label.substringAfter("（").substringBefore("）").ifEmpty { label }, fontSize = 10.sp) }
                    )
                }
            }

            // 码率（Mbps）
            Text("码率：${bitrate / 1_000_000}.${(bitrate % 1_000_000) / 100_000} Mbps", fontSize = 13.sp)
            Slider(
                value = bitrate.toFloat(),
                onValueChange = { bitrate = (it / 500_000).toInt() * 500_000 },  // 0.5Mbps 步进
                valueRange = 1_000_000f..20_000_000f
            )

            // 帧率
            Text("帧率：$fps fps", fontSize = 13.sp)
            Slider(
                value = fps.toFloat(),
                onValueChange = { fps = it.toInt() },
                valueRange = 5f..60f,
                steps = 54
            )

            Text(
                "注意：FLAG_SECURE 安全窗口（支付页/DRM 视频）系统级截屏为黑，任何投屏应用均如此",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.error
            )

            Spacer(Modifier.height(8.dp))
            Text("4. 反控（车机操作手机屏幕）", fontWeight = FontWeight.Bold)

            var injMode by remember { mutableStateOf(TouchInjector.Mode.NONE) }
            var injInit by remember { mutableStateOf(false) }

            LaunchedEffect(injInit) {
                if (injInit && injMode == TouchInjector.Mode.NONE) {
                    val m = withContext(Dispatchers.IO) { injector.init(preferReflect = false) }
                    injMode = m
                }
            }

            val modeText = when (injMode) {
                TouchInjector.Mode.REFLECT -> "反射 injectInputEvent（系统签名，控全系统）"
                TouchInjector.Mode.WINDOW_API -> "Window.injectInputEvent（零权限，CarLife+ 同款，控 BoxCast 窗口）"
                TouchInjector.Mode.ROOT_SHELL -> "root input 命令（控全系统，滑动整段）"
                TouchInjector.Mode.ACCESSIBILITY -> "无障碍 dispatchGesture（控全系统，需设置开启）"
                TouchInjector.Mode.NONE -> "未初始化"
            }
            Text("注入通道：$modeText", fontSize = 13.sp)

            Row {
                Button(onClick = { injInit = true; injMode = TouchInjector.Mode.NONE }) {
                    Text("初始化反控")
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { injector.openAccessibilitySettings() }) {
                    Text("开启无障碍")
                }
            }
            Text(
                "车机触摸(425985)/硬按键(425992) → 本机注入。坐标 ${injector.boxWidth}x${injector.boxHeight}" +
                    " → ${injector.screenWidth}x${injector.screenHeight}",
                fontSize = 11.sp
            )

            Spacer(Modifier.height(8.dp))
            Text("5. 内置桌面（海鸥 HMI）", fontWeight = FontWeight.Bold)
            Row {
                Button(onClick = { com.seagull.boxcast.BoxCastBridge.openDesktop(ctx) }) {
                    Text("打开海鸥桌面")
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { com.seagull.boxcast.BoxCastBridge.openPipBoard(ctx) }) {
                    Text("画中画板")
                }
            }
            Text(
                "海鸥桌面即被投屏 HMI：车机触摸经 Window.injectInputEvent 直达桌面窗口（零权限）。" +
                    "画中画板挂第三方 app（VD）或远端流槽位。",
                fontSize = 11.sp
            )

            Spacer(Modifier.height(8.dp))
            Text("6. 控制", fontWeight = FontWeight.Bold)
            Row {
                Button(
                    // 授权码仅展示/日志：原版收到 Hello 即继续 init BoxH264Manager（tag 不符也投），
                    // 我们同样放行——算法若有偏差不至于卡死（CarlifeConnectHsgHandler:772-780 实证）
                    enabled = state is BoxConnection.State.Authorized,
                    onClick = onRequestProjection
                ) { Text("开始投屏") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onStopCast) { Text("停止") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    connection.otaAddress()?.let { addr ->
                        scope.launch {
                            val r = BoxOta.check(BoxCheckUpdate(
                                otaAddress = addr, httpType = 1, responseKey = "version"))
                            log("OTA($addr): ${r.version ?: r.raw ?: r.error}")
                        }
                    }
                }) { Text("OTA 检查") }
            }

            Spacer(Modifier.height(8.dp))
            Text("日志", fontWeight = FontWeight.Bold)
            LazyColumn(Modifier.heightIn(min = 120.dp)) {
                items(logs) { l -> Text(l, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
            }
        }
    }
}
