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
    
    // ПАМЯТЬ ДЖАРВИСА (История диалога)
    private val chatHistory = mutableListOf<String>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
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
            text = "v0.9.8 (Память и Интеллект)"
            textSize = 14f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(0, 5, 0, 20)
        }

        statusText = TextView(this).apply {
            text = "Система готова к диалогу..."
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 20)
        }

        inputEditText = EditText(this).apply {
            hint = "Напишите или спросите..."
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setBackgroundColor(Color.parseColor("#333333"))
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(20, 20, 20, 20)
        }

        val sendButton = Button(this).apply {
            text = "Отправить текст"
            setBackgroundColor(Color.parseColor("#4CAF50"))
            setOnClickListener {
                val textCommand = inputEditText.text.toString().trim()
                if (textCommand.isNotEmpty()) {
                    statusText.text = "Вы: $textCommand"
                    handleCommand(textCommand)
                    inputEditText.text.clear()
                }
            }
        }

        val voiceButton = Button(this).apply {
            text = "Сказать голосом 🎤"
            setBackgroundColor(Color.CYAN)
            setTextColor(Color.BLACK)
            setOnClickListener {
                statusText.text = "Слушаю..."
                checkPermissionsAndStartListening()
            }
        }

        val clearMemoryButton = Button(this).apply {
            text = "Очистить память ИИ"
            setBackgroundColor(Color.RED)
            setOnClickListener {
                chatHistory.clear()
                statusText.text = "Память очищена."
                speak("История нашего общения удалена.")
            }
        }

        layout.addView(title)
        layout.addView(versionText)
        layout.addView(statusText)
        layout.addView(inputEditText)
        layout.addView(sendButton)
        layout.addView(voiceButton)
        layout.addView(clearMemoryButton)

        setContentView(layout)

        initTts()
        initSpeechRecognizer()
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

    // --- РАСПОЗНАВАНИЕ ---
    private fun initSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { statusText.text = "Слушаю..." }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { statusText.text = "Обрабатываю..." }
                override fun onError(error: Int) { statusText.text = "Не расслышал." }

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
        val lowerCommand = command.lowercase(Locale.getDefault())
        when {
            lowerCommand.contains("время") -> {
                val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                speak("Сейчас $time")
                statusText.text = "Время: $time"
            }
            lowerCommand.contains("дата") -> {
                val date = SimpleDateFormat("d MMMM yyyy", Locale("ru")).format(Date())
                speak("Сегодня $date")
                statusText.text = "Дата: $date"
            }
            lowerCommand.contains("ютуб") -> {
                speak("Открываю YouTube")
                openApp("com.google.android.youtube", "https://youtube.com")
            }
            // Таймер
            lowerCommand.contains("таймер") -> {
                speak("На сколько минут поставить таймер?")
                val minutes = lowerCommand.filter { it.isDigit() }.toIntOrNull() ?: 1
                startTimer(minutes)
            }
            // В остальных случаях - Умный диалог с ИИ
            else -> {
                askSmartAI(command)
            }
        }
    }

    private fun openApp(packageName: String, fallbackUrl: String? = null) {
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } else if (fallbackUrl != null) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else {
                speak("Приложение не установлено.")
            }
        } catch (e: Exception) {}
    }

    private fun startTimer(minutes: Int) {
        timer?.cancel()
        val millisInFuture = minutes * 60 * 1000L
        speak("Таймер на $minutes минут запущен")
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

    // --- МОЗГ И ПАМЯТЬ (SMART AI) ---
    private fun askSmartAI(question: String) {
        statusText.text = "Думаю..."
        
        // 1. Формируем системный характер (Промпт)
        val systemPrompt = "Ты - Джарвис, продвинутый ИИ-ассистент. Ты вежлив, учтив, называешь пользователя 'сэр' или 'шеф'. Ты обладаешь легким британским юмором. Отвечай кратко, информативно и по делу, максимум 3-4 предложения."
        
        // 2. Формируем историю диалога (Берем последние 4 сообщения, чтобы не перегрузить сервер)
        val contextHistory = if (chatHistory.size > 4) {
            chatHistory.takeLast(4).joinToString("\n")
        } else {
            chatHistory.joinToString("\n")
        }

        // 3. Собираем всё вместе
        val fullPrompt = "$systemPrompt\n\nИстория диалога:\n$contextHistory\nПользователь: $question\nДжарвис:"
        
        val encodedPrompt = Uri.encode(fullPrompt)
        val url = "https://text.pollinations.ai/$encodedPrompt"
        
        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", "Mozilla/5.0 (Android)")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    speak("Связь с сервером потеряна, сэр.")
                    statusText.text = "Ошибка сети"
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        val answer = response.body?.string()?.trim() ?: "Я не знаю, что ответить."
                        
                        // 4. СОХРАНЯЕМ В ПАМЯТЬ
                        chatHistory.add("Пользователь: $question")
                        chatHistory.add("Джарвис: $answer")

                        runOnUiThread {
                            statusText.text = "Джарвис: $answer"
                            speak(answer)
                        }
                    } else {
                        runOnUiThread {
                            speak("Нейросеть перегружена. Попробуйте еще раз.")
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