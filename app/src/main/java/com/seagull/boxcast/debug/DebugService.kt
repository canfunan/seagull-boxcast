package com.seagull.boxcast.debug

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * 调试服务保活壳——DebugHttpServer 常驻 + 防 cached freezer 冻结。
 *
 * 实证：App 退后台后进程被 Android cached freezer 冻结，socket 线程无响应
 * （curl 超时）；套一个 dataSync 类型的前台服务即保持可调。
 */
class DebugService : Service() {

    companion object {
        private const val CHANNEL = "boxcast_debug"
        private const val NOTI_ID = 0xDBG
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "BoxCast 调试接口", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("BoxCast 调试接口")
            .setContentText("127.0.0.1:9977（/log /status /server/start /cast/start /mock）")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTI_ID, n)
        }
        DebugHttpServer().start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
    }
}
