package com.seagull.boxcast.inject

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * 反控兜底通道（无 root / 无系统签名时）。
 * 只做手势分发，不读窗口内容（最小权限）。
 */
class CastAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: CastAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        InjectorRegistry.injector?.onAccessibilityConnected()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 反控不需要监听事件；保留空实现
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}

/** 全局注入器注册表（Service ↔ Injector 互持） */
object InjectorRegistry {
    var injector: TouchInjector? = null
}
