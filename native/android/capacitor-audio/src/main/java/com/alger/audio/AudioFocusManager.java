package com.alger.audio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.view.KeyEvent;

import androidx.core.app.NotificationCompat;
import androidx.media.session.MediaButtonReceiver;

import java.util.ArrayList;
import java.util.List;

public class AudioFocusManager {

    private static final String CHANNEL_ID = "alger_playback";
    private static final int NOTIFICATION_ID = 1001;

    // 静态字段，供 MediaBrowserService 获取 session token
    private static MediaSessionCompat staticMediaSession = null;

    // 静态回调，MediaBrowserService 注册，当 MediaSession 创建完毕时自动接收通知
    // 比固定延迟重试更可靠 — 无论插件加载多久，session 就绪后立即注入 token
    public static Runnable onSessionReadyCallback = null;

    public static MediaSessionCompat getStaticMediaSession() {
        return staticMediaSession;
    }

    private AudioManager audioManager;
    private MediaSessionCompat mediaSession;
    private AudioFocusRequest focusRequest;
    private PowerManager.WakeLock wakeLock;
    private boolean hasAudioFocus = false;
    private boolean isPlaying = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Context appContext;
    private NotificationManager notificationManager;

    private String currentTitle = "";
    private String currentArtist = "";
    private String currentAlbum = "";
    private String currentCoverUrl = "";
    private long currentDuration = 0;
    private long currentPosition = 0;

    private AudioFocusCallback focusCallback;
    private boolean sessionActive = false;

    public interface AudioFocusCallback {
        void onAudioFocusGained();
        void onAudioFocusLost(boolean transientLoss);
        void onMediaButton(int action);
    }

    public AudioFocusManager(Context context) {
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        this.appContext = context.getApplicationContext();
        this.notificationManager = (NotificationManager)
            appContext.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    public void setCallback(AudioFocusCallback callback) {
        this.focusCallback = callback;
    }

    /**
     * 延迟初始化 MediaSession — 只在真正需要播放时才激活，
     * 避免与车载系统自带播放器的 MediaSession 竞争。
     */
    private void ensureSessionInitialized() {
        if (mediaSession != null) return;

        createNotificationChannel();

        // MediaButtonReceiver PendingIntent — 正确指向 Manifest 中注册的 Receiver
        Intent mediaButtonIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
        mediaButtonIntent.setClass(appContext, MediaButtonReceiver.class);
        PendingIntent buttonReceiverIntent = PendingIntent.getBroadcast(
            appContext, 0, mediaButtonIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ?
                PendingIntent.FLAG_IMMUTABLE : 0)
        );

        mediaSession = new MediaSessionCompat(appContext, "AlgerMusicPlayer");
        staticMediaSession = mediaSession;
        // 通知 MediaBrowserService：session 已就绪，自动注入 token
        if (onSessionReadyCallback != null) {
            onSessionReadyCallback.run();
        }
        mediaSession.setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS |
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
        );
        // 关键修复：指向 MediaButtonReceiver，不是 Activity
        mediaSession.setMediaButtonReceiver(buttonReceiverIntent);
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
                KeyEvent event = mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                if (event == null || event.getAction() != KeyEvent.ACTION_DOWN) return false;

