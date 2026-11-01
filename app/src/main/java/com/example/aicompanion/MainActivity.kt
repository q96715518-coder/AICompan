package com.example.aicompanion

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var chatScrollView: ScrollView
    private lateinit var chatContainer: LinearLayout
    private lateinit var inputEditText: EditText
    private var timer: CountDownTimer? = null
    
    // Настройки API (ВСТАВЬ СВОЙ ТОКЕН СЮДА!)
    private val DEEPINFRA_TOKEN = "ВСТАВЬ_СЮДА_СВОЙ_ТОКЕН"
    
    // Умная память (контекст диалога)
    private val chatHistory = JSONArray()

    private val client = OkHttpClient.Builder().connectTimeout(60, TimeUnit.SECONDS).build()
    private val PERMISSION_REQUEST_CODE = 123
    private val IMAGE_PICK_CODE = 456

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // --- ИНТЕРФЕЙС (Мессенджер) ---
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
        }

        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(30, 30, 30, 30)
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "J.A.R.V.I.S. (v0.11.0)"
            textSize = 20f
            setTextColor(Color.CYAN)
            setTypeface(null, Typeface.BOLD)
        }
        headerLayout.addView(title)

        chatScrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        chatContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
        }
        chatScrollView.addView(chatContainer)

        val inputLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(15, 15, 15, 15)
            gravity = Gravity.CENTER_VERTICAL
        }

        val attachBtn = Button(this).apply {
            text = "📎"
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { openGallery() }
        }

        inputEditText = EditText(this).apply {
            hint = "Сообщение..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2C2C2C"))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setPadding(20, 15, 20, 15)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(10, 0, 10, 0)
            }
        }

        val micBtn = Button(this).apply {
            text = "🎤"
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { checkPermissionsAndStartListening() }
        }

        val sendBtn = Button(this).apply {
            text = "➤"
            setBackgroundColor(Color.parseColor("#007AFF"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                val text = inputEditText.text.toString().trim()
                if (text.isNotEmpty()) {
                    addMessageToChat("Вы", text, true)
                    handleCommand(text)
                    inputEditText.text.clear()
                }
            }
        }

        inputLayout.addView(attachBtn)
        inputLayout.addView(inputEditText)
        inputLayout.addView(micBtn)
        inputLayout.addView(sendBtn)

        mainLayout.addView(headerLayout)
        mainLayout.addView(chatScrollView)
        mainLayout.addView(inputLayout)

        setContentView(mainLayout)

        initTts()
        initSpeechRecognizer()
        
        addMessageToChat("Джарвис", "Все системы в норме. Мозг обновлен до Llama-3. Слушаю вас, сэр.", false)
    }

    // --- МЕТОДЫ ИНТЕРФЕЙСА ---
    private fun addMessageToChat(sender: String, message: String, isUser: Boolean) {
        val messageLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (isUser) Gravity.END else Gravity.START
            setPadding(0, 10, 0, 10)
        }

        val bubbleLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 20, 30, 20)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(if (isUser) Color.parseColor("#007AFF") else Color.parseColor("#2C2C2C"))
                cornerRadius = 40f
            }
        }

        val textView = TextView(this).apply {
            text = message
            setTextColor(Color.WHITE)
            textSize = 16f
        }

        bubbleLayout.addView(textView)
        messageLayout.addView(bubbleLayout)
        chatContainer.addView(messageLayout)

        chatScrollView.post { chatScrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        startActivityForResult(intent, IMAGE_PICK_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == IMAGE_PICK_CODE && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                addMessageToChat("Вы", "📷 [Отправлено фото]", true)
                askSmartAI("Я отправил тебе фото. Скажи, что ты видишь? (Пока ты не умеешь видеть, просто скажи, что модуль зрения в разработке, но ты рад меня слышать).")
            }
        }
    }

    // --- ОЗВУЧКА И РАСПОЗНАВАНИЕ ---
    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("ru", "RU")
                tts?.setPitch(0.85f)
            }
        }
    }

    fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "JarvisId")
    }

    private fun initSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) { addMessageToChat("Система", "Не расслышал вас.", false) }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val command = matches[0]
                        addMessageToChat("Вы (Голос)", command, true)
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
        val lower = command.lowercase(Locale.getDefault())
        when {
            lower.contains("время") -> {
                val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                respondToAI("Сейчас $time", true)
            }
            lower.contains("таймер") -> {
                val minutes = lower.filter { it.isDigit() }.toIntOrNull() ?: 1
                startTimer(minutes)
            }
            lower.contains("ютуб") -> {
                respondToAI("Открываю YouTube, сэр.", true)
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://youtube.com")))
            }
            else -> {
                askSmartAI(command)
            }
        }
    }

    private fun respondToAI(text: String, speakIt: Boolean = false) {
        addMessageToChat("Джарвис", text, false)
        if (speakIt) speak(text)
    }

    private fun startTimer(minutes: Int) {
        timer?.cancel()
        respondToAI("Таймер на $minutes минут запущен.", true)
        timer = object : CountDownTimer(minutes * 60 * 1000L, 1000) {
            override fun onTick(millisUntilFinished: Long) {}
            override fun onFinish() { respondToAI("Сэр, время вышло!", true) }
        }.start()
    }

    // --- САМЫЙ УМНЫЙ МОЗГ (DEEPINFRA LLAMA-3 70B) ---
    private fun askSmartAI(question: String) {
        if (DEEPINFRA_TOKEN == "d4fbae33623d0be8296291eba362ad65") {
            respondToAI("Сэр, вы забыли вставить API-токен в код.", true)
            return
        }

        try {
            // 1. Добавляем сообщение пользователя в память
            val userMessage = JSONObject().apply {
                put("role", "user")
                put("content", question)
            }
            chatHistory.put(userMessage)

            // Ограничиваем память последними 10 сообщениями, чтобы не перегружать сервер
            val messagesArray = JSONArray()
            
            // Системный промпт (Характер)
            messagesArray.put(JSONObject().apply {
                put("role", "system")
                put("content", "Ты — продвинутый ИИ-ассистент Джарвис. Ты вежлив, умен, обладаешь легким британским юмором. Обращайся к пользователю 'сэр'. Отвечай на русском языке, развернуто, но без лишней воды.")
            })

            // Добавляем историю (последние 10 сообщений)
            val startIdx = maxOf(0, chatHistory.length() - 10)
            for (i in startIdx until chatHistory.length()) {
                messagesArray.put(chatHistory.getJSONObject(i))
            }

            // 2. Формируем JSON для запроса
            val jsonBody = JSONObject().apply {
                put("model", "meta-llama/Meta-Llama-3-70B-Instruct")
                put("messages", messagesArray)
                put("max_tokens", 300)
                put("temperature", 0.7)
            }

            val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("https://api.deepinfra.com/v1/openai/chat/completions")
                .addHeader("Authorization", "Bearer $DEEPINFRA_TOKEN")
                .post(requestBody)
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    runOnUiThread { respondToAI("Ошибка соединения, сэр. Проверьте интернет.") }
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (response.isSuccessful) {
                            val responseData = response.body?.string()
                            try {
                                val jsonObject = JSONObject(responseData!!)
                                val answer = jsonObject.getJSONArray("choices")
                                    .getJSONObject(0)
                                    .getJSONObject("message")
                                    .getString("content")

                                // Сохраняем ответ Джарвиса в память
                                chatHistory.put(JSONObject().apply {
                                    put("role", "assistant")
                                    put("content", answer)
                                })

                                runOnUiThread {
                                    addMessageToChat("Джарвис", answer, false)
                                    speak(answer)
                                }
                            } catch (e: Exception) {
                                runOnUiThread { respondToAI("Не смог разобрать ответ нейросети.") }
                            }
                        } else {
                            runOnUiThread { respondToAI("Сервер ИИ перегружен. Код: ${response.code}") }
                        }
                    }
                }
            })
        } catch (e: Exception) {
            respondToAI("Произошла ошибка при формировании запроса.")
        }
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
        if (requestCode == PERMISSION_REQUEST_CODE && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startListening()
        }
    }

    override fun onDestroy() {
        tts?.shutdown()
        speechRecognizer?.destroy()
        timer?.cancel()
        super.onDestroy()
    }
}