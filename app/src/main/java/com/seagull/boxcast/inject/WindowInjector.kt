package com.seagull.boxcast.inject

import android.app.Activity
import android.util.Log
import android.view.InputEvent
import android.view.MotionEvent
import java.lang.reflect.Method

/**
 * 通道 0：Window.injectInputEvent —— 百度 CarLife+ v8.9.5 实证的无系统签名反控方案。
 *
 * 原版实证（com.baidu.carlife v8.9.5 CarlifeActivity.java:791-797）：
 *   window.setLocalFocus(true, z);
 *   window.injectInputEvent(inputEvent);
 *
 * 特点：
 *   - 零权限：不需要 INJECT_EVENTS（系统级）/ root / 无障碍
 *   - 事件直达该 Window 的 ViewRootImpl 输入队列 → Activity 的 dispatchTouchEvent
 *   - **作用域 = 本 app 自己的 Activity 窗口**。CarLife 模型里"被投屏的就是 app 自己"，
 *     所以注入自己窗口即完成反控
 *
 * 对 BoxCast 的含义：
 *   - 若投屏内容 = BoxCast 自己的全屏 UI（学 CarLife 模型）→ 本通道零权限搞定
 *   - 若要控手机桌面/其他 app → 必须 root(input 命令) 或无障碍(dispatchGesture) 或系统签名
 *
 * hidden API 注意：两方法均为 @UnsupportedAppUsage。targetSdk<29 可直接反射；
 * 高 targetSdk 若被 blocklist 拦截会自动降级到 root/无障碍通道。
 */
class WindowInjector {

    companion object {
        private const val TAG = "WindowInjector"
    }

    private var setLocalFocus: Method? = null
    private var injectInputEvent: Method? = null

    /** 绑定目标 Activity（投屏期间它必须在前台） */
    @Volatile
    var activity: Activity? = null

    val available: Boolean
        get() = activity != null

    fun ensureReady(): Boolean {
        if (injectInputEvent != null) return true
        return runCatching {
            setLocalFocus = android.view.Window::class.java
                .getDeclaredMethod("setLocalFocus", Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }
            injectInputEvent = android.view.Window::class.java
                .getDeclaredMethod("injectInputEvent", InputEvent::class.java)
                .apply { isAccessible = true }
            true
        }.getOrDefault(false)
    }

    /**
     * 注入触摸事件（已映射到手机屏幕坐标）。
     * @param rawEvent 由调用方构造的 MotionEvent（与 BoxCast 窗口同坐标系）
     */
    fun inject(event: MotionEvent): Boolean {
        val act = activity ?: return false
        if (!ensureReady()) return false
        return runCatching {
            val w = act.window
            setLocalFocus?.invoke(w, true, true)      // setLocalFocus(true, true)
            injectInputEvent?.invoke(w, event)        // window.injectInputEvent(event)
            true
        }.getOrDefault(false).also {
            if (!it) Log.w(TAG, "window inject failed (hidden API blocked?), fallback needed")
        }
    }

    /** 按键注入（同样只进本窗口） */
    fun injectKey(event: android.view.KeyEvent): Boolean {
        val act = activity ?: return false
        if (!ensureReady()) return false
        return runCatching {
            val w = act.window
            setLocalFocus?.invoke(w, true, true)
            injectInputEvent?.invoke(w, event)
            true
        }.getOrDefault(false)
    }
}
