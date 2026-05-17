package com.example.simpleplayer

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File

class MainActivity : AppCompatActivity() {
    
    private lateinit var etPath: EditText
    private lateinit var etPause: EditText
    private lateinit var btnPlay: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView
    
    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isPlaying = false
    private var currentFilePath = ""
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        etPath = findViewById(R.id.etPath)
        etPause = findViewById(R.id.etPause)
        btnPlay = findViewById(R.id.btnPlay)
        btnStop = findViewById(R.id.btnStop)
        tvStatus = findViewById(R.id.tvStatus)
        
        // Примеры правильных путей
        etPath.setText("/storage/emulated/0/Download/song.mp3")
        etPause.setText("2")
        
        btnPlay.setOnClickListener { startPlaying() }
        btnStop.setOnClickListener { stopPlaying() }
        
        checkPermissions()
    }
    
    private fun checkPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        
        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        
        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions, 1)
        }
    }
    
    private fun startPlaying() {
        val filePath = etPath.text.toString()
        val pauseText = etPause.text.toString()
        
        if (filePath.isEmpty()) {
            tvStatus.text = "Укажите полный путь к MP3 файлу"
            return
        }
        
        val file = File(filePath)
        if (!file.exists()) {
            tvStatus.text = "Файл не найден: $filePath\nПроверьте путь"
            Toast.makeText(this, "Файл не существует!", Toast.LENGTH_LONG).show()
            return
        }
        
        stopPlaying()
        currentFilePath = filePath
        isPlaying = true
        
        val pauseSeconds = pauseText.toLongOrNull() ?: 0
        playWithLoop(filePath, pauseSeconds)
        
        tvStatus.text = "Играет: ${file.name} (пауза ${pauseSeconds}с)"
        Toast.makeText(this, "Воспроизведение: ${file.name}", Toast.LENGTH_SHORT).show()
    }
    
    private fun playWithLoop(filePath: String, delaySeconds: Long) {
        if (!isPlaying) return
        
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(filePath)
                prepare()
                setVolume(1.0f, 1.0f)  // Максимальная громкость
                setOnCompletionListener {
                    if (isPlaying) {
                        runOnUiThread {
                            tvStatus.text = "Пауза ${delaySeconds} сек..."
                        }
                        handler.postDelayed({
                            playWithLoop(filePath, delaySeconds)
                        }, delaySeconds * 1000)
                    }
                }
                start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            runOnUiThread {
                tvStatus.text = "Ошибка: ${e.message}"
                Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
            }
            isPlaying = false
        }
    }
    
    private fun stopPlaying() {
        isPlaying = false
        handler.removeCallbacksAndMessages(null)
        mediaPlayer?.let {
            try {
                if (it.isPlaying) it.stop()
                it.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        mediaPlayer = null
        tvStatus.text = "Остановлено"
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopPlaying()
    }
}
