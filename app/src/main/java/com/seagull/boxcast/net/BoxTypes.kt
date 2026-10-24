package com.seagull.boxcast.net

/**
 * 盒子家族表（BoxConnectClient.java 实证）。
 *
 * BOX_TYPE_* 为 Hello.type 的取值；OTA 地址为各盒子固件页。
 * 耘电另有产品代际：BOX_VERSION_YUNDIAN_1="1.0.0" / _2="2.0.0"。
 */
object BoxTypes {
    const val TYPE_YUNDIAN = "yundian"
    const val TYPE_JUNYONG = "junyong"
    const val TYPE_LIAOYUAN = "liaoyuan"
    const val TYPE_LIAOYUAN_V2 = "liaoyuan-v2"
    const val TYPE_SUDING = "suding"
    const val TYPE_NONE = "none"
    const val TYPE_UNKNOW = "unknow"

    const val VERSION_YUNDIAN_1 = "1.0.0"
    const val VERSION_YUNDIAN_2 = "2.0.0"

    /** OTA 地址（BoxConnectClient 实证） */
    val OTA = mapOf(
        TYPE_YUNDIAN to "http://192.168.0.1/",
        TYPE_JUNYONG to "http://192.168.50.2/#/",
        TYPE_LIAOYUAN to "http://192.168.1.101/",
        TYPE_LIAOYUAN_V2 to "http://192.168.1.101/",
        TYPE_SUDING to "http://192.168.1.101/",
    )

    /**
     * 授权状态（ConnectManager 实证）：
     * IS_NOT_BOX=-1 / IS_BOX_NOT_AUTHORIZED=0 / IS_BOX_NOT_ACTIVATED=1 /
     * IS_BOX_VERSION_TOO_OLD=2 / IS_BOX_AND_AUTHORIZED=3
     * 连接标记 CONNECTED_BY_BOX=16，setConnectType(9)=盒子模式
     */
    const val IS_NOT_BOX = -1
    const val IS_BOX_NOT_AUTHORIZED = 0
    const val IS_BOX_NOT_ACTIVATED = 1
    const val IS_BOX_VERSION_TOO_OLD = 2
    const val IS_BOX_AND_AUTHORIZED = 3

    const val CONNECTED_BY_BOX = 16
    const val CONNECT_TYPE_BOX = 9
}