                int keyCode = event.getKeyCode();
                dispatchMediaButton(keyCode);
                return true;
            }

            @Override
            public void onPlay() {
                dispatchMediaButton(KeyEvent.KEYCODE_MEDIA_PLAY);
            }

            @Override
            public void onPause() {
                dispatchMediaButton(KeyEvent.KEYCODE_MEDIA_PAUSE);
            }

            @Override
            public void onSkipToNext() {
                dispatchMediaButton(KeyEvent.KEYCODE_MEDIA_NEXT);
            }

            @Override
            public void onSkipToPrevious() {
                dispatchMediaButton(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
            }

            @Override
            public void onStop() {
                dispatchMediaButton(KeyEvent.KEYCODE_MEDIA_PAUSE);
            }
        });

        // 关键修复：MediaSession 创建后立即激活，不要等 AudioFocus 获得后才激活。
        // Android Auto / 车机在连接 MediaBrowserService 时需要 MediaSession 已激活，
        // 否则车机无法发现该应用。应用启动时就应该激活，播放时自动获得 AudioFocus。
        if (!sessionActive) {
            mediaSession.setActive(true);
            sessionActive = true;
        }
    }

    private void dispatchMediaButton(int keyCode) {
        mainHandler.post(() -> {
            if (focusCallback != null) focusCallback.onMediaButton(keyCode);
        });
    }

    // ==================== Notification Channel ====================

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        NotificationChannel channel = notificationManager.getNotificationChannel(CHANNEL_ID);
        if (channel == null) {
            channel = new NotificationChannel(
                CHANNEL_ID,
                "Music Playback",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Playback controls for AlgerMusicPlayer");
            channel.setShowBadge(false);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            channel.enableVibration(false);
            channel.setSound(null, null);
            notificationManager.createNotificationChannel(channel);
        }
    }

    // ==================== WakeLock ====================

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;

        PowerManager pm = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
        if (pm == null) return;

        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AlgerMusicPlayer::Playback"
        );
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    // ==================== Notification ====================

    private PendingIntent buildMediaButtonPendingIntent(int keyCode) {
        Intent intent = new Intent(Intent.ACTION_MEDIA_BUTTON);
        intent.setClass(appContext, MediaButtonReceiver.class);
        intent.putExtra(Intent.EXTRA_KEY_EVENT,
            new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
        return PendingIntent.getBroadcast(
            appContext, keyCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ?
                PendingIntent.FLAG_IMMUTABLE : 0)
        );
    }

    private Notification buildNotification(boolean playing) {
        Intent launchIntent = appContext.getPackageManager()
            .getLaunchIntentForPackage(appContext.getPackageName());
        PendingIntent contentIntent = null;
        if (launchIntent != null) {
            contentIntent = PendingIntent.getActivity(
                appContext, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ?
                    PendingIntent.FLAG_IMMUTABLE : 0)
            );
        }

        String title = currentTitle.isEmpty() ? "AlgerMusicPlayer" : currentTitle;
        String text = currentArtist.isEmpty()
            ? (playing ? "Now playing" : "Paused")
            : (playing ? currentArtist : currentArtist + " ⏸");

        NotificationCompat.Builder builder = new NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(currentAlbum.isEmpty() ? null : currentAlbum)
            .setSmallIcon(appContext.getApplicationInfo().icon)
            .setContentIntent(contentIntent)
            .setOngoing(playing)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(mediaSession.getSessionToken())
                .setShowActionsInCompactView(0, 1, 2))
            .addAction(android.R.drawable.ic_media_previous, "Previous",
                buildMediaButtonPendingIntent(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
            .addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                playing ? "Pause" : "Play",
                buildMediaButtonPendingIntent(playing ? KeyEvent.KEYCODE_MEDIA_PAUSE : KeyEvent.KEYCODE_MEDIA_PLAY))
            .addAction(android.R.drawable.ic_media_next, "Next",
                buildMediaButtonPendingIntent(KeyEvent.KEYCODE_MEDIA_NEXT));

        return builder.build();
    }

    private void showNotification() {
        try {
            notificationManager.notify(NOTIFICATION_ID, buildNotification(isPlaying));
        } catch (SecurityException e) {
            // POST_NOTIFICATIONS permission not granted on Android 13+
        }
    }

    private void dismissNotification() {
        try {
            notificationManager.cancel(NOTIFICATION_ID);
        } catch (Exception ignored) {}
    }

    // ==================== AudioFocus ====================

    /**
     * 请求音频焦点。返回 true 表示成功获得焦点。
     */
    public boolean requestFocus() {
        ensureSessionInitialized();
        if (mediaSession == null) return false;

        int result;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();

            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener(focusChange -> {
                        mainHandler.post(() -> handleFocusChange(focusChange));
                    })
                    .build();

            result = audioManager.requestAudioFocus(focusRequest);
        } else {
            result = audioManager.requestAudioFocus(
                    afChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
            );
        }

        hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);

        if (hasAudioFocus) {
            // 拿到焦点后才激活 MediaSession
            if (!sessionActive && mediaSession != null) {
                mediaSession.setActive(true);
                sessionActive = true;
            }
        }

        return hasAudioFocus;
    }

    /**
     * 以瞬态模式重试获取焦点（专为车载抢焦点设计）
     */
    public boolean requestFocusTransient() {
        ensureSessionInitialized();
        if (mediaSession == null) return false;

        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attrs)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener(focusChange -> {
                        mainHandler.post(() -> handleFocusChange(focusChange));
                    })
                    .build();

            int result = audioManager.requestAudioFocus(focusRequest);
            hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        } else {
            // API < 26 兼容
            int result = audioManager.requestAudioFocus(
                    afChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            );
            hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        }

        if (hasAudioFocus && !sessionActive && mediaSession != null) {
            mediaSession.setActive(true);
            sessionActive = true;
        }

        return hasAudioFocus;
    }

    public void abandonFocus() {
        hasAudioFocus = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
            audioManager.abandonAudioFocusRequest(focusRequest);
        } else {
            audioManager.abandonAudioFocus(afChangeListener);
        }
        // 失去焦点时停用 MediaSession 避免竞争
        if (sessionActive && mediaSession != null) {
            mediaSession.setActive(false);
            sessionActive = false;
        }
    }

    private void handleFocusChange(int focusChange) {
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_GAIN:
                hasAudioFocus = true;
                if (focusCallback != null) focusCallback.onAudioFocusGained();
                break;
            case AudioManager.AUDIOFOCUS_LOSS:
                hasAudioFocus = false;
                if (sessionActive && mediaSession != null) {
                    mediaSession.setActive(false);
                    sessionActive = false;
                }
                if (focusCallback != null) focusCallback.onAudioFocusLost(false);
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                hasAudioFocus = false;
                if (focusCallback != null) focusCallback.onAudioFocusLost(true);
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                // 短暂失去焦点但可以降低音量继续播放 — 不暂停，不触发回调
                // 保持 hasAudioFocus = true，播放继续
                break;
        }
    }

    private final AudioManager.OnAudioFocusChangeListener afChangeListener = focusChange -> {
        mainHandler.post(() -> handleFocusChange(focusChange));
    };

    // ==================== Public API ====================

    public void updateMetadata(String title, String artist, String album, String coverUrl) {
        this.currentTitle = title != null ? title : "";
        this.currentArtist = artist != null ? artist : "";
        this.currentAlbum = album != null ? album : "";
        this.currentCoverUrl = coverUrl != null ? coverUrl : "";

        if (mediaSession == null) return;

        MediaMetadataCompat.Builder builder = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, this.currentTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, this.currentArtist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, this.currentAlbum)
                // 车载仪表盘显示字段
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, this.currentTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE,
                    this.currentAlbum.isEmpty() ? this.currentArtist :
                    this.currentArtist + " · " + this.currentAlbum)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, this.currentDuration);

        if (!this.currentCoverUrl.isEmpty()) {
            builder.putString(MediaMetadataCompat.METADATA_KEY_ART_URI, this.currentCoverUrl);
            builder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, this.currentCoverUrl);
        }

        mediaSession.setMetadata(builder.build());

        // 更新通知栏
        if (hasAudioFocus) {
            showNotification();
        }
    }

    public void updatePlaybackState(boolean playing, long positionMs, long durationMs) {
        if (mediaSession == null) return;
        isPlaying = playing;
        this.currentPosition = positionMs;
        this.currentDuration = durationMs;

        int state = playing
                ? PlaybackStateCompat.STATE_PLAYING
                : PlaybackStateCompat.STATE_PAUSED;

        PlaybackStateCompat.Builder builder = new PlaybackStateCompat.Builder()
                .setState(state, positionMs, 1.0f)
                .setActions(
                        PlaybackStateCompat.ACTION_PLAY |
                        PlaybackStateCompat.ACTION_PAUSE |
                        PlaybackStateCompat.ACTION_PLAY_PAUSE |
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                        PlaybackStateCompat.ACTION_STOP |
                        PlaybackStateCompat.ACTION_SEEK_TO
                );

        mediaSession.setPlaybackState(builder.build());

        // 确保 MediaSession 在播放时始终激活，即使没有 AudioFocus
        // 车机/Android Auto 依赖激活的 MediaSession 来发现和控制应用
        if (playing && !sessionActive) {
            mediaSession.setActive(true);
            sessionActive = true;
        }

        // WakeLock 管理
        if (playing) {
            acquireWakeLock();
        } else {
            releaseWakeLock();
        }

        // 通知栏
        if (hasAudioFocus) {
            showNotification();
        } else if (!playing) {
            dismissNotification();
        }
    }

    /**
     * 更新播放队列（供 WebView 端调用）
     * 设置 MediaSession 的队列，让 Android Auto / 车机仪表盘显示"即将播放"列表
     */
    public void updateQueue(List<MediaSessionCompat.QueueItem> queueItems) {
        if (mediaSession == null) return;
        mediaSession.setQueue(queueItems);

        // 同步到 MediaBrowserService，让车机可以浏览播放列表
        if (queueItems != null) {
            List<MediaBrowserCompat.MediaItem> browserItems = new ArrayList<>();
            String currentTitle = this.currentTitle;
            for (int i = 0; i < queueItems.size(); i++) {
                MediaSessionCompat.QueueItem qi = queueItems.get(i);
                MediaDescriptionCompat desc = qi.getDescription();
                // 第一首标记为"当前播放"
                String title = (i == 0 ? "▶ " : "") + (desc.getTitle() != null ? desc.getTitle().toString() : "未知歌曲");
                String subtitle = desc.getSubtitle() != null ? desc.getSubtitle().toString() : "";
                MediaDescriptionCompat browseDesc = new MediaDescriptionCompat.Builder()
                        .setMediaId(String.valueOf(qi.getQueueId()))
                        .setTitle(title)
                        .setSubtitle(subtitle)
                        .setIconUri(desc.getIconUri())
                        .build();
                browserItems.add(new MediaBrowserCompat.MediaItem(
                        browseDesc, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE));
            }
            MediaBrowserService.updateQueue(browserItems, currentTitle);
        }
    }

    public boolean hasAudioFocus() { return hasAudioFocus; }
    public boolean isPlaying() { return isPlaying; }

    public void release() {
        releaseWakeLock();
        dismissNotification();
        abandonFocus();
        if (mediaSession != null) {
            if (sessionActive) {
                mediaSession.setActive(false);
                sessionActive = false;
            }
            mediaSession.release();
            mediaSession = null;
            staticMediaSession = null;
        }
    }

    /**
     * 释放 AudioFocus 但保留 MediaSession（应用进入后台但音乐继续）
     */
    public void pause() {
        releaseWakeLock();
        abandonFocus();
        dismissNotification();
    }
}
