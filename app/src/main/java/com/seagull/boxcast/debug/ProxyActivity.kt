package com.seagull.boxcast.debug

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import com.seagull.boxcast.video.BoxConnectionHolder
import com.seagull.boxcast.video.ScreenCastService

/**
 * 透明授权代理 Activity——MediaProjection 授权的专职入口。
 *
 * 为什么需要它：HTTP 调试接口的 /cast/start 在 Service 线程触发，
 * 直接调 MainActivity 的 ActivityResultLauncher 会遇到
 * "unregistered launcher"（Activity 重建/未就绪）——静默失败。
 * ProxyActivity 独立生命周期：被拉起 → 立刻发起授权 → 收结果 →
 * 交给 ScreenCastService → 自己透明退出。HTTP/按钮两条路都汇到它。
 */
class ProxyActivity : Activity() {

    companion object {
        private const val REQ = 0x9CA5
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 必须挂个 1px view：纯透明 Activity 无窗口，某些 ROM 不会真正创建它
        window.setContentView(android.view.View(this))
        overridePendingTransition(0, 0)
        // 清掉已死的 VD 缓存（CompositionEngine -19 = ENODEV 实证）
        BoxConnectionHolder.virtualDisplay?.let { runCatching { it.release() } }
        BoxConnectionHolder.virtualDisplay = null
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ) {
            if (resultCode == RESULT_OK && data != null) {
                // 授权结果入 Holder（Service 内 FGS 起后再 getMediaProjection，Android 14+ 顺序）
                BoxConnectionHolder.projectionResultCode = resultCode
                BoxConnectionHolder.projectionData = data
                val svc = Intent(this, ScreenCastService::class.java).apply {
                    action = ScreenCastService.ACTION_START
                    putExtra(ScreenCastService.EXTRA_WIDTH, 832)
                    putExtra(ScreenCastService.EXTRA_HEIGHT, 480)
                    putExtra(ScreenCastService.EXTRA_BITRATE, 3_276_800)
                    putExtra(ScreenCastService.EXTRA_FPS, 30)
                }
                startForegroundService(svc)
            }
            finish()
            overridePendingTransition(0, 0)
        }
    }
}
