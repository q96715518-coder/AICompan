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
    
    // Память и настройки
    private val chatHistory = JSONArray()
    private var groqToken: String = ""
    
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).build()
    private val PERMISSION_REQUEST_CODE = 123
    private val IMAGE_PICK_CODE = 456

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Загружаем ключ Groq (если он есть)
        val prefs = getSharedPreferences("JarvisPrefs", MODE_PRIVATE)
        groqToken = prefs.getString("GROQ_TOKEN", "") ?: ""

        // --- ИНТЕРФЕЙС ---
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
            text = "J.A.R.V.I.S. (v0.12.0)"
            textSize = 20f
            setTextColor(Color.CYAN)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        
        val settingsBtn = Button(this).apply {
            text = "⚙️"
            setBackgroundColor(Color.TRANSPARENT)
            setTextSize(20f)
            setOnClickListener { showTokenDialog() }
        }

        headerLayout.addView(title)
        headerLayout.addView(settingsBtn)

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
        
        addMessageToChat("Джарвис", "Системы готовы. Уровень интеллекта: Максимальный (Каскадный). Слушаю вас, сэр.", false)
    }

    // --- НАСТРОЙКИ GROQ ---
    private fun showTokenDialog() {
        val builder = android.app.AlertDialog.Builder(this)
        builder.setTitle("Настройки Groq (Llama-3)")
        builder.setMessage("Вставьте API-ключ Groq для максимального интеллекта.\n(Оставьте пустым для использования бесплатных прокси).")

        val input = EditText(this).apply {
            setText(groqToken)
            hint = "gsk_..."
            inputType = InputType.TYPE_CLASS_TEXT
        }
        builder.setView(input)

        builder.setPositiveButton("Сохранить") { dialog, _ ->
            groqToken = input.text.toString().trim()
            val prefs = getSharedPreferences("JarvisPrefs", MODE_PRIVATE)
            prefs.edit().putString("GROQ_TOKEN", groqToken).apply()
            addMessageToChat("Система", "Настройки сохранены.", false)
            dialog.dismiss()
        }
        builder.setNegativeButton("Отмена") { dialog, _ -> dialog.dismiss() }
        builder.show()
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
            data?.data?.let {
                addMessageToChat("Вы", "📷 [Фото]", true)
                askSmartAI("Я отправил фото.")
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

    // --- УРОВЕНЬ 1: GROQ (Самый умный) ---
    private fun askSmartAI(question: String) {
        chatHistory.put(JSONObject().apply { put("role", "user"); put("content", question) })

        if (groqToken.isNotEmpty()) {
            val messagesArray = JSONArray()
            messagesArray.put(JSONObject().apply { 
                put("role", "system"); 
                put("content", "Ты — ИИ-ассистент Джарвис. Обращайся 'сэр'. Отвечай кратко.") 
            })
            val startIdx = maxOf(0, chatHistory.length() - 6)
            for (i in startIdx until chatHistory.length()) { messagesArray.put(chatHistory.getJSONObject(i)) }

            val jsonBody = JSONObject().apply {
                put("model", "llama3-70b-8192")
                put("messages", messagesArray)
            }

            val request = Request.Builder()
                .url("https://api.groq.com/openai/v1/chat/completions")
                .addHeader("Authorization", "Bearer $groqToken")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { runOnUiThread { tryPuterAI(question) } }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (response.isSuccessful) {
                            try {
                                val answer = JSONObject(response.body!!.string())
                                    .getJSONArray("choices").getJSONObject(0)
                                    .getJSONObject("message").getString("content")
                                saveAndReply(answer)
                            } catch (e: Exception) { runOnUiThread { tryPuterAI(question) } }
                        } else { runOnUiThread { tryPuterAI(question) } }
                    }
                }
            })
        } else {
            tryPuterAI(question) // Если ключа нет, сразу идем на уровень 2
        }
    }

    // --- УРОВЕНЬ 2: PUTER.JS (Бесплатный GPT-4) ---
    private fun tryPuterAI(question: String) {
        val messagesArray = JSONArray()
        messagesArray.put(JSONObject().apply { put("role", "system"); put("content", "Ты - Джарвис. Отвечай кратко, вежливо.") })
        val startIdx = maxOf(0, chatHistory.length() - 4)
        for (i in startIdx until chatHistory.length()) { messagesArray.put(chatHistory.getJSONObject(i)) }

        val jsonBody = JSONObject().apply { put("messages", messagesArray) }
        val request = Request.Builder()
            .url("https://api.puter.com/drivers/call")
            .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { runOnUiThread { tryFallbackAI(question) } }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        try {
                            val answer = JSONObject(response.body!!.string())
                                .getJSONArray("choices").getJSONObject(0)
                                .getJSONObject("message").getString("content")
                            saveAndReply(answer)
                        } catch (e: Exception) { runOnUiThread { tryFallbackAI(question) } }
                    } else { runOnUiThread { tryFallbackAI(question) } }
                }
            }
        })
    }

    // --- УРОВЕНЬ 3: POLLINATIONS (Самый надежный резерв) ---
    private fun tryFallbackAI(question: String) {
        val url = "https://text.pollinations.ai/${Uri.encode("Ты Джарвис. Отвечай кратко. Вопрос: $question")}"
        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { respondToAI("Все сервера ИИ недоступны, сэр. Проверьте интернет.") }
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        val answer = response.body?.string()?.trim() ?: "Нет ответа."
                        saveAndReply(answer)
                    } else {
                        runOnUiThread { respondToAI("Нейросети перегружены, сэр.") }
                    }
                }
            }
        })
    }

    private fun saveAndReply(answer: String) {
        chatHistory.put(JSONObject().apply { put("role", "assistant"); put("content", answer) })
        runOnUiThread {
            addMessageToChat("Джарвис", answer, false)
            speak(answer)
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