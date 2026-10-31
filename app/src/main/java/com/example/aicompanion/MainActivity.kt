package com.example.aicompanion

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.AlarmClock
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private val client = OkHttpClient.Builder().connectTimeout(60, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()
    private lateinit var petView: TextView
    private lateinit var statusView: TextView
    private lateinit var chatView: TextView
    private lateinit var input: EditText
    private lateinit var tts: TextToSpeech
    private val history = JSONArray()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val MEMORY_FILE = "jarvis_memory.json"

    private val PART1 = "sk-or-v1-732702b2d0cd72cddcb4bca117787bef6fa64"
    private val PART2 = "632bd6dad98adfbcd139cc8946d"
    private val URL = "https://openrouter.ai/api/v1/chat/completions"
    private val MODEL = "meta-llama/llama-3.1-8b-instruct"

    private val SYSTEM_PROMPT = """
        Ты — голосовой ИИ-ассистент. Команды:
        [CMD:open_browser] — браузер
        [CMD:search:ЗАПРОС] — поиск
        [CMD:open_app:ИМЯ] — открыть приложение
        [CMD:volume_up] — громче
        [CMD:volume_down] — тише
        [CMD:mute] — выключить звук
        [CMD:torch_on] — фонарик вкл
        [CMD:torch_off] — фонарик выкл
        [CMD:alarm:ЧЧ:ММ] — будильник
        [CMD:timer:МИНУТЫ] — таймер
        [CMD:music] — включить музыку
        [CMD:vpn] — включить VPN
        [CMD:note:ТЕКСТ] — сохранить заметку
        [CMD:read_notes] — прочитать заметки
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

        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                tts.language = Locale("ru", "RU")
                val voices = tts.voices
                if (voices != null) {
                    for (voice in voices) {
                        if (voice.name.contains("female", ignoreCase = true)) { tts.voice = voice; break }
                    }
                }
                tts.setPitch(1.1f)
                tts.setSpeechRate(0.95f)
            }
        }

        val quick = listOf(
            "🌐 Браузер" to "открой браузер", "🔦 Фонарик" to "включи фонарик",
            "🔊 Громче" to "сделай громче", "🔉 Тише" to "сделай тише",
            "📱 Ютуб" to "открой ютуб", "✈ Телеграм" to "открой телеграм",
            "🎵 Музыка" to "включи музыку", "📝 Заметка" to "запиши заметку ",
            "⏰ Таймер" to "таймер на 5 минут", "🕒 Время" to "сколько времени"
        )
        quick.forEach { (label, cmd) ->
            val b = Button(this)
            b.text = label
            b.textSize = 11f
            b.setOnClickListener {
                val lowerCmd = cmd.lowercase()
                when {
                    lowerCmd.contains("ютуб") -> { openApp("youtube"); return@setOnClickListener }
                    lowerCmd.contains("телеграм") -> { openApp("телеграм"); return@setOnClickListener }
                    lowerCmd.contains("фонарик") -> { toggleTorch(true); reply("Фонарик включен."); return@setOnClickListener }
                    lowerCmd.contains("громче") -> { adjustVolume(true); reply("Сделала громче."); return@setOnClickListener }
                    lowerCmd.contains("тише") -> { adjustVolume(false); reply("Сделала тише."); return@setOnClickListener }
                    lowerCmd.contains("музыка") -> { openMusic(); reply("Включаю музыку."); return@setOnClickListener }
                }
                input.setText(cmd)
                if (!cmd.endsWith(" ")) send()
            }
            quickGrid.addView(b)
        }

        statusView.text = "Напиши «привет» или нажми 🎤"
        loadMemory()
    }

    private fun saveMemory() { try { File(filesDir, MEMORY_FILE).writeText(history.toString()) } catch (e: Exception) {} }
    private fun loadMemory() {
        try {
            val file = File(filesDir, MEMORY_FILE)
            if (file.exists()) {
                val jsonArray = JSONArray(file.readText())
                for (i in 0 until jsonArray.length()) {
                    history.put(jsonArray.get(i))
                    val msg = jsonArray.getJSONObject(i)
                    if (msg.getString("role") == "user") addChat("Вы", msg.getString("content"))
                    else addChat("ИИ", msg.getString("content"))
                }
            }
        } catch (e: Exception) {}
    }
    private fun setPet(mood: String) {
        val m = mapOf("neutral" to "(•‿•)", "happy" to "(^‿^)", "think" to "(・_・)", "sad" to "(ಥ_ಥ)", "talk" to "(°o°)", "listen" to "(◉_◉)")
        petView.text = m[mood] ?: "(•‿•)"
    }
    private fun addChat(who: String, text: String) { chatView.text = "${chatView.text}\n$who: $text" }
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
        saveMemory()
        val currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val currentDate = SimpleDateFormat("EEEE, d MMMM yyyy", Locale("ru")).format(Date())
        val fullPrompt = SYSTEM_PROMPT + "\nСейчас время: $currentTime, дата: $currentDate."
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", fullPrompt))
        for (i in 0 until history.length()) messages.put(history.get(i))
        val body = JSONObject().put("model", MODEL).put("messages", messages).toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(URL).addHeader("Authorization", "Bearer ${PART1 + PART2}").post(body).build()

        scope.launch {
            try {
                val res = withContext(Dispatchers.IO) { client.newCall(req).execute() }
                val json = JSONObject(res.body?.string() ?: "{}")
                val reply = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
                history.put(JSONObject().put("role", "assistant").put("content", reply))
                saveMemory()
                handleReply(reply)
            } catch (e: Exception) {
                statusView.text = "Ошибка: ${e.message}"; setPet("sad"); addChat("Система", "Не удалось получить ответ.")
            }
        }
    }
    private fun handleReply(reply: String) {
        val m = Regex("\\[CMD:([^\\]]+)\\]").find(reply)
        if (m == null) {
            val clean = reply.trim()
            addChat("ИИ", clean); statusView.text = clean.take(100); setPet("talk"); speak(clean); return
        }
        val cmd = m.groupValues[1]
        val clean = reply.replace(Regex("\\[CMD:[^\\]]+\\]"), "").trim()
        if (clean.isNotEmpty()) addChat("ИИ", clean)
        execCmd(cmd)
    }
    private fun execCmd(cmd: String) {
        when {
            cmd == "open_browser" -> { open("https://google.com"); reply("Открываю браузер.") }
            cmd.startsWith("search:") -> { val q = cmd.removePrefix("search:"); open("https://google.com/search?q=${Uri.encode(q)}"); reply("Ищу: $q") }
            cmd.startsWith("open_app:") -> openApp(cmd.removePrefix("open_app:").trim())
            cmd == "volume_up" -> { adjustVolume(true); reply("Сделала громче.") }
            cmd == "volume_down" -> { adjustVolume(false); reply("Сделала тише.") }
            cmd == "mute" -> { setMute(); reply("Звук выключен.") }
            cmd == "torch_on" -> { toggleTorch(true); reply("Фонарик включен.") }
            cmd == "torch_off" -> { toggleTorch(false); reply("Фонарик выключен.") }
            cmd.startsWith("alarm:") -> { val time = cmd.removePrefix("alarm:").split(":"); setAlarm(time.getOrNull(0)?.toIntOrNull() ?: 7, time.getOrNull(1)?.toIntOrNull() ?: 0); reply("Будильник поставлен.") }
            cmd.startsWith("timer:") -> { val mins = cmd.removePrefix("timer:").toIntOrNull() ?: 5; setTimer(mins); reply("Таймер на $mins минут.") }
            cmd == "music" -> { openMusic(); reply("Включаю музыку.") }
            cmd == "vpn" -> { openVpn(); reply("Открываю VPN.") }
            cmd.startsWith("note:") -> { saveNote(cmd.removePrefix("note:")); reply("Заметка сохранена.") }
            cmd == "read_notes" -> reply(readNotes())
            cmd.startsWith("call:") -> { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + cmd.removePrefix("call:")))); reply("Набираю номер.") }
            else -> reply("Команда: $cmd")
        }
    }
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
        try { val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager; cm.setTorchMode(cm.cameraIdList[0], on) } catch (e: Exception) {}
    }
    private fun setAlarm(h: Int, m: Int) {
        val i = Intent(AlarmClock.ACTION_SET_ALARM).apply { putExtra(AlarmClock.EXTRA_HOUR, h); putExtra(AlarmClock.EXTRA_MINUTES, m); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        startActivity(i)
    }
    private fun setTimer(mins: Int) {
        val i = Intent(AlarmClock.ACTION_SET_TIMER).apply { putExtra(AlarmClock.EXTRA_LENGTH, mins * 60); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        startActivity(i)
    }
    private fun openMusic() {
        val i = Intent("android.media.action.MEDIA_PLAY_FROM_SEARCH").apply { putExtra(android.app.SearchManager.QUERY, "музыка"); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        try { startActivity(i) } catch (e: Exception) { open("https://music.yandex.ru") }
    }
    private fun openVpn() {
        val i = Intent("android.net.vpn.SETTINGS").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        try { startActivity(i) } catch (e: Exception) {}
    }
    private fun saveNote(text: String) { try { File(filesDir, "notes.txt").appendText("\n$text") } catch (e: Exception) {} }
    private fun readNotes(): String {
        return try { val file = File(filesDir, "notes.txt"); if (file.exists()) file.readText().takeLast(200) else "Заметок нет." } catch (e: Exception) { "Ошибка" }
    }
    private fun reply(t: String) {
        addChat("ИИ", t); statusView.text = t; setPet("talk"); speak(t); scope.launch { delay(2000); setPet("happy") }
    }
    private fun open(url: String) { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {} }
    private fun openApp(name: String) {
        val map = mapOf("ютуб" to "com.google.android.youtube", "youtube" to "com.google.android.youtube", "телеграм" to "org.telegram.messenger", "whatsapp" to "com.whatsapp", "вк" to "com.vkontakte.android", "камера" to "com.android.camera", "браузер" to "com.android.chrome", "калькулятор" to "com.google.android.calculator")
        val pkg = map[name.lowercase()]
        if (pkg != null) { val i = packageManager.getLaunchIntentForPackage(pkg); if (i != null) { startActivity(i); reply("Открываю $name"); return } }
        open("https://google.com/search?q=" + Uri.encode(name)); reply("Ищу «$name»")
    }
    private fun startVoice() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply { putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM); putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU") }
        try { startActivityForResult(i, 100) } catch (_: Exception)