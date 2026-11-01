package com.example.aicompanion

import android.Manifest
import android.content.ActivityNotFoundException
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var statusText: TextView
    private var timer: CountDownTimer? = null
    
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
            text = "v0.7.1"
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

            // 2. Открытие приложений и сайтов
            command.contains("браузер") || command.contains("интернет") -> {
                speak("Открываю браузер")
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")))
            }
            command.contains("телеграм") || command.contains("telegram") -> {
                speak("Открываю Телеграм")
                openApp("org.telegram.messenger", "https://web.telegram.org")
            }
            command.contains("тик ток") || command.contains("тикток") || command.contains("tiktok") -> {
                speak("Открываю Тик Ток")
                openApp("com.zhiliaoapp.musically")
            }
            command.contains("роблокс") || command.contains("roblox") -> {
                speak("Открываю Роблокс")
                openApp("com.roblox.client")
            }
            command.contains("бравл") || command.contains("brawl") -> {
                speak("Запускаю Бравл Старс")
                openApp("com.supercell.brawlstars")
            }
            // НОВЫЕ ПРИЛОЖЕНИЯ
            command.contains("яндекс музыка") || command.contains("музыка") -> {
                speak("Включаю Яндекс Музыку")
                openApp("ru.yandex.music")
            }
            command.contains("ютуб") || command.contains("youtube") -> {
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

            else -> {
                speak("Я не знаю такой команды")
                statusText.text = "Команда не распознана"
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