package com.ibigou.blindbox;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.widget.Toast;

import java.util.Locale;

public class NativeTTS implements TextToSpeech.OnInitListener {
    private static final String TAG = "NativeTTS";
    private TextToSpeech tts;
    private boolean ready = false;
    private int initStatus = -999;
    private int langResult = -999;
    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public NativeTTS(Context ctx) {
        this.context = ctx;
        tts = new TextToSpeech(ctx, this);
    }

    @Override
    public void onInit(int status) {
        initStatus = status;
        if (status == TextToSpeech.SUCCESS) {
            // Force audio through media stream so Bluetooth A2DP earphones get it
            try {
                AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
                tts.setAudioAttributes(attrs);
            } catch (Exception e) {
                Log.w(TAG, "setAudioAttributes failed", e);
            }

            langResult = tts.setLanguage(Locale.CHINESE);
            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "Chinese not available (code=" + langResult + "), trying default");
                langResult = tts.setLanguage(Locale.getDefault());
            }
            ready = true;
            Log.i(TAG, "TTS ready, lang=" + langResult);
            toast("TTS ready, lang=" + langResult);
        } else {
            Log.e(TAG, "TTS init failed: " + status);
            toast("TTS init failed: " + status);
        }
    }

    public int speak(String text) {
        if (tts == null) {
            toast("TTS engine null, playing fallback");
            playFallbackSound();
            return -1;
        }

        if (!ready) {
            toast("TTS not ready (init=" + initStatus + "), playing fallback");
            playFallbackSound();
            return -2;
        }

        // Request transient audio focus on media stream
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                am.requestAudioFocus(null, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
            }
        } catch (Exception e) {
            Log.w(TAG, "requestAudioFocus failed", e);
        }

        int result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null,
            "ibigou_tts_" + System.currentTimeMillis());
        Log.i(TAG, "speak result=" + result + " ready=" + ready + " lang=" + langResult);
        toast("TTS speak=" + result + " ready=" + ready + " lang=" + langResult);

        if (result != TextToSpeech.SUCCESS) {
            playFallbackSound();
        }
        return result;
    }

    // Play TTS audio from URL (e.g. Baidu TTS female voice)
    private MediaPlayer mediaPlayer;
    private static final String BASE_URL = "https://ybgtc.com";
    public void speakUrl(String url) {
        mainHandler.post(() -> {
            try {
                if (url != null && url.startsWith("/")) {
                    url = BASE_URL + url;
                }
                if (mediaPlayer != null) {
                    try { mediaPlayer.release(); } catch (Exception ignored) {}
                    mediaPlayer = null;
                }
                mediaPlayer = new MediaPlayer();
                mediaPlayer.setAudioAttributes(
                    new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                );
                mediaPlayer.setDataSource(url);
                mediaPlayer.setOnPreparedListener(mp -> {
                    mp.start();
                    Log.i(TAG, "MediaPlayer started: " + url);
                });
                mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                    Log.e(TAG, "MediaPlayer error: what=" + what + " extra=" + extra + " url=" + url);
                    try { mp.release(); } catch (Exception ignored) {}
                    mediaPlayer = null;
                    // Fallback to system TTS
                    // cloud TTS failed; leave to JS-level fallback (Web Speech API)
                    return true;
                });
                mediaPlayer.setOnCompletionListener(mp -> {
                    try { mp.release(); } catch (Exception ignored) {}
                    mediaPlayer = null;
                });
                mediaPlayer.prepareAsync();
                Log.i(TAG, "MediaPlayer preparing: " + url);
            } catch (Exception e) {
                Log.e(TAG, "speakUrl failed", e);
            }
        });
    }

    private void playFallbackSound() {
        try {
            Uri soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            if (soundUri == null) {
                soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            }
            Ringtone r = RingtoneManager.getRingtone(context, soundUri);
            if (r != null) {
                try { r.setStreamType(AudioManager.STREAM_MUSIC); } catch (Exception ignored) {}
                r.play();
            }
        } catch (Exception e) {
            Log.e(TAG, "Fallback sound failed", e);
        }
    }

    public void installTtsData() {
        mainHandler.post(() -> {
            try {
                Intent intent = new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            } catch (Exception e) {
                Log.e(TAG, "installTtsData failed", e);
                toast("Cannot open TTS install page");
            }
        });
    }

    public String getStatus() {
        return "init=" + initStatus + " ready=" + ready + " lang=" + langResult;
    }

    private void toast(final String msg) {
        mainHandler.post(() -> Toast.makeText(context, msg, Toast.LENGTH_LONG).show());
    }

    public boolean isReady() { return ready; }

    public void shutdown() {
        if (tts != null) { tts.stop(); tts.shutdown(); tts = null; }
        ready = false;
    }
}
