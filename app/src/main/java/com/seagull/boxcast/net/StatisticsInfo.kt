package com.seagull.boxcast.net

import com.seagull.boxcast.proto.Wire

/**
 * 连接统计（CarlifeStatisticsInfoProto.proto 权威 10 字段）。
 *
 * 双向：版本匹配后手机上报（handleProtocolVersionMatch 实证）；
 * HU 也会发（CarlifeConnectHsgHandler:473 "渠道号认证"）——**carNumber/carType
 * 是车型渠道（VehicleChannels 2\d{7}）匹配的数据源**。
 */
object StatisticsInfo {

    data class Info(
        val cuid: String = "",
        val versionName: String = "",
        val versionCode: Int = 0,
        val channel: String = "",
        val connectCount: Int = 0,
        val connectSuccessCount: Int = 0,
        val connectTime: Int = 0,
        val crashLog: String = "",
        val carNumber: String = "",
        val carType: String = "",
    )

    fun encode(i: Info): ByteArray = Wire.Writer().apply {
        string(1, i.cuid)
        string(2, i.versionName)
        int32(3, i.versionCode)
        string(4, i.channel)
        int32(5, i.connectCount)
        int32(6, i.connectSuccessCount)
        int32(7, i.connectTime)
        string(8, i.crashLog)
        string(9, i.carNumber)
        string(10, i.carType)
    }.toByteArray()

    fun decode(data: ByteArray): Info = Wire.Reader(data).run {
        var m = Info()
        while (!atEnd()) {
            val (f, w) = readTag()
            when (f) {
                1 -> m = m.copy(cuid = readString())
                2 -> m = m.copy(versionName = readString())
                3 -> m = m.copy(versionCode = readInt32())
                4 -> m = m.copy(channel = readString())
                5 -> m = m.copy(connectCount = readInt32())
                6 -> m = m.copy(connectSuccessCount = readInt32())
                7 -> m = m.copy(connectTime = readInt32())
                8 -> m = m.copy(crashLog = readString())
                9 -> m = m.copy(carNumber = readString())
                10 -> m = m.copy(carType = readString())
                else -> skip(w)
            }
        }
        m
    }
}
