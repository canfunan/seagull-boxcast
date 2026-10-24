package com.seagull.boxcast.net

import com.seagull.boxcast.proto.Wire

/**
 * 媒体信息（CarlifeMediaInfoProto.proto 权威 9 字段）——车机 HUD 显示当前歌曲。
 *
 * 原版实证：MusicAudioHelper 连接后发送（硬编码 "reglink carlife"）；
 * 本工程从系统 MediaSession 读取真实播放状态（公开 API，需通知监听授权）。
 */
object MediaInfo {

    data class Info(
        val source: String = "boxcast",   // 1
        val song: String = "",            // 2
        val artist: String = "",          // 3
        val album: String = "",           // 4
        val albumArt: ByteArray = ByteArray(0), // 5
        val duration: Int = 0,            // 6
        val playlistNum: Int = 1,         // 7
        val songId: String = "1",         // 8
        val mode: Int = 0,                // 9
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Info) return false
            return song == other.song && artist == other.artist && album == other.album
        }
        override fun hashCode(): Int = (song + artist + album).hashCode()
    }

    fun encode(i: Info): ByteArray = Wire.Writer().apply {
        string(1, i.source)
        string(2, i.song)
        string(3, i.artist)
        string(4, i.album)
        bytes(5, i.albumArt)
        int32(6, i.duration)
        int32(7, i.playlistNum)
        string(8, i.songId)
        int32(9, i.mode)
    }.toByteArray()
}

/**
 * 模块状态（CarlifeModuleStatusProto.proto：1=moduleID 2=statusID）。
 * moduleID：音乐/导航/视频等模块；statusID：状态码。
 */
object ModuleStatus {
    fun encode(moduleId: Int, statusId: Int): ByteArray = Wire.Writer().apply {
        int32(1, moduleId)
        int32(2, statusId)
    }.toByteArray()

    /** 模块 ID（原版 ModuleConstants 常见值） */
    const val MODULE_MUSIC = 1
    const val MODULE_NAVI = 2
    const val MODULE_VIDEO = 3
    const val MODULE_VR = 4
    const val MODULE_PHONE = 5
}

/**
 * 时间同步（CarlifeConnectTimeSyncProto.proto：1=timeStamp 单字段）。
 */
object TimeSync {
    fun encode(timeStamp: Int = (System.currentTimeMillis() / 1000).toInt()): ByteArray =
        Wire.Writer().int32(1, timeStamp).toByteArray()
}
