package com.seagull.boxcast.net;

import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.List;

/**
 * 通知监听服务——授权后才能读系统活跃 MediaSession（公开 API，海鸥桌面 MediaListenerService 同款）。
 * 用户开启路径：设置 → 通知 → 通知使用权 → BoxCast。
 */
public class MediaNotificationListener extends NotificationListenerService {

    /** 单例持有（ScreenCastService 轮询用） */
    public static volatile MediaNotificationListener instance;

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        instance = this;
    }

    @Override
    public void onListenerDisconnected() {
        instance = null;
        super.onListenerDisconnected();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {}

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {}

    /** 取当前播放的歌曲信息（无活跃会话返回 null） */
    public static MediaInfo.Info currentTrack() {
        MediaNotificationListener self = instance;
        if (self == null) return null;
        MediaSessionManager msm = (MediaSessionManager) self.getSystemService(MEDIA_SESSION_SERVICE);
        if (msm == null) return null;
        List<MediaController> sessions = msm.getActiveSessions(new android.content.ComponentName(self, MediaNotificationListener.class));
        for (MediaController c : sessions) {
            MediaMetadata md = c.getMetadata();
            if (md == null) continue;
            if (c.getPlaybackState() == null ||
                    c.getPlaybackState().getState() != android.media.session.PlaybackState.STATE_PLAYING) {
                continue;
            }
            // Kotlin data class 的 var 在 Java 侧是 setter
            return new MediaInfo.Info(
                    String.valueOf(md.getText(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)),  // source
                    String.valueOf(md.getText(MediaMetadata.METADATA_KEY_TITLE)),          // song
                    String.valueOf(md.getText(MediaMetadata.METADATA_KEY_ARTIST)),         // artist
                    String.valueOf(md.getText(MediaMetadata.METADATA_KEY_ALBUM)),          // album
                    new byte[0],                                                            // albumArt
                    (int) (md.getLong(MediaMetadata.METADATA_KEY_DURATION) / 1000),        // duration
                    1,                                                                     // playlistNum
                    "1",                                                                   // songId
                    0                                                                      // mode
            );
        }
        return null;
    }

    /** 是否已授权（通知使用权） */
    public static boolean isEnabled() {
        return instance != null;
    }
}
