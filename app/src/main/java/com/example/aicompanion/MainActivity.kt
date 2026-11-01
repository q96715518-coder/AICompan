package com.example.aicompanion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.*
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var statusText: TextView
    private lateinit var inputEditText: EditText
    private var timer: CountDownTimer? = null
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val PERMISSION_REQUEST_CODE = 123

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // --- ГЛАВНЫЙ ЭКРАН ---
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#1A1A1A"))
            setPadding(40, 40, 40, 40)
        }

        val title = TextView(this).apply {
            text = "J.A.R.V.I.S."
            textSize = 32f
            setTextColor(Color.CYAN)
            gravity = Gravity.CENTER
        }

        val versionText = TextView(this).apply {
            text = "v0.9.5 (Голос + Текст)"
            textSize = 14f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(0, 5, 0, 30)
        }

        statusText = TextView(this).apply {
            text = "Система в режиме ожидания"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 20)
        }

        // --- ПОЛЕ ВВОДА ТЕКСТА ---
        inputEditText = EditText(this).apply {
            hint = "Напишите команду или вопрос..."
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setBackgroundColor(Color.parseColor("#333333"))
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(20, 20, 20, 20)
        }

        // --- КНОПКА ОТПРАВКИ ТЕКСТА ---
        val sendButton = Button(this).apply {
            text = "Отправить текст"
            setBackgroundColor(Color.parseColor("#4CAF50"))
            setOnClickListener {
                val textCommand = inputEditText.text.toString().trim()
                if (textCommand.isNotEmpty()) {
                    statusText.text = "Вы (текст): $textCommand"
                    handleCommand(textCommand)
                    inputEditText.text.clear()
                }
            }
        }

        // --- КНОПКА ГОЛОСОВОГО ВВОДА ---
        val voiceButton = Button(this).apply {
            text = "Сказать голосом 🎤"
            setBackgroundColor(Color.CYAN)
            setTextColor(Color.BLACK)
            setOnClickListener {
                statusText.text = "Слушаю..."
                speak("Слушаю вас.")
                checkPermissionsAndStartListening()
            }
        }

        // --- КНОПКА НАСТРОЕК ---
        val settingsButton = Button(this).apply {
            text = "Настройки"
            setOnClickListener {
                showSettingsMenu()
            }
        }

        // --- КНОПКА РАЗРЕШЕНИЙ (Для Android 11+) ---
        val overlayButton = Button(this).apply {
            text = "Разрешить запуск приложений"
            setBackgroundColor(Color.parseColor("#FF9800"))
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (!Settings.canDrawOverlays(this@MainActivity)) {
                        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                        startActivity(intent)
                    } else {
                        Toast.makeText(this@MainActivity, "Разрешение уже есть!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        layout.addView(title)
        layout.addView(versionText)
        layout.addView(statusText)
        layout.addView(inputEditText)
        layout.addView(sendButton)
        layout.addView(voiceButton)
        layout.addView(settingsButton)
        layout.addView(overlayButton)

        setContentView(layout)

        initTts()
        initSpeechRecognizer()
    }

    // --- МЕНЮ НАСТРОЕК ---
    private fun showSettingsMenu() {
        val builder = android.app.AlertDialog.Builder(this)
        builder.setTitle("Настройки Джарвиса")

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 20, 50, 20)
        }

        val speedSeekBar = SeekBar(this).apply {
            max = 150
            progress = 95
        }
        val speedText = TextView(this).apply { text = "Скорость речи: 0.95" }

        speedSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val speed = progress / 100f
                speedText.text = "Скорость речи: $speed"
                tts?.setSpeechRate(speed)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val pitchSeekBar = SeekBar(this).apply {
            max = 200
            progress = 85
        }
        val pitchText = TextView(this).apply { text = "Высота голоса: 0.85" }

        pitchSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val pitch = progress / 100f
                pitchText.text = "Высота голоса: $pitch"
                tts?.setPitch(pitch)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        layout.addView(speedText)
        layout.addView(speedSeekBar)
        layout.addView(pitchText)
        layout.addView(pitchSeekBar)

        builder.setView(layout)
        builder.setPositiveButton("Сохранить") { dialog, _ ->
            speak("Настройки применены.")
            dialog.dismiss()
        }
        builder.setNegativeButton("Отмена") { dialog, _ -> dialog.dismiss() }
        builder.show()
    }

    // --- ОЗВУЧКА ---
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

    // --- РАСПОЗНАВАНИЕ ГОЛОСА ---
    private fun initSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { statusText.text = "Я вас слушаю..." }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { statusText.text = "Обрабатываю..." }
                override fun onError(error: Int) { statusText.text = "Не расслышал. Попробуйте еще раз." }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val command = matches[0].lowercase(Locale.getDefault())
                        statusText.text = "Вы (голос): $command"
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

    // --- ОБРАБОТКА КОМАНД (ОБЩАЯ ДЛЯ ТЕКСТА И ГОЛОСА) ---
    private fun handleCommand(command: String) {
        val lowerCommand = command.lowercase(Locale.getDefault())
        when {
            lowerCommand.contains("время") -> {
                val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                speak("Сейчас $time")
                statusText.text = "Время: $time"
            }
            lowerCommand.contains("дата") || lowerCommand.contains("число") -> {
                val date = SimpleDateFormat("d MMMM yyyy", Locale("ru")).format(Date())
                speak("Сегодня $date")
                statusText.text = "Дата: $date"
            }
            lowerCommand.contains("браузер") -> {
                speak("Открываю браузер")
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")))
            }
            lowerCommand.contains("телеграм") || lowerCommand.contains("telegram") -> {
                speak("Открываю Телеграм")
                openApp("org.telegram.messenger", "https://web.telegram.org")
            }
            lowerCommand.contains("тик ток") || lowerCommand.contains("тикток") -> {
                speak("Открываю Тик Ток")
                openApp("com.zhiliaoapp.musically")
            }
            lowerCommand.contains("роблокс") -> {
                speak("Открываю Роблокс")
                openApp("com.roblox.client")
            }
            lowerCommand.contains("бравл") -> {
                speak("Запускаю Бравл Старс")
                openApp("com.supercell.brawlstars")
            }
            lowerCommand.contains("яндекс музыка") || lowerCommand.contains("музыка") -> {
                speak("Включаю Яндекс Музыку")
                openApp("ru.yandex.music")
            }
            lowerCommand.contains("ютуб") -> {
                speak("Открываю YouTube")
                openApp("com.google.android.youtube", "https://youtube.com")
            }
            lowerCommand.contains("таймер") -> {
                speak("На сколько минут поставить таймер?")
                statusText.text = "Скажите или напишите количество минут..."
                val minutes = lowerCommand.filter { it.isDigit() }.toIntOrNull() ?: 1
                startTimer(minutes)
            }
            lowerCommand.contains("стоп") || lowerCommand.contains("отмена") -> {
                timer?.cancel()
                speak("Таймер отменен")
                statusText.text = "Таймер отменен"
            }
            // Если команда не распознана, идем к ИИ
            else -> {
                askAI(command)
            }
        }
    }

    // --- ЗАПУСК ПРИЛОЖЕНИЙ ---
    private fun openApp(packageName: String, fallbackUrl: String? = null) {
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } else {
                if (fallbackUrl != null) {
                    val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl))
                    webIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(webIntent)
                } else {
                    speak("Извините, это приложение не установлено.")
                    statusText.text = "Приложение не найдено"
                }
            }
        } catch (e: Exception) {
            speak("Не удалось открыть приложение.")
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
                statusText.text = String.format("Осталось: %02d:%02d", seconds / 60, seconds % 60)
            }
            override fun onFinish() {
                speak("Время вышло!")
                statusText.text = "Таймер завершен!"
            }
        }.start()
    }

    // --- БЕСПЛАТНЫЙ ИИ ---
    private fun askAI(question: String) {
        statusText.text = "Думаю..."
        
        val encodedQuestion = Uri.encode(question)
        val url = "https://text.pollinations.ai/$encodedQuestion?system=Ты%20Джарвис.%20Отвечай%20кратко%20и%20по%20делу."
        
        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", "Mozilla/5.0 (Android)")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    speak("Ошибка сети. Проверьте интернет.")
                    statusText.text = "Нет связи с ИИ"
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        val answer = response.body?.string()?.trim() ?: "Я не знаю, что ответить."
                        runOnUiThread {
                            statusText.text = "Джарвис: $answer"
                            speak(answer)
                        }
                    } else {
                        runOnUiThread {
                            speak("Сервер ИИ перегружен. Попробуйте позже.")
                            statusText.text = "Ошибка сервера ИИ (Код ${response.code})"
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