package com.ibigou.blindbox;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.util.Locale;

public class NativeTTS implements TextToSpeech.OnInitListener {
    private static final String TAG = "NativeTTS";
    private TextToSpeech tts;
    private boolean ready = false;

    public NativeTTS(Context ctx) {
        tts = new TextToSpeech(ctx, this);
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            int r = tts.setLanguage(Locale.CHINESE);
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "Chinese TTS not available, trying default");
                tts.setLanguage(Locale.getDefault());
            }
            ready = true;
            Log.i(TAG, "TTS ready");
        } else {
            Log.e(TAG, "TTS init failed: " + status);
        }
    }

    public void speak(String text) {
        if (tts == null) return;
        if (ready) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ibigou_tts_" + System.currentTimeMillis());
        } else {
            // Retry after short delay
            new android.os.Handler().postDelayed(() -> {
                if (ready) {
                    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ibigou_tts_" + System.currentTimeMillis());
                }
            }, 1000);
        }
    }

    public boolean isReady() { return ready; }

    public void shutdown() {
        if (tts != null) { tts.stop(); tts.shutdown(); tts = null; }
        ready = false;
    }
}
