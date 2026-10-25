package com.seagull.boxcast.root

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Root 能力内置（替代外部脚本）：
 *
 * 1. **自授权**：启动时检测缺失的运行时/特殊权限，有 su 就直接授予
 *    （pm grant / appops / settings secure——通知监听、无障碍、悬浮窗）
 * 2. **保活**：有 su 时向 /data/adb/service.d 部署守护脚本（KernelSU/Magisk 开机执行）：
 *    进程没了 30s 内拉起 + oom_score_adj=-1000（LMK 免疫）；
 *    App 内再起一个轻量协程定时自查（双保险，service.d 被清时兜底）
 *
 * 无 su 时全部静默跳过（不影响普通使用）。
 */
object RootKeeper {

    private const val TAG = "RootKeeper"
    private const val PKG = "com.seagull.boxcast"

    /** su 全路径（App 进程 PATH 不含 /system/bin，ProcessBuilder("su") 报 No such file） */
    private val SU_PATH = if (java.io.File("/system/bin/su").exists()) "/system/bin/su"
                          else if (java.io.File("/system/xbin/su").exists()) "/system/xbin/su"
                          else "su"

    /** root 能力状态（UI 展示用） */
    data class Status(
        val suAvailable: Boolean = false,
        val setupDone: Boolean = false,
        val keepaliveInstalled: Boolean = false,
        val oomImmune: Boolean = false,
        val accessibilityOn: Boolean = false,
        val notifListenerOn: Boolean = false,
        val dozeWhitelisted: Boolean = false,
    )

    val status = kotlinx.coroutines.flow.MutableStateFlow(Status())

    /** service.d 守护脚本内容（开机执行，常驻循环） */
    private val KEEPALIVE_SCRIPT = """
#!/system/bin/sh
# BoxCast root 保活（App 自动部署，勿手改）
# 卸载自杀（事故复盘：App 删了还拉 = 死亡循环）
pm path $PKG > /dev/null 2>&1 || exit 0
while true; do
  PID=${'$'}(pidof $PKG)
  if [ -z "${'$'}PID" ]; then
    am start -n $PKG/com.seagull.boxcast.MainActivity \
      -a android.intent.action.MAIN -c android.intent.category.LAUNCHER \
      -f 0x10000000 > /dev/null 2>&1
  else
    echo -1000 > /proc/${'$'}PID/oom_score_adj 2>/dev/null
  fi
  pm path $PKG > /dev/null 2>&1 || exit 0
  sleep 30
done
""".trimIndent()

