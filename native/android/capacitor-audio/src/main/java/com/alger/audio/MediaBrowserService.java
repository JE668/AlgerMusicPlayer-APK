package com.alger.audio;

import android.os.Bundle;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.session.MediaSessionCompat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media.MediaBrowserServiceCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * MediaBrowserService 用于 Android Auto / 车机浏览音乐。
 *
 * 车机通过此服务连接后可浏览当前播放列表、控制播放、显示歌曲信息。
 * 播放队列由 WebView 端通过 Capacitor 插件同步，onLoadChildren 返回
 * 实时队列内容，让车机仪表盘能够显示"当前播放"和"即将播放"列表。
 */
public class MediaBrowserService extends MediaBrowserServiceCompat {

    private static final String MEDIA_ID_ROOT = "__ROOT__";
    private static final String MEDIA_ID_NOW_PLAYING = "__NOW_PLAYING__";
    private static final String MEDIA_ID_QUEUE = "__QUEUE__";

    // 从 WebView 同步的当前播放队列（静态，由 AudioFocusManager.updateQueue 更新）
    private static List<MediaBrowserCompat.MediaItem> sQueueItems = new ArrayList<>();
    private static String sCurrentTitle = "";

    /**
     * 由 AudioFocusManager 调用，更新 MediaBrowserService 的队列数据
     */
    public static void updateQueue(List<MediaBrowserCompat.MediaItem> items, String currentTitle) {
        sQueueItems = items != null ? items : new ArrayList<>();
        sCurrentTitle = currentTitle != null ? currentTitle : "";
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // 获取 AudioFocusManager 中已创建的 MediaSession
        MediaSessionCompat mediaSession = AudioFocusManager.getStaticMediaSession();
        if (mediaSession != null) {
            setSessionToken(mediaSession.getSessionToken());
        } else {
            // 注册回调：当 AudioFocusManager 创建 MediaSession 时自动注入 token
            AudioFocusManager.onSessionReadyCallback = () -> {
                MediaSessionCompat ms = AudioFocusManager.getStaticMediaSession();
                if (ms != null) {
                    setSessionToken(ms.getSessionToken());
                }
                AudioFocusManager.onSessionReadyCallback = null;
            };
        }
    }

    @Nullable
    @Override
    public BrowserRoot onGetRoot(@NonNull String clientPackageName,
                                  int clientUid,
                                  @Nullable Bundle rootHints) {
        // Android Auto 连接时，允许所有包名
        return new BrowserRoot(MEDIA_ID_ROOT, null);
    }

    @Override
    public void onLoadChildren(@NonNull String parentId,
                                @NonNull Result<List<MediaBrowserCompat.MediaItem>> result) {
        List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();

        if (MEDIA_ID_ROOT.equals(parentId)) {
            // 根目录：返回"当前播放"和"播放队列"两个分类
            if (!sCurrentTitle.isEmpty()) {
                MediaDescriptionCompat nowPlayingDesc = new MediaDescriptionCompat.Builder()
                        .setMediaId(MEDIA_ID_NOW_PLAYING)
                        .setTitle("当前播放")
                        .setSubtitle(sCurrentTitle)
                        .build();
                items.add(new MediaBrowserCompat.MediaItem(
                        nowPlayingDesc, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE));
            }

            if (!sQueueItems.isEmpty()) {
                MediaDescriptionCompat queueDesc = new MediaDescriptionCompat.Builder()
                        .setMediaId(MEDIA_ID_QUEUE)
                        .setTitle("播放列表")
                        .setSubtitle(sQueueItems.size() + " 首歌曲")
                        .build();
                items.add(new MediaBrowserCompat.MediaItem(
                        queueDesc, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE));
            }
        } else if (MEDIA_ID_QUEUE.equals(parentId)) {
            // 返回播放队列中的所有歌曲
            items.addAll(sQueueItems);
        } else if (MEDIA_ID_NOW_PLAYING.equals(parentId)) {
            // 当前播放：返回当前歌曲信息（与 MediaSession 的元数据一致）
            if (!sQueueItems.isEmpty()) {
                items.add(sQueueItems.get(0));
            }
        }

        result.sendResult(items);
    }
}