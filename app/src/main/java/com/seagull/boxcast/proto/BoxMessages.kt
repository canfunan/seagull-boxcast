package com.seagull.boxcast.proto

/**
 * 百度互联无线盒子协议消息（protobuf）。
 *
 * 字段号来源：com.baidu.carlife.protobuf.* 反编译代码（CarlifeBoxHelloProto 等，
 * 见 dex2-src/sources/com/baidu/carlife/protobuf/）。
 *
 * 消息类型 ID（CarLifeCmdMessage serviceType）：见 com.seagull.boxcast.frame.CmdId。
 */

/** 102403 MSG_CMD_BOX_HELLO：盒子上线握手 */
data class BoxHello(
    val isResetResolution: Boolean = false,   // 1
    val type: String = "",                    // 2  盒子类型 yundian/junyong/liaoyuan/suding
    val active: Int = 0,                      // 3  激活状态
    val mac: String = "",                     // 4
    val randomvalue: Int = 0,                 // 5
    val tag: String = "",                     // 6  认证码
    val isHuRate: Boolean = false,            // 7  HU 帧率支持
    val boxVersion: String = "",              // 8  耘电产品版本 1.0.0/2.0.0
    val boxNetAddress: String = "",           // 9
) {
    fun encode(): ByteArray = Wire.Writer().apply {
        bool(1, isResetResolution)
        string(2, type)
        int32(3, active)
        string(4, mac)
        int32(5, randomvalue)
        string(6, tag)
        bool(7, isHuRate)
        string(8, boxVersion)
        string(9, boxNetAddress)
    }.toByteArray()

    companion object {
        fun decode(d: ByteArray): BoxHello = Wire.Reader(d).run {
            var m = BoxHello()
            while (!atEnd()) {
                val (f, w) = readTag()
                when (f) {
                    1 -> m = m.copy(isResetResolution = readBool())
                    2 -> m = m.copy(type = readString())
                    3 -> m = m.copy(active = readInt32())
                    4 -> m = m.copy(mac = readString())
                    5 -> m = m.copy(randomvalue = readInt32())
                    6 -> m = m.copy(tag = readString())
                    7 -> m = m.copy(isHuRate = readBool())
                    8 -> m = m.copy(boxVersion = readString())
                    9 -> m = m.copy(boxNetAddress = readString())
                    else -> skip(w)
                }
            }
            m
        }
    }
}

/** 102418 MSG_CMD_BOX_VEHCILE_STATE：盒子→手机车辆状态 */
data class BoxVehicleState(val connectState: Int = 0) {   // 1
    fun encode(): ByteArray = Wire.Writer().int32(1, connectState).toByteArray()

    companion object {
        fun decode(d: ByteArray): BoxVehicleState = Wire.Reader(d).run {
            var v = 0
            while (!atEnd()) {
                val (f, w) = readTag()
                if (f == 1) v = readInt32() else skip(w)
            }
            BoxVehicleState(v)
        }
    }
}

/**
 * 102404/102405 MSG_CMD_BOX_H264_LIST / H264LIST_REQUEST：
 * 请求/响应盒子上的 H.264 视频列表。
 * 字段来自 CarlifeBoxH264ListProto：1=rsCode, 2=timeStamp(i64), 4=rsType, 5=rsId, 6=cnt, 7=versionCode
 * （rsCode/rsId 呼应 BoxConstants.BOX_RS_CODE=3 / BOX_RS_ID=2）
 */
data class BoxH264List(
    val rsCode: Int = 3,          // 1  对应 BoxConstants.BOX_RS_CODE
    val timeStamp: Long = 0,      // 2
    val rsType: Int = 0,          // 4
    val rsId: Int = 2,            // 5  对应 BoxConstants.BOX_RS_ID
    val cnt: Int = 0,             // 6
    val versionCode: Int = 0,     // 7
) {
    fun encode(): ByteArray = Wire.Writer().apply {
        int32(1, rsCode)
        int64(2, timeStamp)
        int32(4, rsType)
        int32(5, rsId)
        int32(6, cnt)
        int32(7, versionCode)
    }.toByteArray()

    companion object {
        fun decode(d: ByteArray): BoxH264List = Wire.Reader(d).run {
            var m = BoxH264List()
            while (!atEnd()) {
                val (f, w) = readTag()
                when (f) {
                    1 -> m = m.copy(rsCode = readInt32())
                    2 -> m = m.copy(timeStamp = readInt64())
                    4 -> m = m.copy(rsType = readInt32())
                    5 -> m = m.copy(rsId = readInt32())
                    6 -> m = m.copy(cnt = readInt32())
                    7 -> m = m.copy(versionCode = readInt32())
                    else -> skip(w)
                }
            }
            m
        }
    }
}

