package com.example.aicompanion

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS).build()

    private lateinit var petView: TextView
    private lateinit var statusView: TextView
    private lateinit var chatView: TextView
    private lateinit var input: EditText
    private lateinit var tts: TextToSpeech
    private val history = JSONArray()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ВАШ КЛЮЧ
    private val PART1 = "sk-or-v1-732702b2d0cd72cddcb4bca117787bef6fa64"
    private val PART2 = "632bd6dad98adfbcd139cc8946d"
    
    private val URL = "https://openrouter.ai/api/v1/chat/completions"
    private val MODEL = "meta-llama/llama-3.1-8b-instruct"

    private val SYSTEM_PROMPT = """
        Ты — голосовой ИИ-ассистент. Команды:
        [CMD:open_browser] — браузер
        [CMD:search:ЗАПРОС] — поиск
        [CMD:youtube:ЗАПРОС] — YouTube
        [CMD:open_app:ИМЯ] — открыть приложение (ютуб, телеграм, whatsapp, вк, камера, браузер, калькулятор)
        [CMD:call:НОМЕР] — позвонить
        [CMD:time] — время
        [CMD:date] — дата
        Если не команда — отвечай коротко по-русски.
    """.trimIndent()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        petView = findViewById(R.id.petView)
        statusView = findViewById(R.id.statusView)
        chatView = findViewById(R.id.chatView)
        input = findViewById(R.id.input)
        val quickGrid = findViewById<GridLayout>(R.id.quickGrid)

        findViewById<Button>(R.id.sendBtn).setOnClickListener { send() }
        findViewById<Button>(R.id.voiceBtn).setOnClickListener { startVoice() }
        findViewById<Button>(R.id.stopBtn).setOnClickListener { stopAll() }

        // НАСТРОЙКА ГОЛОСА (Пытаемся сделать как у Джарвиса)
        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                val result = tts.setLanguage(Locale.UK) 
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.language = Locale("ru", "RU")
                }
                
                val voices = tts.voices
                if (voices != null) {
                    for (voice in voices) {
                        if (voice.name.contains("male", ignoreCase = true) || voice.name.contains("en-gb", ignoreCase = true)) {
                            tts.voice = voice
                            break
                        }
                    }
                }
                tts.setPitch(0.8f) 
                tts.setSpeechRate(1.0f)
            }
        }

        val quick = listOf(
            "🌐 Браузер" to "открой браузер",
            "🔍 Поиск" to "найди в интернете ",
            "▶ YouTube" to "найди на ютубе ",
            "📱 Ютуб" to "открой ютуб",
            "✈ Телеграм" to "открой телеграм",
            "💬 WhatsApp" to "открой whatsapp",
            "📞 Звонок" to "позвони ",
            "🕒 Время" to "сколько времени",
            "📅 Дата" to "какое сегодня число"
        )
        quick.forEach { (label, cmd) ->
            val b = Button(this)
            b.text = label
            b.textSize = 11f
            b.setOnClickListener {
                val lowerCmd = cmd.lowercase()
                when {
                    lowerCmd.contains("ютуб") || lowerCmd.contains("youtube") -> { openApp("youtube"); return@setOnClickListener }
                    lowerCmd.contains("телеграм") -> { openApp("телеграм"); return@setOnClickListener }
                    lowerCmd.contains("whatsapp") -> { openApp("whatsapp"); return@setOnClickListener }
                }
                input.setText(cmd)
                if (!cmd.endsWith(" ")) send()
            }
            quickGrid.addView(b)
        }

        statusView.text = "Напиши «привет» или нажми 🎤"

        // ЗАПУСК ФОНОВОГО РЕЖИМА ДЖАРВИСА
        val serviceIntent = Intent(this, JarvisService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // ПРОВЕРКА: ЕСЛИ НАС ВЫЗВАЛИ СЛОВОМ "ДЖАРВИС"
        if (intent?.getStringExtra("VOICE_COMMAND") != null) {
            val commandText = intent.getStringExtra("VOICE_COMMAND")
            if (commandText != null) {
                input.setText(commandText)
                send()
            }
        }
    }

    private fun setPet(mood: String) {
        val m = mapOf(
            "neutral" to "(•‿•)", "happy" to "(^‿^)", "think" to "(・_・)",
            "sad" to "(ಥ_ಥ)", "talk" to "(°o°)", "listen" to "(◉_◉)"
        )
        petView.text = m[mood] ?: "(•‿•)"
    }

    private fun addChat(who: String, text: String) {
        chatView.text = "${chatView.text}\n$who: $text"
    }

    private fun send() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        input.setText("")
        addChat("Вы", t)
        setPet("think")
        statusView.text = "Думаю..."
        askAi(t)
    }

    private fun askAi(userText: String) {
        history.put(JSONObject().put("role", "user").put("content", userText))
        val messages = JSONArray().put(
            JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
        for (i in 0 until history.length()) messages.put(history.get(i))

        val body = JSONObject()
            .put("model", MODEL)
            .put("messages", messages)
            .toString().toRequestBody("application/json".toMediaType())

        val req = Request.Builder()
            .url(URL)
            .addHeader("Authorization", "Bearer ${PART1 + PART2}")
            .addHeader("Content-Type", "application/json")
            .post(body).build()

        scope.launch {
            try {
                val res = withContext(Dispatchers.IO) { client.newCall(req).execute() }
                val json = JSONObject(res.body?.string() ?: "{}")
                val reply = json.getJSONArray("choices")
                    .getJSONObject(0).getJSONObject("message").getString("content")
                history.put(JSONObject().put("role", "assistant").put("content", reply))
                handleReply(reply)
            } catch (e: Exception) {
                statusView.text = "Ошибка: ${e.message}"
                setPet("sad")
                addChat("Система", "Не удалось получить ответ. Проверьте интернет и ключ.")
            }
        }
    }

    private fun handleReply(reply: String) {
        val m = Regex("\\[CMD:([^\\]]+)\\]").find(reply)
        if (m == null) {
            val clean = reply.trim()
            addChat("ИИ", clean)
            statusView.text = clean.take(100)
            setPet("talk")
            speak(clean)
            return
        }
        val cmd = m.groupValues[1]
        val clean = reply.replace(Regex("\\[CMD:[^\\]]+\\]"), "").trim()
        if (clean.isNotEmpty()) addChat("ИИ", clean)
        execCmd(cmd)
    }

    private fun execCmd(cmd: String) {
        when {
            cmd == "open_browser" -> { open("https://google.com"); reply("Открываю браузер.") }
            cmd.startsWith("search:") -> {
                val q = cmd.removePrefix("search:")
                open("https://google.com/search?q=" + Uri.encode(q))
                reply("Ищу: $q")
            }
            cmd.startsWith("youtube:") -> {
                val q = cmd.removePrefix("youtube:")
                open("https://youtube.com/results?search_query=" + Uri.encode(q))
                reply("Ищу на YouTube: $q")
            }
            cmd.startsWith("open_app:") -> openApp(cmd.removePrefix("open_app:").trim())
            cmd.startsWith("call:") -> {
                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + cmd.removePrefix("call:"))))
                reply("Набираю номер.")
            }
            cmd == "time" -> reply("Сейчас " + java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date()))
            cmd == "date" -> reply("Сегодня " + java.text.SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(java.util.Date()))
            else -> reply("Команда: $cmd")
        }
    }

    private fun reply(t: String) {
        addChat("ИИ", t)
        statusView.text = t
        setPet("talk")
        speak(t)
        scope.launch { delay(2000); setPet("happy") }
    }

    private fun open(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: Exception) {}
    }

    private fun openApp(name: String) {
        val map = mapOf(
            "ютуб" to "com.google.android.youtube",
            "youtube" to "com.google.android.youtube",
            "телеграм" to "org.telegram.messenger",
            "telegram" to "org.telegram.messenger",
            "whatsapp" to "com.whatsapp",
            "вк" to "com.vkontakte.android",
            "камера" to "com.android.camera",
            "браузер" to "com.android.chrome",
            "хром" to "com.android.chrome",
            "калькулятор" to "com.google.android.calculator",
            "настройки" to "com.android.settings"
        )
        val pkg = map[name.lowercase()]
        if (pkg != null) {
            val i = packageManager.getLaunchIntentForPackage(pkg)
            if (i != null) { startActivity(i); reply("Открываю $name"); return }
        }
        open("https://google.com/search?q=" + Uri.encode(name))
        reply("Ищу «$name»")
    }

    private fun startVoice() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
        }
        try { startActivityForResult(i, 100) }
        catch (_: Exception) { Toast.makeText(this, "Голос недоступен", Toast.LENGTH_SHORT).show() }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 100 && res == RESULT_OK) {
            val t = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (t != null) { input.setText(t); send() }
        }
    }

    private fun speak(t: String) { tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, "id") }

    private fun stopAll() {
        tts.stop(); statusView.text = "Остановлено."; setPet("neutral")
    }

    override fun onDestroy() {
        scope.cancel(); tts.stop(); tts.shutdown(); super.onDestroy()
    }
}