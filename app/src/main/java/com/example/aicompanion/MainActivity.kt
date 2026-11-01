package com.example.aicompanion

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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

    // Цвета
    private val colorBg = Color.parseColor("#0F1115")
    private val colorHeader = Color.parseColor("#1A1D24")
    private val colorUserBubble = Color.parseColor("#2563EB")
    private val colorAiBubble = Color.parseColor("#2A2D35")
    private val colorInputBg = Color.parseColor("#1A1D24")
    private val colorAccent = Color.parseColor("#38BDF8")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val prefs = getSharedPreferences("JarvisPrefs", MODE_PRIVATE)
        groqToken = prefs.getString("GROQ_TOKEN", "") ?: ""
        
        memoryFile = File(applicationContext.filesDir, "jarvis_memory.txt")
        if (!memoryFile.exists()) memoryFile.createNewFile()

        // --- ГЛАВНЫЙ МАКЕТ ---
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colorBg)
        }

        // --- ШАПКА ---
        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(colorHeader)
            setPadding(40, 40, 40, 40)
            gravity = Gravity.CENTER_VERTICAL
        }
        
        val titleLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val title = TextView(this).apply {
            text = "J.A.R.V.I.S."
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
        }
        
        val status = TextView(this).apply {
            text = "● Онлайн (v0.14.1)"
            textSize = 12f
            setTextColor(colorAccent)
        }

        titleLayout.addView(title)
        titleLayout.addView(status)

        val settingsBtn = TextView(this).apply {
            text = "⚙️"
            textSize = 24f
            setPadding(20, 20, 20, 20)
            setOnClickListener { showSettingsDialog() }
        }

        headerLayout.addView(titleLayout)
        headerLayout.addView(settingsBtn)

        // --- ОБЛАСТЬ ЧАТА ---
        chatScrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            isVerticalScrollBarEnabled = false
        }
        chatContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 30, 30, 30)
        }
        chatScrollView.addView(chatContainer)

        // --- ПАНЕЛЬ ВВОДА ---
        val inputContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(colorInputBg)
            setPadding(30, 20, 30, 30)
            gravity = Gravity.BOTTOM
        }

        val inputBackground = GradientDrawable().apply {
            setColor(Color.parseColor("#2A2D35"))
            cornerRadius = 60f
        }

        val editTextLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = inputBackground
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(20, 0, 20, 0)
        }

        val attachBtn = TextView(this).apply {
            text = "📷"
            textSize = 20f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 20, 0)
            setOnClickListener { openGallery() }
        }

        inputEditText = EditText(this).apply {
            hint = "Написать сообщение..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 4
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        editTextLayout.addView(attachBtn)
        editTextLayout.addView(inputEditText)

        val micBtn = TextView(this).apply {
            text = "🎤"
            textSize = 24f
            setTextColor(colorAccent)
            setPadding(30, 20, 30, 20)
            setOnClickListener { checkPermissionsAndStartListening() }
        }

        val sendBtnBackground = GradientDrawable().apply {
            setColor(colorUserBubble)
            shape = GradientDrawable.OVAL
        }
        val sendBtn = TextView(this).apply {
            text = "➤"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = sendBtnBackground
            val size = 120
            layoutParams = LinearLayout.LayoutParams(size, size)
            setOnClickListener {
                val text = inputEditText.text.toString().trim()
                if (text.isNotEmpty()) {
                    addMessageToChat("Вы", text, true)
                    handleCommand(text)
                    inputEditText.text.clear()
                }
            }
        }

        inputContainer.addView(editTextLayout)
        inputContainer.addView(micBtn)
        inputContainer.addView(sendBtn)

        mainLayout.addView(headerLayout)
        mainLayout.addView(chatScrollView)
        mainLayout.addView(inputContainer)

        setContentView(mainLayout)

        initTts()
        initSpeechRecognizer()
        
        addMessageToChat("Джарвис", "Все системы в норме. Чем могу помочь, сэр?", false)
    }

    private fun addMessageToChat(sender: String, message: String, isUser: Boolean) {
        val messageLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (isUser) Gravity.END else Gravity.START
            setPadding(0, 10, 0, 10)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(if (isUser) 60 else 0, 0, if (isUser) 0 else 60, 0)
            }
        }

        val bubbleLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(35, 25, 35, 25)
            background = GradientDrawable().apply {
                setColor(if (isUser) colorUserBubble else colorAiBubble)
                cornerRadius = 50f
                if (isUser) {
                    cornerRadii = floatArrayOf(50f, 50f, 50f, 50f, 50f, 50f, 10f, 10f)
                } else {
                    cornerRadii = floatArrayOf(50f, 50f, 50f, 50f, 10f, 10f, 50f, 50f)
                }
            }
        }

        val textView = TextView(this).apply {
            text = message
            setTextColor(Color.WHITE)
            textSize = 16f
            lineSpacingMultiplier = 1.2f
        }

        bubbleLayout.addView(textView)
        messageLayout.addView(bubbleLayout)
        chatContainer.addView(messageLayout)

        chatScrollView.post { chatScrollView.fullScroll(View.FOCUS_DOWN) }
    }

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
        builder.setMessage("Вставьте API-ключ Groq (gsk_...)")

        val input = EditText(this).apply {
            setText(groqToken)
            hint = "gsk_..."
            setTextColor(Color.BLACK)
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

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        startActivityForResult(intent, IMAGE_PICK_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == IMAGE_PICK_CODE && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                addMessageToChat("Вы", "📷 [Фото]", true)
                val base64Image = encodeImageToBase64(uri)
                if (base64Image != null) {
                    askVisionAI(base64Image)
                } else {
                    respondToAI("Не удалось прочитать фото.")
                }
            }
        }
    }

    private fun encodeImageToBase64(uri: Uri): String? {
        return try {
            val inputStream: InputStream = contentResolver.openInputStream(uri) ?: return null
            val bytes = inputStream.readBytes()
            inputStream.close()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) { null }
    }

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
                override fun onError(error: Int) { addMessageToChat("Система", "Не расслышал.", false) }

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
                respondToAI("Открываю YouTube.", true)
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://youtube.com")))
            }
            else -> askSmartAI(command)
        }
    }

    private fun respondToAI(text: String, speakIt: Boolean = false) {
        addMessageToChat("Джарвис", text, false)
        if (speakIt) speak(text)
    }

    private fun askVisionAI(base64Image: String) {
        if (groqToken.isEmpty()) {
            respondToAI("Для зрения нужен ключ Groq (в настройках).", true)
            return
        }

        val messagesArray = JSONArray()
        messagesArray.put(JSONObject().apply {
            put("role", "system")
            put("content", "Ты — Джарвис. Опиши, что ты видишь на этом фото, кратко.")
        })

        val contentArray = JSONArray()
        contentArray.put(JSONObject().apply { put("type", "text"); put("text", "Что на изображении?") })
        contentArray.put(JSONObject().apply {
            put("type", "image_url")
            put("image_url", JSONObject().apply { put("url", "data:image/jpeg;base64,$base64Image") })
        })

        messagesArray.put(JSONObject().apply { put("role", "user"); put("content", contentArray) })

        val jsonBody = JSONObject().apply {
            put("model", "llama-3.2-11b-vision-preview")
            put("messages", messagesArray)
        }

        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .addHeader("Authorization", "Bearer $groqToken")
            .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { respondToAI("Ошибка сети.") }
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
                            runOnUiThread { respondToAI("Ошибка Vision.") }
                        }
                    } else {
                        runOnUiThread { respondToAI("Ошибка API (${response.code})") }
                    }
                }
            }
        })
    }

    private fun askSmartAI(question: String) {
        chatHistory.put(JSONObject().apply { put("role", "user"); put("content", question) })
        val memory = readMemory()
        val memoryPrompt = if (memory.isNotEmpty()) "\nФакты:\n$memory" else ""

        if (groqToken.isNotEmpty()) {
            val messagesArray = JSONArray()
            messagesArray.put(JSONObject().apply { 
                put("role", "system"); 
                put("content", "Ты — Джарвис. Обращайся 'сэр'. Отвечай кратко.$memoryPrompt") 
            })
            val startIdx = maxOf(0, chatHistory.length() - 6)
            for (i in startIdx until chatHistory.length()) { messagesArray.put(chatHistory.getJSONObject(i)) }

            val jsonBody = JSONObject().apply { put("model", "llama3-70b-8192"); put("messages", messagesArray) }

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
                                val answer = JSONObject(response.body!!.string()).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
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

    private fun tryPuterAI(question: String, memoryPrompt: String) {
        val messagesArray = JSONArray()
        messagesArray.put(JSONObject().apply { put("role", "system"); put("content", "Ты - Джарвис. Отвечай кратко.$memoryPrompt") })
        val startIdx = maxOf(0, chatHistory.length() - 4)
        for (i in startIdx until chatHistory.length()) { messagesArray.put(chatHistory.getJSONObject(i)) }

        val jsonBody = JSONObject().apply { put("messages", messagesArray) }
        val request = Request.Builder()
            .url("https://api.puter.com/drivers/call")
            .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { runOnUiThread { respondToAI("Ошибка сети.") } }
            override fun onRespons