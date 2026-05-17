package com.example.simpleplayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    
    private lateinit var etFileName: EditText
    private lateinit var etPause: EditText
    private lateinit var btnPlay: Button
    private lateinit var btnStop: Button
    private lateinit var btnSelect: Button
    private lateinit var tvStatus: TextView
    
    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isPlaying = false
    private var currentUri: Uri? = null
    private var currentPauseSeconds = 0L
    private var checkPositionRunnable: Runnable? = null
    private var currentFileName = ""
    
    private val PICK_AUDIO_FILE = 1000
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        etFileName = findViewById(R.id.etFileName)
        etPause = findViewById(R.id.etPause)
        btnPlay = findViewById(R.id.btnPlay)
        btnStop = findViewById(R.id.btnStop)
        btnSelect = findViewById(R.id.btnSelect)
        tvStatus = findViewById(R.id.tvStatus)
        
        etPause.setText("2")
        
        btnSelect.setOnClickListener { selectAudioFile() }
        btnPlay.setOnClickListener { startPlaying() }
        btnStop.setOnClickListener { stopPlaying() }
        
        checkPermissions()
    }
    
    private fun selectAudioFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/mpeg", "audio/mp3"))
        }
        startActivityForResult(intent, PICK_AUDIO_FILE)
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_AUDIO_FILE && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                // Сохраняем URI
                currentUri = uri
                
                // Получаем持久ный доступ к файлу
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, 
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    // Некоторые версии Android не поддерживают это
                    e.printStackTrace()
                }
                
                // Получаем имя файла
                currentFileName = getFileName(uri)
                etFileName.setText(currentFileName)
                tvStatus.text = "Выбран: $currentFileName"
                Toast.makeText(this, "Файл выбран: $currentFileName", Toast.LENGTH_SHORT).show()
                
                // Останавливаем воспроизведение если что-то играло
                stopPlaying()
            }
        }
    }
    
    private fun getFileName(uri: Uri): String {
        var fileName = "audio_file"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    fileName = cursor.getString(nameIndex)
                }
            }
        }
        return fileName
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
        val pauseText = etPause.text.toString()
        
        if (currentUri == null) {
            tvStatus.text = "Сначала выберите файл"
            Toast.makeText(this, "Нажмите 'Выбрать файл'", Toast.LENGTH_SHORT).show()
            return
        }
        
        stopPlaying()
        
        currentPauseSeconds = pauseText.toLongOrNull() ?: 0
        isPlaying = true
        
        startPlayback()
        
        tvStatus.text = "▶ $currentFileName"
        Toast.makeText(this, "Воспроизведение: $currentFileName", Toast.LENGTH_SHORT).show()
    }
    
    private fun startPlayback() {
        if (!isPlaying || currentUri == null) return
        
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(this@MainActivity, currentUri!!)
                prepare()
                setVolume(1.0f, 1.0f)
                start()
            }
            
            startMonitoringPlayback()
            
        } catch (e: Exception) {
            e.printStackTrace()
            tvStatus.text = "Ошибка: ${e.message}"
            Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
            isPlaying = false
        }
    }
    
    private fun startMonitoringPlayback() {
        checkPositionRunnable?.let { handler.removeCallbacks(it) }
        
        checkPositionRunnable = object : Runnable {
            override fun run() {
                if (!isPlaying) return
                
                val player = mediaPlayer
                if (player != null && player.isPlaying) {
                    // Трек играет, продолжаем проверять
                    handler.postDelayed(this, 100)
                } else if (player != null && !player.isPlaying && player.currentPosition > 0) {
                    // Трек закончился
                    runOnUiThread {
                        tvStatus.text = "⏸ Пауза $currentPauseSeconds сек..."
                    }
                    
                    handler.postDelayed({
                        if (isPlaying) {
                            runOnUiThread {
                                tvStatus.text = "▶ Повтор: $currentFileName"
                            }
                            startPlayback()
                        }
                    }, currentPauseSeconds * 1000)
                } else {
                    // Продолжаем проверять
                    handler.postDelayed(this, 100)
                }
            }
        }
        
        handler.post(checkPositionRunnable!!)
    }
    
    private fun stopPlaying() {
        isPlaying = false
        
        checkPositionRunnable?.let { handler.removeCallbacks(it) }
        checkPositionRunnable = null
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
        
        runOnUiThread {
            tvStatus.text = "⏹ Остановлено"
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopPlaying()
    }
}
