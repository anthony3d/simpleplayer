package com.example.simpleplayer

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File

class MainActivity : AppCompatActivity() {
    
    private lateinit var etDirectory: EditText
    private lateinit var etFilename: EditText
    private lateinit var etPause: EditText
    private lateinit var btnPlay: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView
    
    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isPlaying = false
    private var pauseSeconds = 0L
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        etDirectory = findViewById(R.id.etDirectory)
        etFilename = findViewById(R.id.etFilename)
        etPause = findViewById(R.id.etPause)
        btnPlay = findViewById(R.id.btnPlay)
        btnStop = findViewById(R.id.btnStop)
        tvStatus = findViewById(R.id.tvStatus)
        
        // Пример пути: /storage/emulated/0/Music
        etDirectory.setText("/storage/emulated/0/Music")
        etFilename.setText("song.mp3")
        etPause.setText("2")
        
        btnPlay.setOnClickListener { startPlaying() }
        btnStop.setOnClickListener { stopPlaying() }
        
        checkPermissions()
    }
    
    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, 
                arrayOf(Manifest.permission.READ_MEDIA_AUDIO), 1)
        }
    }
    
    private fun startPlaying() {
        val directory = etDirectory.text.toString()
        val filename = etFilename.text.toString()
        val pauseText = etPause.text.toString()
        
        if (directory.isEmpty() || filename.isEmpty()) {
            tvStatus.text = "Ошибка: укажите путь и файл"
            return
        }
        
        pauseSeconds = pauseText.toLongOrNull() ?: 0
        
        val filePath = if (directory.endsWith("/")) directory + filename 
                       else "$directory/$filename"
        val file = File(filePath)
        
        if (!file.exists()) {
            tvStatus.text = "Файл не найден: $filePath"
            return
        }
        
        stopPlaying()
        isPlaying = true
        playWithLoop(filePath)
        
        tvStatus.text = "Воспроизведение: $filename (пауза ${pauseSeconds}с)"
    }
    
    private fun playWithLoop(filePath: String) {
        if (!isPlaying) return
        
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(filePath)
                prepare()
                setOnCompletionListener {
                    if (isPlaying) {
                        tvStatus.text = "Пауза ${pauseSeconds}с..."
                        handler.postDelayed({
                            playWithLoop(filePath)
                        }, pauseSeconds * 1000)
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
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        tvStatus.text = "Остановлено"
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopPlaying()
    }
}
