package com.alger.audio;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.view.KeyEvent;

public class AudioFocusManager {

    private AudioManager audioManager;
    private MediaSessionCompat mediaSession;
    private AudioFocusRequest focusRequest;
    private boolean hasAudioFocus = false;
    private boolean isPlaying = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private AudioFocusCallback focusCallback;

    public interface AudioFocusCallback {
        void onAudioFocusGained();
        void onAudioFocusLost(boolean transientLoss);
        void onMediaButton(int action);
    }

    public AudioFocusManager(Context context) {
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    public void init(Context context, AudioFocusCallback callback) {
        this.focusCallback = callback;

        mediaSession = new MediaSessionCompat(context, "AlgerMusicPlayer");
        mediaSession.setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS |
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
        );
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

    public void updateMetadata(String title, String artist, String album, String coverUrl) {
        if (mediaSession == null) return;

        MediaMetadataCompat.Builder builder = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title != null ? title : "")
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist != null ? artist : "")
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album != null ? album : "");
        if (coverUrl != null && !coverUrl.isEmpty()) {
            builder.putString(MediaMetadataCompat.METADATA_KEY_ART_URI, coverUrl);
        }
        mediaSession.setMetadata(builder.build());
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
    }

    public boolean hasAudioFocus() { return hasAudioFocus; }
    public boolean isPlaying() { return isPlaying; }

    public void release() {
        abandonFocus();
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
    }
}
