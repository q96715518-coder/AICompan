package com.example.aicompanion

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.util.Base64
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
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var chatScrollView: ScrollView
    private lateinit var chatContainer: LinearLayout
    private lateinit var inputEditText: EditText
    
    private val chatHistory = JSONArray()
    private var groqToken: String = ""
    private lateinit var memoryFile: File

    private val client = OkHttpClient.Builder().connectTimeout(60, TimeUnit.SECONDS).build()
    private val PERMISSION_REQUEST_CODE = 123
    private val IMAGE_PICK_CODE = 456

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val prefs = getSharedPreferences("JarvisPrefs", MODE_PRIVATE)
        groqToken = prefs.getString("GROQ_TOKEN", "") ?: ""
        
        memoryFile = File(applicationContext.filesDir, "jarvis_memory.txt")
        if (!memoryFile.exists()) memoryFile.createNewFile()

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
            text = "J.A.R.V.I.S. (v0.14.0 Vision)"
            textSize = 18f
            setTextColor(Color.CYAN)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        
        val settingsBtn = Button(this).apply {
            text = "⚙️"
            setBackgroundColor(Color.TRANSPARENT)
            setTextSize(20f)
            setOnClickListener { showSettingsDialog() }
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
            text = "📷" // Изменили на камеру/фото
            setBackgroundColor(Color.TRANSPARENT)
            setTextSize(20f)
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
            setTextSize(20f)
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
        
        addMessageToChat("Джарвис", "Системы готовы. Модуль зрения активирован. Слушаю вас, сэр.", false)
    }

    // --- НАСТРОЙКИ ---
    private fun showSettingsDialog() {
        val options = arrayOf("Вставить API-ключ Groq", "Очистить долговременную память")
        val builder = android.app.AlertDialog.Builder(this)
        builder.setTitle("Настройки")
        builder.setItems(options) { _, which ->
            when (which) {
                0 -> showTokenDialog()
                1 -> clearMemory()
            }
        }
        builder.show()
    }

    private fun showTokenDialog() {
        val builder = android.app.AlertDialog.Builder(this)
        builder.setTitle("Groq API (Llama-3.2 Vision)")
        builder.setMessage("Вставьте API-ключ Groq (gsk_...). Он нужен для распознавания фото.")

        val input = EditText(this).apply {
            setText(groqToken)
            hint = "gsk_..."
        }
        builder.setView(input)

        builder.setPositiveButton("Сохранить") { dialog, _ ->
            groqToken = input.text.toString().trim()
            val prefs = getSharedPreferences("JarvisPrefs", MODE_PRIVATE)
            prefs.edit().putString("GROQ_TOKEN", groqToken).apply()
            addMessageToChat("Система", "Ключ сохранен.", false)
            dialog.dismiss()
        }
        builder.setNegativeButton("Отмена") { dialog, _ -> dialog.dismiss() }
        builder.show()
    }

    private fun clearMemory() {
        memoryFile.writeText("")
        addMessageToChat("Система", "Память очищена.", false)
        speak("Я всё забыл, сэр.")
    }

    private fun readMemory(): String {
        return if (memoryFile.exists()) memoryFile.readText().trim() else ""
    }

    // --- ИНТЕРФЕЙС ---
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

    // --- ВЫБОР ФОТО ---
    private fun openGallery() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        startActivityForResult(intent, IMAGE_PICK_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == IMAGE_PICK_CODE && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                addMessageToChat("Вы", "📷 [Отправлено фото]", true)
                // Конвертируем фото в Base64 для отправки в ИИ
                val base64Image = encodeImageToBase64(uri)
                if (base64Image != null) {
                    askVisionAI(base64Image)
                } else {
                    respondToAI("Не удалось прочитать изображение, сэр.")
                }
            }
        }
    }

    // Конвертация картинки в Base64
    private fun encodeImageToBase64(uri: Uri): String? {
        return try {
            val inputStream: InputStream = contentResolver.openInputStream(uri) ?: return null
            val bytes = inputStream.readBytes()
            inputStream.close()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // --- ОЗВУЧКА ---
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

    // --- РАСПОЗНАВАНИЕ ГОЛОСА ---
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

    // --- КОМАНДЫ ---
    private fun handleCommand(command: String) {
        val lower = command.lowercase(Locale.getDefault())
        when {
            lower.contains("время") -> {
                val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                respondToAI("Сейчас $time", true)
            }
            lower.contains("запомни") -> {
                val fact = command.replace("запомни", "", ignoreCase = true).trim()
                if (fact.isNotEmpty()) {
                    memoryFile.appendText("- $fact\n")
                    respondToAI("Я запомнил: $fact", true)
                }
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

    // --- МОДУЛЬ ЗРЕНИЯ (VISION) ---
    private fun askVisionAI(base64Image: String) {
        if (groqToken.isEmpty()) {
            respondToAI("Сэр, для распознавания фото нужен ключ Groq. Вставьте его в настройках (⚙️).", true)
            return
        }

        val messagesArray = JSONArray()
        messagesArray.put(JSONObject().apply {
            put("role", "system")
            put("content", "Ты — Джарвис. Опиши, что ты видишь на этом фото, кратко и по делу.")
        })

        // Формируем мультимодальный запрос (Текст + Картинка)
        val contentArray = JSONArray()
        contentArray.put(JSONObject().apply {
            put("type", "text")
            put("text", "Что на этом изображении?")
        })
        contentArray.put(JSONObject().apply {
            put("type", "image_url")
            put("image_url", JSONObject().apply {
                put("url", "data:image/jpeg;base64,$base64Image")
            })
        })

        messagesArray.put(JSONObject().apply {
            put("role", "user")
            put("content", contentArray)
        })

        val jsonBody = JSONObject().apply {
            put("model", "llama-3.2-11b-vision-preview") // Бесплатная Vision-модель от Groq
            put("messages", messagesArray)
            put("max_tokens", 300)
        }

        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .addHeader("Authorization", "Bearer $groqToken")
            .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { respondToAI("Ошибка сети при отправке фото.") }
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        try {
                            val answer = JSONObject(response.body!!.string())
                                .getJSONArray("choices").getJSONObject(0)
                                .getJSONObject("message").getString("content")
                            runOnUiThread {
                                addMessageToChat("Джарвис", answer, false)
                                speak(answer)
                            }
                        } catch (e: Exception) {
                            runOnUiThread { respondToAI("Не смог разобрать ответ от Vision-модели.") }
                        }
                    } else {
                        runOnUiThread { respondToAI("Ошибка Vision API. Проверьте ключ. (Код ${response.code})") }
                    }
                }
            }
        })
    }

    // --- УМНЫЙ ТЕКСТОВЫЙ ИИ (С ПАМЯТЬЮ) ---
    private fun askSmartAI(question: String) {
        chatHistory.put(JSONObject().apply { put("role", "user"); put("content", question) })

        val memory = readMemory()
        val memoryPrompt = if (memory.isNotEmpty()) "\nФакты о пользователе:\n$memory" else ""

        if (groqToken.isNotEmpty()) {
            val messagesArray = JSONArray()
            messagesArray.put(JSONObject().apply { 
                put("role", "system"); 
                put("content", "Ты — Джарвис. Обращайся 'сэр'. Отвечай кратко.$memoryPrompt") 
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
                override fun onFailure(call: Call, e: IOException) { runOnUiThread { tryPuterAI(question, memoryPrompt) } }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (response.isSuccessful) {
                            try {
                                val answer = JSONObject(response.body!!.string())
                                    .getJSONArray("choices").getJSONObject(0)
                                    .getJSONObject("message").getString("content")
                                saveAndReply(answer)
                            } catch (e: Exception) { runOnUiThread { tryPuterAI(question, memoryPrompt) } }
                        } else { runOnUiThread { tryPuterAI(question, memoryPrompt) } }
                    }
                }
            })
        } else {
            tryPuterAI(question, memoryPrompt)
        }
    }

    // --- РЕЗЕРВ (PUTER.JS) ---
    private fun tryPuterAI(question: String, memoryPrompt: String) {
        val messagesArray = JSONArray()
        messagesArray.put(JSONObject().apply { 
            put("role", "system"); 
            put("content", "Ты - Джарвис. Отвечай кратко, вежливо.$memoryPrompt") 
        })
        val startIdx = maxOf(0, chatHistory.length() - 4)
        for (i in startIdx until chatHistory.length()) { messagesArray.put(chatHistory.getJSONObject(i)) }

        val jsonBody = JSONObject().apply { put("messages", messagesArray) }
        val request = Request.Builder()
            .url("https://api.puter.com/drivers/call")
            .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { runOnUiThread { respondToAI("Ошибка сети.") } }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        try {
                            val answer = JSONObject(response.body!!.string())
                                .getJSONArray("choices").getJSONObject(0)
                                .getJSONObject("message").getString("content")
                            saveAndReply(answer)
                        } catch (e: Exception) { runOnUiThread { respondToAI("Ошибка обработки.") } }
                    } else { runOnUiThread { respondToAI("Сервер перегружен.") } }
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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != Pa