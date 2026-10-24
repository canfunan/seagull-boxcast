package com.seagull.boxcast.video

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.seagull.boxcast.R
import com.seagull.boxcast.net.BoxConnection

/**
 * 投屏前台服务：持有 MediaProjection，驱动 H264Encoder + H264Sender。
 *
 * MediaProjection 必须在 Service 启动前由 Activity 授权取得，
 * 通过 [EXTRA_PROJECTION] 传入（MediaProjection 可跨组件传递）。
 */
class ScreenCastService : Service() {

    companion object {
        const val EXTRA_PROJECTION = "projection"
        const val ACTION_START = "com.seagull.boxcast.START_CAST"
        const val ACTION_STOP = "com.seagull.boxcast.STOP_CAST"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_BITRATE = "bitrate"
        const val EXTRA_FPS = "fps"
        private const val CHANNEL_ID = "boxcast"
        private const val NOTIFY_ID = 1
    }

    private var encoder: H264Encoder? = null
    private var sender: H264Sender? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "BoxCast 投屏", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(ch)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFY_ID, NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("BoxCast")
                    .setContentText("正在向盒子投屏")
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setOngoing(true)
                    .build())
                val mp = intent.getParcelableExtra<MediaProjection>(EXTRA_PROJECTION)
                if (mp == null) {
                    stopSelf(); return START_NOT_STICKY
                }
                val dm = getSystemService(DisplayManager::class.java)
                val conn = BoxConnectionHolder.connection ?: run { stopSelf(); return START_NOT_STICKY }

                // 编码参数（UI 可调；默认对齐逆向实证值 832x480@15fps/3.2Mbps）
                val w = intent.getIntExtra(EXTRA_WIDTH, H264Encoder.DEFAULT_WIDTH)
                val h = intent.getIntExtra(EXTRA_HEIGHT, H264Encoder.DEFAULT_HEIGHT)
                val br = intent.getIntExtra(EXTRA_BITRATE, H264Encoder.DEFAULT_BITRATE)
                val fps = intent.getIntExtra(EXTRA_FPS, H264Encoder.DEFAULT_FPS)

                sender = H264Sender(conn).apply {
                    configure(w, h)
                    requestStart(verifyValue = "", supportPFrames = true)
                }
                encoder = H264Encoder(mp, dm) { data, key, pts ->
                    sender?.sendFrame(data, key, pts)
                    // 本地环回：同时喂 PipBoard 远端流槽位（验证解码渲染管线；
                    // 盒子来流协议实测后改为 BoxConnection 分发喂入）
                    streamSource?.feedFrame(data, key)
                }
                encoder?.start(w, h, br, fps)
            }
            ACTION_STOP -> {
                sender?.finish()
                encoder?.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        sender?.finish()
        encoder?.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

/** 简单的全局连接持有（避免 Service 重建丢连接；生产可换 DI） */
object BoxConnectionHolder {
    var connection: BoxConnection? = null

    /** PipBoard 远端流槽位源（BoxStreamSource），由海鸥侧创建后注册 */
    var streamSource: com.seagull.boxcast.BoxStreamSource? = null
}