    /** 一次性完成：自授权 + 部署保活（MainActivity.onCreate 调） */
    fun setup(context: Context) {
        if (!hasSu()) {
            Log.i(TAG, "无 root，跳过（自授权/保活走普通路径）")
            status.value = Status()
            return
        }
        Log.i(TAG, "root 可用，开始自授权 + 部署保活")
        status.value = status.value.copy(suAvailable = true)
        grantPermissions()
        grantSpecialOps()
        installKeepAlive()
        // 回读验证（UI 显示真实状态）
        status.value = status.value.copy(
            setupDone = true,
            keepaliveInstalled = File("/data/adb/service.d/seagull-boxcast-keepalive.sh").exists()
                || suOut("ls /data/adb/service.d/ 2>/dev/null | grep seagull").isNotEmpty(),
            accessibilityOn = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )?.contains("CastAccessibilityService") == true,
            notifListenerOn = android.provider.Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            )?.contains("MediaNotificationListener") == true,
        )
    }

    private fun suOut(cmd: String): String = runCatching {
        val p = ProcessBuilder(SU_PATH, "-c", cmd).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readText().trim().also { p.waitFor() }
    }.getOrDefault("")

    fun hasSu(): Boolean = runCatching {
        val p = ProcessBuilder(SU_PATH, "-c", "id").redirectErrorStream(true).start()
        p.waitFor() == 0
    }.getOrDefault(false)

    /** 运行时权限一键授（缺的才授） */
    fun grantPermissions() {
        val perms = listOf(
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.RECORD_AUDIO",
            "android.permission.BLUETOOTH_SCAN",
            "android.permission.BLUETOOTH_CONNECT",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.READ_PHONE_STATE",
            "android.permission.QUERY_ALL_PACKAGES",
        )
        val missing = perms.filter { p ->
            context.checkSelfPermission(p) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            Log.i(TAG, "权限已全授")
            return
        }
        // pm grant 一次只接受一个 permission——逐条授
        missing.forEach { p ->
            su("pm grant $PKG $p") { out ->
                if (out.isNotEmpty()) Log.w(TAG, "grant $p → $out")
            }
        }
        Log.i(TAG, "pm grant 完成: ${missing.size} 项")
    }

    /** 特殊权限：悬浮窗 / 通知监听 / 无障碍（settings secure） */
    fun grantSpecialOps() {
        su("appops set $PKG SYSTEM_ALERT_WINDOW allow") { }
        su("appops set $PKG POST_NOTIFICATION allow") { }
        // 通知监听（HUD 歌曲信息）
        su("settings put secure enabled_notification_listeners " +
            "$PKG/com.seagull.boxcast.net.MediaNotificationListener") { }
        // 无障碍（反控手势注入）
        su("settings put secure enabled_accessibility_services " +
            "$PKG/com.seagull.boxcast.inject.CastAccessibilityService") { }
        su("settings put secure accessibility_enabled 1") { }
        // Doze/待机白名单
        su("dumpsys deviceidle whitelist +$PKG") { }
        su("am set-standby-bucket $PKG active") { }
        // MediaProjection 授权（root 下部分 ROM 可免弹窗；不支持的 ROM 忽略错误走正常弹窗）
        su("appops set $PKG PROJECT_MEDIA allow") { }
        su("appops set $PKG START_FOREGROUND allow") { }
        Log.i(TAG, "特殊权限已配置")
        status.value = status.value.copy(dozeWhitelisted = true)
    }

    /** 部署 service.d 保活脚本（KernelSU/Magisk 开机常驻） */
    fun installKeepAlive() {
        su("mkdir -p /data/adb/service.d") { }
        val script = "/data/adb/service.d/seagull-boxcast-keepalive.sh"
        su("cat > $script <<'SEAGULL_EOF'\n$KEEPALIVE_SCRIPT\nSEAGULL_EOF") { }
        su("chmod 755 $script") { }
        su("ls -la $script") { out -> Log.i(TAG, "保活脚本: $out") }
        // 立即跑一次部署验证（不等重启）
        su("nohup sh $script > /dev/null 2>&1 &") { }
    }

    /** App 内自查协程兜底（service.d 失效/未重启时） */
    fun startInAppGuard() {
        Thread({
            while (true) {
                runCatching {
                    val pid = android.os.Process.myPid()
                    // 自身 oom_score_adj 降到最低（有 root 时；无 root 时 Android 已按前台服务保优先级）
                    su("echo -1000 > /proc/$pid/oom_score_adj 2>/dev/null") { }
                    val adj = runCatching {
                        File("/proc/$pid/oom_score_adj").readText().trim().toIntOrNull()
                    }.getOrNull()
                    if (adj != null && adj <= -900) {
                        status.value = status.value.copy(oomImmune = true)
                    }
                }
                Thread.sleep(60_000)
            }
        }, "RootGuard").apply { isDaemon = true }.start()
    }

    // ---- su 执行封装 ----
    private fun su(cmd: String, onOut: (String) -> Unit) {
        runCatching {
            val p = ProcessBuilder(SU_PATH, "-c", cmd).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            onOut(out)
        }.onFailure { Log.w(TAG, "su 失败: $cmd -> ${it.message}") }
    }

    private lateinit var context: Context
    fun init(ctx: Context) { context = ctx.applicationContext }
}
