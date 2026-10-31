package com.example.aicompanion

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.*
import android.provider.AlarmClock
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class JarvisService : Service() {
    private var speechRecognizer: android.speech.SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var isProcessing = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    private val PART1 = "sk-or-v1-732702b2d0cd72cddcb4bca117787bef6fa64"
    private val PART2 = "632bd6dad98adfbcd139cc8946d"
    private val URL = "https://openrouter.ai/api/v1/chat/completions"
    private val MODEL = "meta-llama/llama-3.1-8b-instruct"
    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).build()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, buildNotification("Скажите 'Джарвис' для активации..."))

        // НАСТРОЙКА ЖЕНСКОГО ГОЛОСА (как Алиса)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("ru", "RU")
                val voices = tts?.voices
                if (voices != null) {
                    for (voice in voices) {
                        if (voice.name.contains("female", ignoreCase = true) || voice.name.contains("ru-ru-x-", ignoreCase = true)) {
                            tts?.voice = voice
                            break
                        }
                    }
                }
                tts?.setPitch(1.1f) // Чуть выше для женского голоса
                tts?.setSpeechRate(0.95f) // Чуть медленнее для мягкости
            }
        }
        startListening()
    }

    private fun startListening() {
        if (isProcessing) return
        speechRecognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(this)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
        }

        speechRecognizer?.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.get(0)?.lowercase(Locale.getDefault()) ?: ""

                if (text.contains("джарвис") || text.contains("jarvis") || text.contains("алиса")) {
                    isProcessing = true
                    updateNotification("Слушаю команду...")
                    tts?.speak("Да, сэр.", TextToSpeech.QUEUE_FLUSH, null, "id")
                    Handler(Looper.getMainLooper()).postDelayed({ listenCommand() }, 1500)
                } else {
                    restartListening()
                }
            }
            override fun onError(error: Int) { restartListening() }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        speechRecognizer?.startListening(intent)
    }

    private fun listenCommand() {
        speechRecognizer?.destroy()
        speechRecognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(this)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
        }

        speechRecognizer?.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                val command = matches?.get(0) ?: ""
                if (command.isNotEmpty()) processCommand(command) else endProcessing()
            }
            override fun onError(error: Int) { endProcessing() }
            override fun onReadyForSpeech(params: Bundle?) { updateNotification("Слушаю...") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        speechRecognizer?.startListening(intent)
    }

    private fun processCommand(command: String) {
        updateNotification("Обрабатываю: $command")
        
        val currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val currentDate = SimpleDateFormat("EEEE, d MMMM yyyy", Locale("ru")).format(Date())

        val messages = JSONArray().put(
            JSONObject().put("role", "system").put("content", 
            "Ты - голосовой ассистент. Сейчас время: $currentTime, дата: $currentDate. Отвечай кратко. Команды: [CMD:open_app:ютуб/телеграм] [CMD:search:запрос] [CMD:open_browser] [CMD:volume_up] [CMD:volume_down] [CMD:mute] [CMD:torch_on] [CMD:torch_off] [CMD:alarm:ЧЧ:ММ] [CMD:timer:МИНУТЫ]")
        )
        messages.put(JSONObject().put("role", "user").put("content", command))

        val body = JSONObject().put("model", MODEL).put("messages", messages)
            .toString().toRequestBody("application/json".toMediaType())

        val req = Request.Builder().url(URL)
            .addHeader("Authorization", "Bearer ${PART1 + PART2}")
            .post(body).build()

        scope.launch {
            try {
                val res = withContext(Dispatchers.IO) { client.newCall(req).execute() }
                val json = JSONObject(res.body?.string() ?: "{}")
                val reply = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
                
                if (reply.contains("[CMD:open_app:")) {
                    val app = reply.substringAfter("[CMD:open_app:").substringBefore("]").trim().lowercase()
                    openApp(app)
                    tts?.speak("Открываю $app", TextToSpeech.QUEUE_FLUSH, null, "id")
                } else if (reply.contains("[CMD:search:")) {
                    val q = reply.substringAfter("[CMD:search:").substringBefore("]")
                    openUrl("https://google.com/search?q=${Uri.encode(q)}")
                    tts?.speak("Ищу $q", TextToSpeech.QUEUE_FLUSH, null, "id")
                } else if (reply.contains("[CMD:volume_up]")) {
                    adjustVolume(true); tts?.speak("Сделала громче", TextToSpeech.QUEUE_FLUSH, null, "id")
                } else if (reply.contains("[CMD:volume_down]")) {
                    adjustVolume(false); tts?.speak("Сделала тише", TextToSpeech.QUEUE_FLUSH, null, "id")
                } else if (reply.contains("[CMD:mute]")) {
                    setMute(); tts?.speak("Звук выключен", TextToSpeech.QUEUE_FLUSH, null, "id")
                } else if (reply.contains("[CMD:torch_on]")) {
                    toggleTorch(true); tts?.speak("Фонарик включен", TextToSpeech.QUEUE_FLUSH, null, "id")
                } else if (reply.contains("[CMD:torch_off]")) {
                    toggleTorch(false); tts?.speak("Фонарик выключен", TextToSpeech.QUEUE_FLUSH, null, "id")
                } else if (reply.contains("[CMD:alarm:")) {
                    val time = reply.substringAfter("[CMD:alarm:").substringBefore("]").split(":")
                    setAlarm(time.getOrNull(0)?.toIntOrNull() ?: 7, time.getOrNull(1)?.toIntOrNull() ?: 0)
                    tts?.speak("Будильник поставлен", TextToSpeech.QUEUE_FLUSH, null, "id")
                } else {
                    val cleanReply = reply.replace(Regex("\\[CMD:[^\\]]+\\]"), "").trim()
                    tts?.speak(cleanReply, TextToSpeech.QUEUE_FLUSH, null, "id")
                }
            } catch (e: Exception) {
                tts?.speak("Ошибка соединения", TextToSpeech.QUEUE_FLUSH, null, "id")
            } finally {
                Handler(Looper.getMainLooper()).postDelayed({ endProcessing() }, 4000)
            }
        }
    }

    private fun adjustVolume(up: Boolean) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val dir = if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        repeat(5) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0) }
    }

    private fun setMute() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
    }

    private fun toggleTorch(on: Boolean) {
        try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList[0]
            cameraManager.setTorchMode(cameraId, on)
        } catch (e: Exception) {}
    }

    private fun setAlarm(hour: Int, minute: Int) {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun endProcessing() {
        isProcessing = false
        updateNotification("Скажите 'Джарвис' для активации...")
        restartListening()
    }

    private fun restartListening() {
        if (isProcessing) return
        Handler(Looper.getMainLooper()).postDelayed({ startListening() }, 200)
    }

    private fun openUrl(url: String) {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
    }

    private fun openApp(name: String) {
        val map = mapOf(
            "ютуб" to "com.google.android.youtube", "youtube" to "com.google.android.youtube",
            "телеграм" to "org.telegram.messenger", "whatsapp" to "com.whatsapp",
            "браузер" to "com.android.chrome", "камера" to "com.android.camera"
        )
        val pkg = map[name]
        if (pkg != null) {
            val i = packageManager.getLaunchIntentForPackage(pkg)
            if (i != null) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); return }
        }
        openUrl("https://google.com/search?q=$name")
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, "jarvis_channel")
            .setContentTitle("Джарвис онлайн").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(1, buildNotification(text))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        scope.cancel(); speechRecognizer?.destroy(); tts?.shutdown(); super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("jarvis_channel", "Джарвис", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}