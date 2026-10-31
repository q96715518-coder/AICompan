package com.example.aicompanion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private val PERMISSION_REQUEST_CODE = 123

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // ВАЖНО: Если у тебя есть файл разметки, раскомментируй строку ниже!
        // Если ты создавал кнопку программно, оставь как есть.
        setContentView(R.layout.activity_main) 

        // Инициализация TTS
        initTts()

        // Проверка разрешений
        checkPermissions()
    }

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale("ru", "RU"))
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Toast.makeText(this, "Русский язык не поддерживается TTS", Toast.LENGTH_SHORT).show()
                } else {
                    isTtsReady = true
                }
            } else {
                Toast.makeText(this, "Ошибка инициализации TTS", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Та самая функция speak
    fun speak(text: String) {
        if (isTtsReady && tts != null) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "JarvisUtteranceId")
        } else {
            // Если TTS еще не готов, просто показываем текст на экране
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                // Разрешения получены
            } else {
                Toast.makeText(this, "Джарвису нужны разрешения для работы!", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}