package com.seagull.boxcast.net

import org.json.JSONArray
import org.json.JSONObject

/**
 * 车型参数配置（复刻官方 VideoResolution.getSpecialBitRate/getSpecialFrame + AMIS JSON 格式）。
 *
 * 官方机制（实证）：
 *   AMIS 下发 JSON：[{"cn":"20181100","rate":3276800,"frame":15,"highrate":false}, ...]
 *   连接后 getVechileChannel() 拿到车机自报渠道号 → 按 cn 匹配 → 同时取 rate+frame
 *
 * 本类把同一机制落地为本地表：内置实证默认值，支持运行时解析同格式 JSON 覆盖
 * （你可以把实测最优参数按 cn 写进去，效果与官方云端下发一致）。
 */
object VehicleProfile {

    data class Profile(val bitrate: Int, val frameRate: Int, val highrate: Boolean = false)

    /** 内置表：实证默认（h/g.java：832x480@15fps/3.2Mbps） */
    private val builtin = mapOf(
        VehicleChannels.NORMAL to Profile(3_276_800, 15),
        VehicleChannels.AFTERMARKET to Profile(3_276_800, 15),
        VehicleChannels.PREINSTALLMARKET to Profile(3_276_800, 15),
    )

    /** 运行时覆盖表（parseAmisJson 写入） */
    @Volatile
    private var overrides: Map<String, Profile> = emptyMap()

    /** 取某车型渠道的参数；无匹配回退 NORMAL 默认 */
    fun forChannel(channel: String): Profile =
        overrides[channel] ?: builtin[channel] ?: builtin[VehicleChannels.NORMAL]!!

    /** 解析 AMIS 同格式 JSON（官方 AmisConfigManager.readKey3SpecialBitRate 实证格式） */
    fun parseAmisJson(json: String): Int {
        return runCatching {
            val arr = JSONArray(json)
            val m = HashMap<String, Profile>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val cn = o.optString("cn")
                if (cn.isEmpty()) continue
                val rate = o.optInt("rate")
                val frame = o.optInt("frame")
                val high = o.optBoolean("highrate", false)
                // 官方特例：rate<=0 且 highrate=true → rate=1（高速通道标记）
                val finalRate = if (rate <= 0 && high) 1 else rate
                m[cn] = Profile(finalRate, frame, high)
            }
            overrides = m
            m.size
        }.getOrDefault(0)
    }

    /** 通道中文名（日志/UI） */
    fun channelName(channel: String): String =
        VehicleChannels.NAMES[channel] ?: if (VehicleChannels.isValid(channel)) "渠道$channel" else "未知"
}
