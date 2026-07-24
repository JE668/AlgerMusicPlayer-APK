package com.alger.audio;

import android.view.KeyEvent;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "AudioFocus")
public class AudioFocusPlugin extends Plugin {

    private AudioFocusManager focusManager;

    @Override
    public void load() {
        focusManager = new AudioFocusManager(getContext());
        AudioFocusManager.AudioFocusCallback callback = new AudioFocusManager.AudioFocusCallback() {
            @Override
            public void onAudioFocusGained() {
                JSObject data = new JSObject();
                notifyListeners("audioFocusGained", data);
            }

            @Override
            public void onAudioFocusLost(boolean transientLoss) {
                JSObject data = new JSObject();
                data.put("transient", transientLoss);
                notifyListeners("audioFocusLost", data);
            }

            @Override
            public void onMediaButton(int action) {
                JSObject data = new JSObject();
                String actionName;
                switch (action) {
                    case KeyEvent.KEYCODE_MEDIA_PLAY:
                        actionName = "play";
                        break;
                    case KeyEvent.KEYCODE_MEDIA_PAUSE:
                        actionName = "pause";
                        break;
                    case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                        actionName = "playpause";
                        break;
                    case KeyEvent.KEYCODE_MEDIA_NEXT:
                        actionName = "next";
                        break;
                    case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                        actionName = "previous";
                        break;
                    default:
                        actionName = "unknown";
                }
                data.put("action", actionName);
                notifyListeners("mediaButton", data);
            }
        };
        // 延迟初始化：注册回调但不在 load() 时初始化 MediaSession
        focusManager.setCallback(callback);
    }

    @PluginMethod
    public void requestFocus(PluginCall call) {
        JSObject result = new JSObject();
        if (focusManager != null) {
            boolean granted = focusManager.requestFocus();
            result.put("granted", granted);
        } else {
            result.put("granted", false);
        }
        call.resolve(result);
    }

    @PluginMethod
    public void requestFocusTransient(PluginCall call) {
        JSObject result = new JSObject();
        if (focusManager != null) {
            boolean granted = focusManager.requestFocusTransient();
            result.put("granted", granted);
        } else {
            result.put("granted", false);
        }
        call.resolve(result);
    }

    @PluginMethod
    public void abandonFocus(PluginCall call) {
        if (focusManager != null) {
            focusManager.abandonFocus();
        }
        call.resolve();
    }

    @PluginMethod
    public void updateMetadata(PluginCall call) {
        String title = call.getString("title", "");
        String artist = call.getString("artist", "");
        String album = call.getString("album", "");
        String coverUrl = call.getString("coverUrl", "");

        if (focusManager != null) {
            focusManager.updateMetadata(title, artist, album, coverUrl);
        }
        call.resolve();
    }

    @PluginMethod
    public void updatePlaybackState(PluginCall call) {
        boolean playing = call.getBoolean("playing", false);
        double position = call.getDouble("position", 0.0);
        double duration = call.getDouble("duration", 0.0);

        if (focusManager != null) {
            focusManager.updatePlaybackState(playing,
                    (long) (position * 1000),
                    (long) (duration * 1000));
        }
        call.resolve();
    }

    @PluginMethod
    public void isActive(PluginCall call) {
        JSObject result = new JSObject();
        if (focusManager != null) {
            result.put("hasFocus", focusManager.hasAudioFocus());
            result.put("isPlaying", focusManager.isPlaying());
        } else {
            result.put("hasFocus", false);
            result.put("isPlaying", false);
        }
        call.resolve(result);
    }

    @PluginMethod
    public void pause(PluginCall call) {
        if (focusManager != null) {
            focusManager.pause();
        }
        call.resolve();
    }

    @Override
    protected void handleOnDestroy() {
        if (focusManager != null) {
            focusManager.release();
            focusManager = null;
        }
    }
}
