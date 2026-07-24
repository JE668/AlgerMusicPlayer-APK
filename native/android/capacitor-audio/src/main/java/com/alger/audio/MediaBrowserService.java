package com.alger.audio;

import android.os.Bundle;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.session.MediaSessionCompat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media.MediaBrowserServiceCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * MediaBrowserService 用于 Android Auto / 车机浏览音乐。
 *
 * 通过 AudioFocusManager.getStaticMediaSession() 获取已存在的 MediaSession，
 * 车机通过此服务连接后可控制播放、显示歌曲信息。
 */
public class MediaBrowserService extends MediaBrowserServiceCompat {

    private static final String MEDIA_ID_ROOT = "__ROOT__";

    @Override
    public void onCreate() {
        super.onCreate();

        // 获取 AudioFocusManager 中已创建的 MediaSession
        MediaSessionCompat mediaSession = AudioFocusManager.getStaticMediaSession();
        if (mediaSession != null) {
            setSessionToken(mediaSession.getSessionToken());
        }
    }

    @Nullable
    @Override
    public BrowserRoot onGetRoot(@NonNull String clientPackageName,
                                  int clientUid,
                                  @Nullable Bundle rootHints) {
        // 安卓 Auto 连接时，允许所有包名
        return new BrowserRoot(MEDIA_ID_ROOT, null);
    }

    @Override
    public void onLoadChildren(@NonNull String parentId,
                                @NonNull Result<List<MediaBrowserCompat.MediaItem>> result) {
        // 返回空列表，播放通过搜索/播放列表控制
        result.sendResult(new ArrayList<>());
    }
}