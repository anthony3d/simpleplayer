package com.example.simpleplayer

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
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
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        etPath = findViewById(R.id.etPath)
        etPause = findViewById(R.id.etPause)
        btnPlay = findViewById(R.id.btnPlay)
        btnStop = findViewById(R.id.btnStop)
        tvStatus = findViewById(R.id.tvStatus)
        
        etPath.setText("/storage/emulated/0/Music/song.mp3")
        etPause.setText("2")
        
        btnPlay.setOnClickListener { startPlaying() }
        btnStop.setOnClickListener { stopPlaying() }
        
        checkPermission()
    }
    
    private fun checkPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, 
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 1)
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
            tvStatus.text = "Файл не найден: $filePath"
            return
        }
        
        stopPlaying()
        isPlaying = true
        
        val pauseSeconds = pauseText.toLongOrNull() ?: 0
        playWithLoop(filePath, pauseSeconds)
        
        tvStatus.text = "Играет: ${file.name} (пауза ${pauseSeconds}с)"
    }
    
    private fun playWithLoop(filePath: String, delaySeconds: Long) {
        if (!isPlaying) return
        
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(filePath)
                prepare()
                setOnCompletionListener {
                    if (isPlaying) {
                        tvStatus.text = "Пауза ${delaySeconds} сек..."
                        handler.postDelayed({
                            playWithLoop(filePath, delaySeconds)
                        }, delaySeconds * 1000)
                    }
                }
                start()
            }
        } catch (e: Exception) {
            tvStatus.text = "Ошибка: ${e.message}"
            isPlaying = false
        }
    }
    
    private fun stopPlaying() {
        isPlaying = false
        handler.removeCallbacksAndMessages(null)
        mediaPlayer?.let {
            if (it.isPlaying) it.stop()
            it.release()
        }
        mediaPlayer = null
        tvStatus.text = "Остановлено"
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopPlaying()
    }
}
