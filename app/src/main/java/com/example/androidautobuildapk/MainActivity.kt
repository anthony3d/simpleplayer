package com.example.simpleplayer

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private var currentPauseSeconds = 0L
    private var checkPositionRunnable: Runnable? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        etPath = findViewById(R.id.etPath)
        etPause = findViewById(R.id.etPause)
        btnPlay = findViewById(R.id.btnPlay)
        btnStop = findViewById(R.id.btnStop)
        tvStatus = findViewById(R.id.tvStatus)
        
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
            tvStatus.text = "Файл не найден: $filePath"
            Toast.makeText(this, "Файл не существует!", Toast.LENGTH_LONG).show()
            return
        }
        
        stopPlaying()
        
        currentFilePath = filePath
        currentPauseSeconds = pauseText.toLongOrNull() ?: 0
        isPlaying = true
        
        startPlayback()
        
        tvStatus.text = "▶ Воспроизведение: ${file.name}"
        Toast.makeText(this, "Воспроизведение: ${file.name}", Toast.LENGTH_SHORT).show()
    }
    
    private fun startPlayback() {
        if (!isPlaying) return
        
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(currentFilePath)
                prepare()
                setVolume(1.0f, 1.0f)
                start()
            }
            
            // Запускаем отслеживание окончания трека
            startMonitoringPlayback()
            
        } catch (e: Exception) {
            e.printStackTrace()
            tvStatus.text = "Ошибка: ${e.message}"
            Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
            isPlaying = false
        }
    }
    
    private fun startMonitoringPlayback() {
        // Отменяем предыдущий мониторинг
        checkPositionRunnable?.let { handler.removeCallbacks(it) }
        
        checkPositionRunnable = object : Runnable {
            override fun run() {
                if (!isPlaying) return
                
                val player = mediaPlayer
                if (player != null && player.isPlaying) {
                    // Трек еще играет, проверяем через 100 мс
                    handler.postDelayed(this, 100)
                } else if (player != null && !player.isPlaying && player.currentPosition > 0) {
                    // Трек закончился (больше не играет, но позиция была >0)
                    tvStatus.text = "⏸ Пауза ${currentPauseSeconds} сек..."
                    
                    // Планируем повтор через заданную паузу
                    handler.postDelayed({
                        if (isPlaying) {
                            tvStatus.text = "▶ Воспроизведение..."
                            startPlayback()
                        }
                    }, currentPauseSeconds * 1000)
                } else {
                    // Если плеер нулевой или позиция 0 - продолжаем проверять
                    handler.postDelayed(this, 100)
                }
            }
        }
        
        handler.post(checkPositionRunnable!!)
    }
    
    private fun stopPlaying() {
        isPlaying = false
        
        // Останавливаем мониторинг
        checkPositionRunnable?.let { handler.removeCallbacks(it) }
        checkPositionRunnable = null
        
        // Останавливаем запланированные паузы
        handler.removeCallbacksAndMessages(null)
        
        // Останавливаем плеер
        mediaPlayer?.let {
            try {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        mediaPlayer = null
        
        tvStatus.text = "⏹ Остановлено"
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopPlaying()
    }
}
