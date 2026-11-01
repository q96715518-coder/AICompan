package com.example.aicompanion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var statusText: TextView
    private var timer: CountDownTimer? = null
    
    // Настройки OkHttp для запросов к ИИ
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // ВСТАВЬ СЮДА СВОЙ API КЛЮЧ OPENAI
    private val OPENAI_API_KEY = "sk-..." 

    private val PERMISSION_REQUEST_CODE = 123

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // --- ИНТЕРФЕЙС ---
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#1A1A1A"))
        }

        val title = TextView(this).apply {
            text = "J.A.R.V.I.S."
            textSize = 32f
            setTextColor(Color.CYAN)
            gravity = Gravity.CENTER
        }

        val versionText = TextView(this).apply {
            text = "v0.8.0"
            textSize = 14f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(0, 5, 0, 40)
        }

        statusText = TextView(this).apply {
            text = "Система в режиме ожидания"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 30, 0, 30)
        }

        val startButton = Button(this).apply {
            text = "Активировать"
            setOnClickListener {
                statusText.text = "Слушаю..."
                speak("Слушаю вас.")
                checkPermissionsAndStartListening()
            }
        }

        layout.addView(title)
        layout.addView(versionText)
        layout.addView(statusText)
        layout.addView(startButton)

        setContentView(layout)

        initTts()
        initSpeechRecognizer()
    }

    // --- ОЗВУЧКА (С НАСТРОЙКОЙ ГОЛОСА) ---
    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("ru", "RU")
                tts?.setPitch(0.85f) 
                tts?.setSpeechRate(0.95f)
            }
        }
    }

    fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "JarvisUtteranceId")
    }

    // --- РАСПОЗНАВАНИЕ РЕЧИ ---
    private fun initSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { statusText.text = "Я вас слушаю..." }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { statusText.text = "Обрабатываю..." }
                override fun onError(error: Int) { statusText.text = "Не расслышал. Нажмите еще раз." }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val command = matches[0].lowercase(Locale.getDefault())
                        statusText.text = "Вы: $command"
                        handleCommand(command)
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
        }
        speechRecognizer?.startListening(intent)
    }

    // --- ОБРАБОТКА КОМАНД ---
    private fun handleCommand(command: String) {
        when {
            // 1. Время и дата
            command.contains("время") -> {
                val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                speak("Сейчас $time")
                statusText.text = "Время: $time"
            }
            command.contains("дата") || command.contains("какое сегодня число") -> {
                val date = SimpleDateFormat("d MMMM yyyy", Locale("ru")).format(Date())
                speak("Сегодня $date")
                statusText.text = "Дата: $date"
            }

            // 2. Приложения (как раньше)
            command.contains("браузер") -> {
                speak("Открываю браузер")
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")))
            }
            command.contains("телеграм") || command.contains("telegram") -> {
                speak("Открываю Телеграм")
                openApp("org.telegram.messenger", "https://web.telegram.org")
            }
            command.contains("тик ток") || command.contains("тикток") -> {
                speak("Открываю Тик Ток")
                openApp("com.zhiliaoapp.musically")
            }
            command.contains("роблокс") -> {
                speak("Открываю Роблокс")
                openApp("com.roblox.client")
            }
            command.contains("бравл") -> {
                speak("Запускаю Бравл Старс")
                openApp("com.supercell.brawlstars")
            }
            command.contains("яндекс музыка") || command.contains("музыка") -> {
                speak("Включаю Яндекс Музыку")
                openApp("ru.yandex.music")
            }
            command.contains("ютуб") -> {
                speak("Открываю YouTube")
                openApp("com.google.android.youtube", "https://youtube.com")
            }

            // 3. Таймер
            command.contains("таймер") -> {
                speak("На сколько минут поставить таймер?")
                statusText.text = "Скажите количество минут..."
                val minutes = command.filter { it.isDigit() }.toIntOrNull() ?: 1
                startTimer(minutes)
            }
            command.contains("стоп") || command.contains("отмена") -> {
                timer?.cancel()
                speak("Таймер отменен")
                statusText.text = "Таймер отменен"
            }

            // 4. УМНЫЙ РЕЖИМ (Если команда не распознана, спрашиваем ИИ)
            else -> {
                askAI(command)
            }
        }
    }

    // --- ВСПОМОГАТЕЛЬНАЯ ФУНКЦИЯ ДЛЯ ОТКРЫТИЯ ПРИЛОЖЕНИЙ ---
    private fun openApp(packageName: String, fallbackUrl: String? = null) {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            startActivity(intent)
        } else {
            if (fallbackUrl != null) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl)))
            } else {
                speak("Извините, это приложение не установлено на вашем устройстве.")
                statusText.text = "Приложение не найдено"
            }
        }
    }

    // --- ТАЙМЕР ---
    private fun startTimer(minutes: Int) {
        timer?.cancel()
        val millisInFuture = minutes * 60 * 1000L
        speak("Таймер на $minutes минут запущен")
        statusText.text = "Таймер: $minutes мин."

        timer = object : CountDownTimer(millisInFuture, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = millisUntilFinished / 1000
                val m = seconds / 60
                val s = seconds % 60
                statusText.text = String.format("Осталось: %02d:%02d", m, s)
            }
            override fun onFinish() {
                speak("Время вышло!")
                statusText.text = "Таймер завершен!"
            }
        }.start()
    }

    // --- МОЗГ: ЗАПРОС К OPENAI ---
    private fun askAI(question: String) {
        statusText.text = "Думаю..."
        
        // Если ключ не вставлен, выдаем ошибку
        if (OPENAI_API_KEY == "sk-...") {
            speak("Извините, мой искусственный интеллект не настроен.")
            statusText.text = "Нужен API ключ OpenAI"
            return
        }

        val json = """
            {
                "model": "gpt-3.5-turbo",
                "messages": [
                    {"role": "system", "content": "Ты - Джарвис, умный и вежливый голосовой ассистент. Отвечай кратко и по делу, максимум 2-3 предложения."},
                    {"role": "user", "content": "$question"}
                ]
            }
        """.trimIndent()

        val requestBody = json.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer $OPENAI_API_KEY")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    speak("Ошибка сети. Не могу связаться с сервером.")
                    statusText.text = "Ошибка сети"
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        runOnUiThread {
                            speak("Ошибка сервера. Возможно, проблема с ключом.")
                            statusText.text = "Ошибка API"
                        }
                        return
                    }
                    val responseData = response.body?.string()
                    try {
                        val jsonObject = JSONObject(responseData!!)
                        val answer = jsonObject.getJSONArray("choices")
                            .getJSONObject(0)
                            .getJSONObject("message")
                            .getString("content")
                            
                        runOnUiThread {
                            statusText.text = "Джарвис: $answer"
                            speak(answer)
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            speak("Не смог разобрать ответ.")
                        }
                    }
                }
            }
        })
    }

    // --- РАЗРЕШЕНИЯ ---
    private fun checkPermissionsAndStartListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSION_REQUEST_CODE)
        } else {
            startListening()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startListening()
            }
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        speechRecognizer?.destroy()
        timer?.cancel()
        super.onDestroy()
    }
}