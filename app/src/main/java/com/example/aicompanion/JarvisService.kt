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
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class JarvisService : Service() {
    private var speechRecognizer: android.speech.SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var isProcessing = false
    private var pendingCommand = ""
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    private val PART1 = "sk-or-v1-732702b2d0cd72cddcb4bca117787bef6fa64"
    private val PART2 = "632bd6dad98adfbcd139cc8946d"
    private val URL = "https://openrouter.ai/api/v1/chat/completions"
    private val MODEL = "meta-llama/llama-3.1-8b-instruct"
    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).build()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, buildNotification("Скажите 'Джарвис'..."))

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("ru", "RU")
                val voices = tts?.voices
                if (voices != null) {
                    for (voice in voices) {
                        if (voice.name.contains("female", ignoreCase = true)) { tts?.voice = voice; break }
                    }
                }
                tts?.setPitch(1.1f)
            }
        }
        startListening()
    }

    private fun startListening() {
        if (isProcessing) return
        speechRecognizer?.destroy()
        speechRecognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(this)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
        }

        speechRecognizer?.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.get(0)?.lowercase(Locale.getDefault()) ?: ""

                if (text.contains("джарвис") || text.contains("jarvis")) {
                    isProcessing = true
                    updateNotification("Слушаю...")
                    tts?.speak("Слушаю.", TextToSpeech.QUEUE_FLUSH, null, "id")
                    Handler(Looper.getMainLooper()).postDelayed({ listenCommand(false) }, 1500)
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

    private fun listenCommand(isConfirming: Boolean) {
        speechRecognizer?.destroy()
        speechRecognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(this)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
        }

        speechRecognizer?.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onResults(results: Bundle?) {
                val command = results?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.get(0) ?: ""
                if (command.isNotEmpty()) {
                    if (isConfirming) {
                        if (command.lowercase().contains("да") || command.lowercase().contains("выполняй")) {
                            processCommand(pendingCommand, false) // Выполняем без подтверждения
                        } else {
                            tts?.speak("Отменено.", TextToSpeech.QUEUE_FLUSH, null, "id")
                            Handler(Looper.getMainLooper()).postDelayed({ endProcessing() }, 2000)
                        }
                    } else {
                        pendingCommand = command
                        updateNotification("Принял: $command. Жду подтверждения.")
                        tts?.speak("Принял, $command. Выполняю?", TextToSpeech.QUEUE_FLUSH, null, "id")
                        Handler(Looper.getMainLooper()).postDelayed({ listenCommand(true) }, 3500)
                    }
                } else {
                    endProcessing()
                }
            }
            override fun onError(error: Int) { endProcessing() }
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

    private fun processCommand(command: String, askAI: Boolean) {
        updateNotification("Выполняю...")
        
        val currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val currentDate = SimpleDateFormat("EEEE, d MMMM yyyy", Locale("ru")).format(Date())

        val systemPrompt = """
            Ты - голосовой ассистент. Время: $currentTime, Дата: $currentDate.
            Твои команды:
            [CMD:open_app:ИМЯ] (ютуб, телеграм, whatsapp)
            [CMD:search:ЗАПРОС]
            [CMD:open_browser]
            [CMD:volume_up] / [CMD:volume_down] / [CMD:mute]
            [CMD:torch_on] / [CMD:torch_off]
            [CMD:alarm:ЧЧ:ММ] / [CMD:timer:МИНУТЫ]
            [CMD:music] (включить музыку)
            [CMD:vpn] (включить VPN)
            [CMD:note:ТЕКСТ] (записать заметку)
            [CMD:read_notes] (прочитать заметки)
            Если это просто вопрос - ответь кратко.
        """.trimIndent()

        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt))
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
                
                if (reply.contains("[CMD:")) {
                    executeSystemCommand(reply)
                } else {
                    val cleanReply = reply.replace(Regex("\\[CMD:[^\\]]+\\]"), "").trim()
                    tts?.speak(cleanReply, TextToSpeech.QUEUE_FLUSH, null, "id")
                }
            } catch (e: Exception) {
                tts?.speak("Ошибка сети.", TextToSpeech.QUEUE_FLUSH, null, "id")
            } finally {
                Handler(Looper.getMainLooper()).postDelayed({ endProcessing() }, 5000)
            }
        }
    }

    private fun executeSystemCommand(reply: String) {
        when {
            reply.contains("[CMD:open_app:") -> {
                val app = reply.substringAfter("[CMD:open_app:").substringBefore("]").trim().lowercase()
                openApp(app); tts?.speak("Открываю", TextToSpeech.QUEUE_FLUSH, null, "id")
            }
            reply.contains("[CMD:search:") -> {
                val q = reply.substringAfter("[CMD:search:").substringBefore("]")
                openUrl("https://google.com/search?q=${Uri.encode(q)}"); tts?.speak("Ищу", TextToSpeech.QUEUE_FLUSH, null, "id")
            }
            reply.contains("[CMD:open_browser]") -> { openUrl("https://google.com"); tts?.speak("Открываю браузер", TextToSpeech.QUEUE_FLUSH, null, "id") }
            reply.contains("[CMD:volume_up]") -> { adjustVolume(true); tts?.speak("Громче", TextToSpeech.QUEUE_FLUSH, null, "id") }
            reply.contains("[CMD:volume_down]") -> { adjustVolume(false); tts?.speak("Тише", TextToSpeech.QUEUE_FLUSH, null, "id") }
            reply.contains("[CMD:mute]") -> { setMute(); tts?.speak("Звук выключен", TextToSpeech.QUEUE_FLUSH, null, "id") }
            reply.contains("[CMD:torch_on]") -> { toggleTorch(true); tts?.speak("Фонарик включен", TextToSpeech.QUEUE_FLUSH, null, "id") }
            reply.contains("[CMD:torch_off]") -> { toggleTorch(false); tts?.speak("Фонарик выключен", TextToSpeech.QUEUE_FLUSH, null, "id") }
            reply.contains("[CMD:alarm:") -> {
                val t = reply.substringAfter("[CMD:alarm:").substringBefore("]").split(":")
                setAlarm(t.getOrNull(0)?.toIntOrNull() ?: 7, t.getOrNull(1)?.toIntOrNull() ?: 0)
                tts?.speak("Будильник поставлен", TextToSpeech.QUEUE_FLUSH, null, "id")
            }
            reply.contains("[CMD:timer:") -> {
                val m = reply.substringAfter("[CMD:timer:").substringBefore("]").toIntOrNull() ?: 5
                setTimer(m); tts?.speak("Таймер на $m минут", TextToSpeech.QUEUE_FLUSH, null, "id")
            }
            reply.contains("[CMD:music]") -> { openMusic(); tts?.speak("Включаю музыку", TextToSpeech.QUEUE_FLUSH, null, "id") }
            reply.contains("[CMD:vpn]") -> { openVpn(); tts?.speak("Открываю VPN", TextToSpeech.QUEUE_FLUSH, null, "id") }
            reply.contains("[CMD:note:") -> {
                val note = reply.substringAfter("[CMD:note:").substringBefore("]")
                saveNote(note); tts?.speak("Заметка сохранена", TextToSpeech.QUEUE_FLUSH, null, "id")
            }
            reply.contains("[CMD:read_notes]") -> {
                val notes = readNotes(); tts?.speak(notes, TextToSpeech.QUEUE_FLUSH, null, "id")
            }
        }
    }

    // --- СИСТЕМНЫЕ ФУНКЦИИ ---
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
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cm.setTorchMode(cm.cameraIdList[0], on)
        } catch (e: Exception) {}
    }
    private fun setAlarm(h: Int, m: Int) {
        val i = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, h); putExtra(AlarmClock.EXTRA_MINUTES, m); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(i)
    }
    private fun setTimer(mins: Int) {
        val i = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, mins * 60); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(i)
    }
    private fun openMusic() {
        val i = Intent("android.media.action.MEDIA_PLAY_FROM_SEARCH").apply {
            putExtra(android.app.SearchManager.QUERY, "музыка")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try { startActivity(i) } catch (e: Exception) { openUrl("https://music.yandex.ru") }
    }
    private fun openVpn() {
        // Android не позволяет включать сторонний VPN без разрешения. Открываем настройки.
        val i = Intent("android.net.vpn.SETTINGS").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        try { startActivity(i) } catch (e: Exception) {}
    }
    private fun saveNote(text: String) {
        try { File(filesDir, "notes.txt").appendText("\n$text") } catch (e: Exception) {}
    }
    private fun readNotes(): String {
        return try {
            val file = File(filesDir, "notes.txt")
            if (file.exists()) file.readText().takeLast(200) else "Заметок нет."
        } catch (e: Exception) { "Ошибка чтения" }
    }

    private fun endProcessing() {
        isProcessing = false
        updateNotification("Скажите 'Джарвис'...")
        restartListening()
    }
    private fun restartListening() {
        if (isProcessing) return
        Handler(Looper.getMainLooper()).postDelayed({ startListening() }, 300)
    }
    private fun openUrl(url: String) {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)); i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i)
    }
    private fun openApp(name: String) {
        val map = mapOf("ютуб" to "com.google.android.youtube", "youtube" to "com.google.android.youtube", "телеграм" to "org.telegram.messenger", "whatsapp" to "com.whatsapp", "браузер" to "com.android.chrome", "камера" to "com.android.camera")
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
    override fun onDestroy() { scope.cancel(); speechRecognizer?.destroy(); tts?.shutdown(); super.onDestroy() }
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("jarvis_channel", "Джарвис", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}