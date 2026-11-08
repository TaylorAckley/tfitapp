package com.tfitapp.timer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import timber.log.Timber
import java.util.Locale

/**
 * Beeps, speech, and vibration for the timer. Requests transient audio focus so
 * music ducks rather than stops. TTS is warmed up at construction so the first
 * exercise name is not late.
 */
class TimerCues(private val context: Context) {

    private val audio = context.getSystemService(AudioManager::class.java)
    private val tone: ToneGenerator? = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 90) }.getOrNull()
    private val vibrator: Vibrator? = context.getSystemService(VibratorManager::class.java)?.defaultVibrator

    private var ttsReady = false
    private val tts: TextToSpeech = TextToSpeech(context) { status ->
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) tts.language = Locale.getDefault()
    }

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    fun play(cue: TimerCue) {
        withFocus {
            when (cue) {
                is TimerCue.Countdown -> {
                    tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
                    vibrate(60)
                }
                is TimerCue.PhaseStarted -> {
                    tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
                    vibrate(250)
                    if (cue.speak) speak(cue.label)
                }
                TimerCue.Finished -> {
                    tone?.startTone(ToneGenerator.TONE_PROP_ACK, 800)
                    vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 200, 100, 400), -1))
                    speak("Done")
                }
            }
        }
    }

    private fun speak(text: String) {
        if (!ttsReady) return
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tfit-${System.nanoTime()}")
    }

    private fun vibrate(ms: Long) {
        vibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun withFocus(block: () -> Unit) {
        val granted = audio?.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        try {
            block()
        } finally {
            if (granted) audio?.abandonAudioFocusRequest(focusRequest)
        }
    }

    fun release() {
        runCatching { tone?.release() }
        runCatching { tts.stop(); tts.shutdown() }.onFailure { Timber.w(it) }
    }
}
