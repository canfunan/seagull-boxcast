package com.seagull.boxcast.inject

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.hardware.input.InputManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.InputEvent
import android.view.MotionEvent
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.lang.reflect.Method
import kotlin.math.abs

/**
 * 反控注入器：三条通道自动降级。
 *
 * 1) [Reflect]  反射 InputManager.injectInputEvent —— 原版 vivo 百度互联路线
 *              （f/q.java 实证）。需要 INJECT_EVENTS 权限（系统签名/root 提权后可用）。
 *              零延迟、支持完整手势流（DOWN/MOVE/UP 连续事件）。
 * 2) [RootShell] root 执行 `input tap/swipe/keyevent` —— 本工程 root 路线主通道。
 *              连续 MOVE 会被聚合成 swipe（系统命令限制，非逐帧）。
 * 3) [Accessibility] AccessibilityService.dispatchGesture —— 无 root 兜底。
 *
 * 触摸语义对齐 2021 f/y.java：盒子/车机坐标 → 手机屏幕线性映射。
 */
class TouchInjector(private val appContext: Context) {

    companion object {
        private const val TAG = "TouchInjector"
        private const val CLICK_SLOP = 12          // px：小于此位移视为点击
        private const val GESTURE_TIMEOUT = 800L
    }

    enum class Mode { NONE, REFLECT, WINDOW_API, ROOT_SHELL, ACCESSIBILITY }

    @Volatile var mode: Mode = Mode.NONE
        private set

    /** 屏幕尺寸（盒子坐标映射目标） */
    @Volatile var screenWidth = 1080
    @Volatile var screenHeight = 2400

    /** 盒子侧画面尺寸（Hello 协商/102401 得来，默认 1080p 车机） */
    @Volatile var boxWidth = 1920
    @Volatile var boxHeight = 1080

    // ---------- 通道 1：反射 ----------

    private var inputManager: InputManager? = null
    private var injectMethod: Method? = null

    private fun initReflect(): Boolean {
        if (injectMethod != null) return true
        return runCatching {
            val getInstance = InputManager::class.java.getDeclaredMethod("getInstance")
            getInstance.isAccessible = true
            inputManager = getInstance.invoke(null) as InputManager
            injectMethod = InputManager::class.java
                .getDeclaredMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
            true
        }.getOrDefault(false)
    }

    // ---------- 通道 2：root shell ----------

    private var suProcess: Process? = null
    private var suOut: DataOutputStream? = null

    private val rootAvailable: Boolean by lazy {
        runCatching {
            val p = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(p.outputStream)
            os.writeBytes("id\n")
            os.flush()
            val r = BufferedReader(InputStreamReader(p.inputStream)).readLine() ?: ""
            suProcess = p; suOut = os
            r.contains("uid=0")
        }.getOrDefault(false)
    }

    // ---------- 通道 0b：Window.injectInputEvent（CarLife+ 实证，零权限） ----------

    val windowInjector = WindowInjector()

    // ---------- 通道 3：无障碍 ----------

    var accessibilityProvider: (() -> AccessibilityService?)? = null

    // ---------- 初始化（自动选路） ----------

    /**
     * 按 反射 → root → 无障碍 顺序探测可用通道。
     * @param preferReflect 有系统签名时传 true 优先反射
     */
    fun init(preferReflect: Boolean = false): Mode {
        if (preferReflect && initReflect()) {
            mode = Mode.REFLECT
            Log.i(TAG, "inject mode: REFLECT (injectInputEvent)")
            return mode
        }
        // 通道 0（CarLife+ v8.9.5 实证）：Window.injectInputEvent，零权限，完整手势流
        if (windowInjector.ensureReady()) {
            mode = Mode.WINDOW_API
            Log.i(TAG, "inject mode: WINDOW_API (Window.injectInputEvent, 零权限)")
            return mode
        }
        if (rootAvailable) {
            mode = Mode.ROOT_SHELL
            Log.i(TAG, "inject mode: ROOT_SHELL (input cmd)")
            return mode
        }
        mode = Mode.ACCESSIBILITY
        Log.i(TAG, "inject mode: ACCESSIBILITY (dispatchGesture)")
        return mode
    }

