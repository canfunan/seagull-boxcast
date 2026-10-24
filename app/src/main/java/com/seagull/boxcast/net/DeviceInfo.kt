package com.seagull.boxcast.net

import android.os.Build
import com.seagull.boxcast.proto.Wire

/**
 * 设备信息（CarlifeDeviceInfoProto.proto 权威字段序，27 字段）。
 *
 * 两个方向：
 *  1. **MD_INFO(65540)**：连接后手机主动向 HU 上报身份（CarlifeConnectHsgHandler:836 实证）
 *     ——android.os.Build 全字段 + 蓝牙 MAC + CarLife 版本
 *  2. **HU_INFO(98307)**：HU 主动上报车机信息（同 proto）——收到后触发
 *     屏幕变更/重采样开关重协商（CarlifeDeviceInfoManager.setCarDeviceInfo 实证）
 *
 * 字段序（.proto 源文件实证，勿信字母序）：
 *   1=os 2=board 3=bootloader 4=brand 5=cpu_abi 6=cpu_abi2 7=device 8=display
 *   9=fingerprint 10=hardware 11=host 12=cid 13=manufacturer 14=model 15=product
 *   16=serial 17=codename 18=incremental 19=release 20=sdk 21=sdk_int 22=token
 *   23=btaddress 24=carlifeversion 25=carlifeType 26=year 27=carlifeSdkInt
 */
object DeviceInfo {

    /** 本端 CarLife 协议版本标识（MD_INFO 用） */
    const val CARLIFE_VERSION = "8.9.5-boxcast"

    /** 编码 MD_INFO（手机 → HU） */
    fun encodeMdInfo(btAddress: String = ""): ByteArray = Wire.Writer().apply {
        string(1, "Android")                 // os
        string(2, Build.BOARD)
        string(3, Build.BOOTLOADER)
        string(4, Build.BRAND)
        string(5, Build.CPU_ABI)
        string(6, Build.CPU_ABI2 ?: "")
        string(7, Build.DEVICE)
        string(8, Build.DISPLAY)
        string(9, Build.FINGERPRINT)
        string(10, Build.HARDWARE)
        string(11, Build.HOST)
        string(12, Build.ID)
        string(13, Build.MANUFACTURER)
        string(14, Build.MODEL)
        string(15, Build.PRODUCT)
        string(16, Build.SERIAL ?: "unknown")
        string(17, Build.VERSION.CODENAME)
        string(18, Build.VERSION.INCREMENTAL ?: "")
        string(19, Build.VERSION.RELEASE ?: "")
        string(20, Build.VERSION.SDK)
        int32(21, Build.VERSION.SDK_INT)
        string(22, "")                        // token
        string(23, btAddress)                 // btaddress
        string(24, CARLIFE_VERSION)           // carlifeversion
        int32(25, 0)                          // carlifeType
        string(26, "")                        // year
        int32(27, 0)                          // carlifeSdkInt
    }.toByteArray()

    /** 解析 HU_INFO（HU → 手机；屏幕/音频重协商的触发源） */
    fun decode(data: ByteArray): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        Wire.Reader(data).run {
            while (!atEnd()) {
                val (f, w) = readTag()
                if (w == Wire.WIRE_LEN) {
                    val key = when (f) {
                        1 -> "os"; 4 -> "brand"; 7 -> "device"; 13 -> "manufacturer"
                        14 -> "model"; 19 -> "release"; 23 -> "btaddress"
                        24 -> "carlifeversion"; else -> null
                    }
                    if (key != null) m[key] = readString() else skip(w)
                } else {
                    if (f == 21) m["sdk_int"] = readInt32().toString() else skip(w)
                }
            }
        }
        return m
    }
}
