package com.example.aicompanion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import okhttp3.* // Импортируем всё сразу, чтобы не было конфликтов
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class JarvisService : Service() {

    private val CHANNEL_ID = "JarvisServiceChannel"
    private val NOTIFICATION_ID = 1
    private val client = OkHttpClient()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d("Jarvis", "Сервис создан")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Обязательно для Android 8+
        startForeground(NOTIFICATION_ID, buildNotification())
        
        Log.d("Jarvis", "Джарвис слушает...")

        // ЗДЕСЬ ТВОЙ КОД ОТПРАВКИ ЗАПРОСА (То, из-за чего были ошибки на строках 159-161)
        sendRequestToAI("Привет, Джарвис!")

        return START_STICKY
    }

    // Пример функции, где использовались Request, toMediaType и toRequestBody
    private fun sendRequestToAI(userText: String) {
        try {
            val json = """
                {
                    "model": "gpt-3.5-turbo",
                    "messages": [{"role": "user", "content": "$userText"}]
                }
            """.trimIndent()

            // ВОТ ТЕ САМЫЕ СТРОКИ, КОТОРЫЕ ВЫЗЫВАЛИ ОШИБКУ (теперь они работают)
            val requestBody = json.toRequestBody("application/json".toMediaType())
            
            val request = Request.Builder()
                .url("https://api.openai.com/v1/chat/completions") // ЗАМЕНИ на свой URL
                .addHeader("Authorization", "Bearer ТВОЙ_API_КЛЮЧ") // Вставь свой ключ
                .post(requestBody)
                .build()

            // Отправляем запрос асинхронно (чтобы не вешать сервис)
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {
                    Log.e("Jarvis", "Ошибка сети: ${e.message}")
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!response.isSuccessful) {
                            Log.e("Jarvis", "Ошибка сервера: ${response.code}")
                        } else {
                            val responseData = response.body?.string()
                            Log.d("Jarvis", "Ответ от ИИ: $responseData")
                            // Здесь можно передать ответ в TTS (озвучку)
                        }
                    }
                }
            })
        } catch (e: Exception) {
            Log.e("Jarvis", "Ошибка при создании запроса: ${e.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("Jarvis", "Сервис остановлен")
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Джарвис")
            .setContentText("Слушаю ваши команды...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Jarvis Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }
}