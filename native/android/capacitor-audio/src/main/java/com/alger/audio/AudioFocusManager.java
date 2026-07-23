package com.alger.audio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.view.KeyEvent;

import androidx.core.app.NotificationCompat;

public class AudioFocusManager {

    private static final String CHANNEL_ID = "alger_playback";
    private static final int NOTIFICATION_ID = 1001;

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

    private AudioFocusCallback focusCallback;

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

    public void init(Context context, AudioFocusCallback callback) {
        this.focusCallback = callback;

        // Create notification channel (Android 8+)
        createNotificationChannel();

        // Build PendingIntent to open app
        Intent launchIntent = context.getPackageManager()
            .getLaunchIntentForPackage(context.getPackageName());
        PendingIntent pendingIntent = null;
        if (launchIntent != null) {
            pendingIntent = PendingIntent.getActivity(
                context, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ?
                    PendingIntent.FLAG_IMMUTABLE : 0)
            );
        }

        mediaSession = new MediaSessionCompat(context, "AlgerMusicPlayer");
        mediaSession.setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS |
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
        );
        mediaSession.setMediaButtonReceiver(pendingIntent);
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
                KeyEvent event = mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                if (event == null || event.getAction() != KeyEvent.ACTION_DOWN) return false;

                int keyCode = event.getKeyCode();
                int action = -1;
                switch (keyCode) {
                    case KeyEvent.KEYCODE_MEDIA_PLAY:           action = keyCode; break;
                    case KeyEvent.KEYCODE_MEDIA_PAUSE:         action = keyCode; break;
                    case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:    action = keyCode; break;
                    case KeyEvent.KEYCODE_MEDIA_NEXT:          action = keyCode; break;
                    case KeyEvent.KEYCODE_MEDIA_PREVIOUS:      action = keyCode; break;
                }

                if (action > 0) {
                    final int finalAction = action;
                    mainHandler.post(() -> {
                        if (focusCallback != null) focusCallback.onMediaButton(finalAction);
                    });
                    return true;
                }
                return false;
            }

            @Override
            public void onPlay() {
                mainHandler.post(() -> {
                    if (focusCallback != null) focusCallback.onMediaButton(KeyEvent.KEYCODE_MEDIA_PLAY);
                });
            }

            @Override
            public void onPause() {
                mainHandler.post(() -> {
                    if (focusCallback != null) focusCallback.onMediaButton(KeyEvent.KEYCODE_MEDIA_PAUSE);
                });
            }

            @Override
            public void onSkipToNext() {
                mainHandler.post(() -> {
                    if (focusCallback != null) focusCallback.onMediaButton(KeyEvent.KEYCODE_MEDIA_NEXT);
                });
            }

            @Override
            public void onSkipToPrevious() {
                mainHandler.post(() -> {
                    if (focusCallback != null) focusCallback.onMediaButton(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
                });
            }

            @Override
            public void onStop() {
                mainHandler.post(() -> {
                    if (focusCallback != null) focusCallback.onMediaButton(KeyEvent.KEYCODE_MEDIA_PAUSE);
                });
            }
        });
        mediaSession.setActive(true);
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
        // 不设超时 — 只要在播放就一直持有
        wakeLock.acquire();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    // ==================== Notification ====================

    private PendingIntent buildMediaButtonIntent(int keyCode) {
        Intent intent = new Intent(Intent.ACTION_MEDIA_BUTTON);
        intent.setPackage(appContext.getPackageName());
        intent.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
        return PendingIntent.getBroadcast(
            appContext, keyCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ?
                PendingIntent.FLAG_IMMUTABLE : 0)
        );
    }

    private void buildAndShowNotification() {
        if (mediaSession == null) return;

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

        NotificationCompat.Builder builder = new NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setContentTitle(currentTitle.isEmpty() ? "AlgerMusicPlayer" : currentTitle)
            .setContentText(currentArtist.isEmpty() ? "Now playing" : currentArtist)
            .setSubText(currentAlbum.isEmpty() ? null : currentAlbum)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(mediaSession.getSessionToken())
                .setShowActionsInCompactView(0, 1, 2))
            .addAction(android.R.drawable.ic_media_previous, "Previous",
                buildMediaButtonIntent(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
            .addAction(android.R.drawable.ic_media_pause, "Pause",
                buildMediaButtonIntent(KeyEvent.KEYCODE_MEDIA_PAUSE))
            .addAction(android.R.drawable.ic_media_next, "Next",
                buildMediaButtonIntent(KeyEvent.KEYCODE_MEDIA_NEXT));

        notificationManager.notify(NOTIFICATION_ID, builder.build());
    }

    private void updateNotificationPaused() {
        if (currentTitle.isEmpty()) {
            dismissNotification();
            return;
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setContentTitle(currentTitle)
            .setContentText(currentArtist.isEmpty() ? "Paused" : currentArtist + " (Paused)")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(mediaSession.getSessionToken())
                .setShowActionsInCompactView(0, 1, 2))
            .addAction(android.R.drawable.ic_media_previous, "Previous",
                buildMediaButtonIntent(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
            .addAction(android.R.drawable.ic_media_play, "Play",
                buildMediaButtonIntent(KeyEvent.KEYCODE_MEDIA_PLAY))
            .addAction(android.R.drawable.ic_media_next, "Next",
                buildMediaButtonIntent(KeyEvent.KEYCODE_MEDIA_NEXT));

        notificationManager.notify(NOTIFICATION_ID, builder.build());
    }

    private void dismissNotification() {
        try {
            notificationManager.cancel(NOTIFICATION_ID);
        } catch (Exception ignored) {}
    }

    // ==================== AudioFocus ====================

    public void requestFocus() {
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

            int result = audioManager.requestAudioFocus(focusRequest);
            hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        } else {
            int result = audioManager.requestAudioFocus(
                    afChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
            );
            hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        }
    }

    public void abandonFocus() {
        hasAudioFocus = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
            audioManager.abandonAudioFocusRequest(focusRequest);
        } else {
            audioManager.abandonAudioFocus(afChangeListener);
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
                if (focusCallback != null) focusCallback.onAudioFocusLost(false);
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                hasAudioFocus = false;
                if (focusCallback != null) focusCallback.onAudioFocusLost(true);
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

        if (mediaSession == null) return;

        MediaMetadataCompat.Builder builder = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, this.currentTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, this.currentArtist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, this.currentAlbum);
        if (coverUrl != null && !coverUrl.isEmpty()) {
            builder.putString(MediaMetadataCompat.METADATA_KEY_ART_URI, coverUrl);
        }
        mediaSession.setMetadata(builder.build());

        // Update notification
        buildAndShowNotification();
    }

    public void updatePlaybackState(boolean playing, long positionMs, long durationMs) {
        if (mediaSession == null) return;
        isPlaying = playing;

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

        // WakeLock management
        if (playing) {
            acquireWakeLock();
        } else {
            releaseWakeLock();
        }

        // Notification
        if (playing) {
            buildAndShowNotification();
        } else {
            updateNotificationPaused();
        }
    }

    public boolean hasAudioFocus() { return hasAudioFocus; }
    public boolean isPlaying() { return isPlaying; }

    public void release() {
        releaseWakeLock();
        dismissNotification();
        abandonFocus();
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
    }
}