/**
 * 102406 MSG_CMD_BOX_H264LIST_RESPONSE（H264Init）——confirmH264Init 实证：
 * 1=timeStamp(i32 秒级), 2=verifyValue(str, 硬编码 "hellobox"), 3=supportPFrames(bool)
 */
data class BoxH264Init(
    val timeStamp: Long = 0,          // 1（秒级 int32，varint 编码）
    val verifyValue: String = "hellobox",  // 2 硬编码实证
    val supportPFrames: Boolean = true,    // 3
) {
    fun encode(): ByteArray = Wire.Writer().apply {
        int32(1, timeStamp.toInt())
        string(2, verifyValue)
        bool(3, supportPFrames)
    }.toByteArray()

    companion object {
        fun decode(d: ByteArray): BoxH264Init = Wire.Reader(d).run {
            var m = BoxH264Init()
            while (!atEnd()) {
                val (f, w) = readTag()
                when (f) {
                    1 -> m = m.copy(timeStamp = readInt32().toLong())
                    2 -> m = m.copy(verifyValue = readString())
                    3 -> m = m.copy(supportPFrames = readBool())
                    else -> skip(w)
                }
            }
            m
        }
    }
}

/**
 * 102408/102410 的 SendInfo（CarlifeBoxH264SendInfo）——sendH264Start/sendH264End 实证字段：
 * 1=type(int) 2=frameType(int) 3=width(int) 4=length(int64) 5=verifyValue(str)
 * 6=needPFrames(bool) 7=height(int)
 * 实锤来源：BoxH264Manager.sendH264Start 的 setWidth(3)/setHeight(7)/setType(1)/
 * setLength(4,int64)/setFrameType(2) 调用序。
 */
data class BoxH264Send(
    val type: Int = 0,              // 1
    val frameType: Int = 0,         // 2  帧类型（I/P）
    val width: Int = 0,             // 3
    val length: Long = 0,           // 4  int64！数据长度
    val verifyValue: String = "",   // 5
    val needPFrames: Boolean = false, // 6
    val height: Int = 0,            // 7
) {
    fun encode(): ByteArray = Wire.Writer().apply {
        int32(1, type)
        int32(2, frameType)
        int32(3, width)
        int64(4, length)
        string(5, verifyValue)
        bool(6, needPFrames)
        int32(7, height)
    }.toByteArray()

    companion object {
        fun decode(d: ByteArray): BoxH264Send = Wire.Reader(d).run {
            var m = BoxH264Send()
            while (!atEnd()) {
                val (f, w) = readTag()
                when (f) {
                    1 -> m = m.copy(type = readInt32())
                    2 -> m = m.copy(frameType = readInt32())
                    3 -> m = m.copy(width = readInt32())
                    4 -> m = m.copy(length = readInt64())
                    5 -> m = m.copy(verifyValue = readString())
                    6 -> m = m.copy(needPFrames = readBool())
                    7 -> m = m.copy(height = readInt32())
                    else -> skip(w)
                }
            }
            m
        }
    }
}

/** 102416 MSG_CMD_BOX_CHECK_UPDATE_REQ/RES（OTA 检查更新） */
data class BoxCheckUpdate(
    val httpUrl: String = "",      // 1
    val httpType: Int = 0,         // 2
    val paramjson: String = "",    // 3
    val responseKey: String = "",  // 4
    val otaAddress: String = "",   // 5  各盒子 OTA 页（耘电 http://192.168.0.1/）
) {
    fun encode(): ByteArray = Wire.Writer().apply {
        string(1, httpUrl)
        int32(2, httpType)
        string(3, paramjson)
        string(4, responseKey)
        string(5, otaAddress)
    }.toByteArray()

    companion object {
        fun decode(d: ByteArray): BoxCheckUpdate = Wire.Reader(d).run {
            var m = BoxCheckUpdate()
            while (!atEnd()) {
                val (f, w) = readTag()
                when (f) {
                    1 -> m = m.copy(httpUrl = readString())
                    2 -> m = m.copy(httpType = readInt32())
                    3 -> m = m.copy(paramjson = readString())
                    4 -> m = m.copy(responseKey = readString())
                    5 -> m = m.copy(otaAddress = readString())
                    else -> skip(w)
                }
            }
            m
        }
    }
}
