package com.example.aicompanion

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import android.speech.RecognizerIntent
import androidx.core.app.NotificationCompat
import java.util.Locale

class JarvisService : Service() {
    private var speechRecognizer: android.speech.SpeechRecognizer? = null
    private var isListening = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "jarvis_channel")
            .setContentTitle("Джарвис активен")
            .setContentText("Скажите 'Джарвис' для активации...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        startForeground(1, notification)

        startListening()
    }

    private fun startListening() {
        if (isListening) return
        
        speechRecognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(this)
        
        // Настройка интента для долгого прослушивания без пауз
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            // Увеличиваем время ожидания тишины (в миллисекундах), чтобы он не отключался сразу
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000) 
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)
        }

        speechRecognizer?.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                val matches = results?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.get(0)?.lowercase(Locale.getDefault()) ?: ""
                
                // Если услышали "Джарвис"
                if (text.contains("джарвис") || text.contains("jarvis")) {
                    val mainIntent = Intent(Intent.ACTION_VIEW, null, this@JarvisService, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        putExtra("VOICE_COMMAND", text)
                    }
                    startActivity(mainIntent)
                }
                // Мгновенный перезапуск прослушивания
                restartListening()
            }

            override fun onError(error: Int) {
                isListening = false
                // Если произошла ошибка (например, тишина), мгновенно перезапускаем
                restartListening()
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { isListening = false }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        speechRecognizer?.startListening(intent)
    }

    private fun restartListening() {
        // Небольшая задержка 100мс, чтобы система успела освободить микрофон
        Handler(Looper.getMainLooper()).postDelayed({
            if (speechRecognizer == null) {
                startListening()
            } else {
                // Если распознаватель уже есть, просто перезапускаем его с тем же интентом
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)
                }
                speechRecognizer?.startListening(intent)
            }
        }, 100)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        speechRecognizer?.destroy()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("jarvis_channel", "Джарвис", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}