    /** 无障碍服务连接后调用 */
    fun onAccessibilityConnected() {
        if (mode == Mode.NONE || mode == Mode.ACCESSIBILITY) {
            mode = Mode.ACCESSIBILITY
        }
    }

    // ---------- 事件入口 ----------

    private var downX = 0f
    private var downY = 0f
    private var tracking = false

    /**
     * 注入一个触摸事件（盒子坐标系，内部映射到手机屏幕）。
     */
    fun inject(t: TouchProto.TouchAction) {
        val sx = t.x.toFloat() / boxWidth * screenWidth
        val sy = t.y.toFloat() / boxHeight * screenHeight
        when (t.action) {
            TouchProto.ACTION_DOWN -> {
                downX = sx; downY = sy; tracking = true
                injectPoint(sx, sy, MotionEvent.ACTION_DOWN)
            }
            TouchProto.ACTION_MOVE -> {
                if (tracking) injectPoint(sx, sy, MotionEvent.ACTION_MOVE)
            }
            TouchProto.ACTION_UP -> {
                if (tracking) {
                    injectPoint(sx, sy, MotionEvent.ACTION_UP)
                    // root/无障碍通道：聚合为 swipe（非点击时）
                    tracking = false
                }
            }
        }
    }

    /** 硬按键注入（CarlifeCarHardKeyCode → Android KeyEvent 码） */
    fun injectKey(keycode: Int) {
        val androidKey = mapCarKey(keycode)
        when (mode) {
            Mode.REFLECT -> injectKeyReflect(androidKey)
            Mode.WINDOW_API -> {
                val now = SystemClock.uptimeMillis()
                windowInjector.injectKey(
                    android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, androidKey, 0))
                windowInjector.injectKey(
                    android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_UP, androidKey, 0))
            }
            Mode.ROOT_SHELL -> rootCmd("input keyevent $androidKey")
            Mode.ACCESSIBILITY -> globalAction(mapCarKeyGlobal(keycode))
            Mode.NONE -> {}
        }
    }

    // ---------- 反射实现（完整手势流） ----------

    private fun injectPoint(x: Float, y: Float, action: Int) {
        if (mode == Mode.REFLECT) {
            val now = SystemClock.uptimeMillis()
            val ev = MotionEvent.obtain(now, now, action, x, y, 0)
            ev.setSource(4098)   // 0x1002 SOURCE_TOUCHSCREEN|CLASS_POINTER，f/y.java 实证
            runCatching { injectMethod?.invoke(inputManager, ev, 0) }
                .onFailure { Log.e(TAG, "inject fail, fallback: ${it.message}"); downgrade() }
            ev.recycle()
        } else if (mode == Mode.WINDOW_API) {
            // Window.injectInputEvent：完整手势流（DOWN/MOVE/UP 逐帧直达本窗口）
            val now = SystemClock.uptimeMillis()
            val ev = MotionEvent.obtain(now, now, action, x, y, 0)
            ev.setSource(4098)
            // Window/ViewRootImpl 非线程安全：读循环在 IO 线程，必须回主线程投递
            val act = windowInjector.activity
            if (act != null) {
                act.runOnUiThread {
                    if (!windowInjector.inject(ev)) {
                        Log.w(TAG, "window inject fail, downgrade")
                        downgrade()
                    }
                }
            } else {
                Log.w(TAG, "no target activity for WINDOW_API, downgrade")
                downgrade()
            }
            // 不 recycle：注入队列可能异步持有 event（原版 f/y.java 同样不回收）
        } else if (mode == Mode.ROOT_SHELL) {
            // input 命令无流式事件：DOWN 记录、UP 时按点击/滑动一次性下发
            if (action == MotionEvent.ACTION_UP) {
                val moved = abs(x - downX) > CLICK_SLOP || abs(y - downY) > CLICK_SLOP
                if (moved) rootCmd("input swipe ${downX.toInt()} ${downY.toInt()} ${x.toInt()} ${y.toInt()} 120")
                else rootCmd("input tap ${x.toInt()} ${y.toInt()}")
            }
        } else if (mode == Mode.ACCESSIBILITY) {
            if (action == MotionEvent.ACTION_UP) {
                val moved = abs(x - downX) > CLICK_SLOP || abs(y - downY) > CLICK_SLOP
                dispatchAccessibility(downX, downY, x, y, moved)
            }
        }
    }

    private fun injectKeyReflect(androidKey: Int) {
        val now = SystemClock.uptimeMillis()
        runCatching {
            val down = android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, androidKey, 0)
            val up = android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_UP, androidKey, 0)
            injectMethod?.invoke(inputManager, down, 0)
            injectMethod?.invoke(inputManager, up, 0)
        }
    }

    // ---------- root 实现 ----------

    private fun rootCmd(cmd: String) {
        runCatching { suOut?.apply { writeBytes("$cmd\n"); flush() } }
            .onFailure { Log.e(TAG, "root cmd fail: ${it.message}"); downgrade() }
    }

    // ---------- 无障碍实现 ----------

    private fun dispatchAccessibility(x1: Float, y1: Float, x2: Float, y2: Float, moved: Boolean) {
        val svc = accessibilityProvider?.invoke() ?: run {
            Log.w(TAG, "accessibility service not connected"); return
        }
        val path = Path().apply {
            moveTo(x1, y1)
            if (moved) lineTo(x2, y2)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, if (moved) 300L else 50L))
            .build()
        runCatching {
            svc.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) {}
                override fun onCancelled(d: GestureDescription?) {
                    Log.w(TAG, "gesture cancelled")
                }
            }, null)
        }.onFailure { Log.e(TAG, "dispatchGesture fail", it) }
    }

    private fun globalAction(action: Int) {
        runCatching { accessibilityProvider?.invoke()?.performGlobalAction(action) }
    }

    // ---------- 按键映射 ----------

    /**
     * CarLife 车机按键码（ServiceTypes 实证）→ Android KeyEvent 码。
     * KEYCODE_HOME=1 / HFP=5 / MEDIA=9 / NAV=11 / MODE=13 / BACK=14 / VR_START / SRC / TEL / SETTING
     */
    private fun mapCarKey(carKey: Int): Int = when (carKey) {
        1 -> android.view.KeyEvent.KEYCODE_HOME
        5 -> android.view.KeyEvent.KEYCODE_CALL            // HFP 通话
        9 -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        11 -> android.view.KeyEvent.KEYCODE_NAVIGATION     // NAV→地图
        13 -> android.view.KeyEvent.KEYCODE_MENU           // MODE
        14 -> android.view.KeyEvent.KEYCODE_BACK
        19 -> android.view.KeyEvent.KEYCODE_VOLUME_MUTE
        31 -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY
        32 -> android.view.KeyEvent.KEYCODE_MEDIA_STOP
        45 -> android.view.KeyEvent.KEYCODE_STAR
        46 -> android.view.KeyEvent.KEYCODE_POUND
        47 -> android.view.KeyEvent.KEYCODE_DEL
        in 35..44 -> android.view.KeyEvent.KEYCODE_0 + (carKey - 35)
        else -> android.view.KeyEvent.KEYCODE_UNKNOWN
    }

    private fun mapCarKeyGlobal(carKey: Int): Int = when (carKey) {
        1 -> AccessibilityService.GLOBAL_ACTION_HOME
        14 -> AccessibilityService.GLOBAL_ACTION_BACK
        5 -> AccessibilityService.GLOBAL_ACTION_RECENTS
        else -> AccessibilityService.GLOBAL_ACTION_BACK
    }

    // ---------- 降级 ----------

    private fun downgrade() {
        Log.w(TAG, "downgrade inject mode from $mode")
        when (mode) {
            Mode.REFLECT -> mode = Mode.WINDOW_API
            Mode.WINDOW_API -> mode = Mode.ROOT_SHELL
            Mode.ROOT_SHELL -> mode = Mode.ACCESSIBILITY
            else -> {}
        }
    }

    /** 无障碍服务是否已在系统设置中启用 */
    fun isAccessibilityEnabled(serviceClass: Class<*>): Boolean = runCatching {
        val expect = "${appContext.packageName}/${serviceClass.name}"
        val enabled = Settings.Secure.getString(
            appContext.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        enabled.split(":").any { it.equals(expect, ignoreCase = true) }
    }.getOrDefault(false)

    fun openAccessibilitySettings() {
        appContext.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun release() {
        runCatching { suOut?.close(); suProcess?.destroy() }
    }
}
