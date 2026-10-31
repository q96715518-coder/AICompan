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

// Твои импорты (если ты использовал ViewBinding, Retrofit или другие библиотеки, 
// убедись, что они тоже есть в списке выше)

class MainActivity : AppCompatActivity() {

    // Переменные для работы TTS (Озвучка)
    private var tts: TextToSpeech? = null
    private val PERMISSION_REQUEST_CODE = 123

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Твой setContentView (если он у тебя был в коде, он останется здесь)
        // setContentView(R.layout.activity_main) 

        // 1. Инициализируем голосового движка
        initTts()

        // 2. Твой код инициализации кнопок, полей и т.д.
        // ...

        // 3. Проверяем разрешения (микрофон, уведомления)
        checkPermissionsAndStartService()
    }

    // === БЛОК ОЗВУЧКИ (TTS) ===
    
    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // Устанавливаем русский язык
                val result = tts?.setLanguage(Locale("ru", "RU"))
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Toast.makeText(this, "Русский язык не поддерживается", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Ошибка TTS", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ВОТ ТА САМАЯ ФУНКЦИЯ, КОТОРОЙ НЕ ХВАТАЛО НА СТРОКАХ 177 и 237
    fun speak(text: String) {
        // Если tts еще не готов, инициализируем на ходу
        if (tts == null) {
            initTts()
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "JarvisUtteranceId")
    }

    // ========================

    // === БЛОК РАЗРЕШЕНИЙ И СЕРВИСА ===

    private fun checkPermissionsAndStartService() {
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
        } else {
            startJarvisService()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startJarvisService()
            } else {
                Toast.makeText(this, "Джарвису нужны разрешения для работы!", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startJarvisService() {
        val serviceIntent = Intent(this, JarvisService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    // === ОЧИСТКА ПАМЯТИ ===
    
    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}