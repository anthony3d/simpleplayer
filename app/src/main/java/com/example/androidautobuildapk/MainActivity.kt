package com.example.simpleplayer


import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    
    private lateinit var etFileName: EditText
    private lateinit var etPause: EditText
    private lateinit var btnPlay: Button
    private lateinit var btnStop: Button
    private lateinit var btnSelect: Button
    private lateinit var lvHistory: ListView
    private lateinit var tvStatus: TextView
    
    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isPlaying = false
    private var currentUri: Uri? = null
    private var currentPauseSeconds = 0L
    private var checkPositionRunnable: Runnable? = null
    private var currentFileName = ""
    private var currentUriString = ""
    
    private val PICK_AUDIO_FILE = 1000
    private val MAX_HISTORY = 10
    
    private lateinit var sharedPrefs: SharedPreferences
    private var historyList = mutableListOf<HistoryItem>()
    private var historyAdapter: ArrayAdapter<String>? = null
    
    data class HistoryItem(
        val fileName: String,
        val uriString: String,
        val pauseSeconds: Long
    )
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        etFileName = findViewById(R.id.etFileName)
        etPause = findViewById(R.id.etPause)
        btnPlay = findViewById(R.id.btnPlay)
        btnStop = findViewById(R.id.btnStop)
        btnSelect = findViewById(R.id.btnSelect)
        lvHistory = findViewById(R.id.lvHistory)
        tvStatus = findViewById(R.id.tvStatus)
        
        sharedPrefs = getSharedPreferences("player_history", MODE_PRIVATE)
        
        etPause.setText("2")
        
        btnSelect.setOnClickListener { selectAudioFile() }
        btnPlay.setOnClickListener { startPlaying() }
        btnStop.setOnClickListener { stopPlaying() }
        
        // Обработка клика по элементу истории
        lvHistory.setOnItemClickListener { _, _, position, _ ->
            if (position < historyList.size) {
                val item = historyList[position]
                loadHistoryItem(item)
            }
        }
        
        // Долгий клик для удаления из истории
        lvHistory.setOnItemLongClickListener { _, _, position, _ ->
            if (position < historyList.size) {
                removeFromHistory(position)
                true
            } else {
                false
            }
        }
        
        checkPermissions()
        loadHistory()
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
                currentUriString = uri.toString()
                
                // Получаем持久ный доступ к файлу
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, 
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                
                // Получаем имя файла
                currentFileName = getFileName(uri)
                etFileName.setText(currentFileName)
                tvStatus.text = "Выбран: $currentFileName"
                Toast.makeText(this, "Файл выбран: $currentFileName", Toast.LENGTH_SHORT).show()
                
                // Получаем паузу
                val pause = etPause.text.toString().toLongOrNull() ?: 2
                
                // Добавляем в историю
                addToHistory(currentFileName, currentUriString, pause)
                
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
    
    // ============ РАБОТА С ИСТОРИЕЙ ============
    
    private fun addToHistory(fileName: String, uriString: String, pauseSeconds: Long) {
        // Удаляем дубликаты
        historyList.removeAll { it.uriString == uriString }
        
        // Добавляем в начало
        historyList.add(0, HistoryItem(fileName, uriString, pauseSeconds))
        
        // Ограничиваем размер
        if (historyList.size > MAX_HISTORY) {
            historyList = historyList.take(MAX_HISTORY).toMutableList()
        }
        
        saveHistory()
        updateHistoryUI()
    }
    
    private fun removeFromHistory(position: Int) {
        historyList.removeAt(position)
        saveHistory()
        updateHistoryUI()
        Toast.makeText(this, "Удалено из истории", Toast.LENGTH_SHORT).show()
    }
    
    private fun loadHistoryItem(item: HistoryItem) {
        try {
            val uri = Uri.parse(item.uriString)
            currentUri = uri
            currentUriString = item.uriString
            currentFileName = item.fileName
            etFileName.setText(item.fileName)
            etPause.setText(item.pauseSeconds.toString())
            
            tvStatus.text = "Загружено: ${item.fileName}"
            Toast.makeText(this, "Загружено: ${item.fileName}", Toast.LENGTH_SHORT).show()
            
            // Автоматически начинаем воспроизведение
            startPlaying()
            
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Ошибка загрузки файла", Toast.LENGTH_SHORT).show()
            // Удаляем из истории если файл недоступен
            removeFromHistory(historyList.indexOf(item))
        }
    }
    
    private fun saveHistory() {
        val jsonArray = JSONArray()
        historyList.forEach { item ->
            val jsonObject = JSONObject().apply {
                put("fileName", item.fileName)
                put("uriString", item.uriString)
                put("pauseSeconds", item.pauseSeconds)
            }
            jsonArray.put(jsonObject)
        }
        sharedPrefs.edit().putString("history", jsonArray.toString()).apply()
    }
    
    private fun loadHistory() {
        val jsonString = sharedPrefs.getString("history", "[]") ?: "[]"
        try {
            val jsonArray = JSONArray(jsonString)
            historyList.clear()
            for (i in 0 until jsonArray.length()) {
                val jsonObject = jsonArray.getJSONObject(i)
                historyList.add(
                    HistoryItem(
                        jsonObject.getString("fileName"),
                        jsonObject.getString("uriString"),
                        jsonObject.getLong("pauseSeconds")
                    )
                )
            }
            updateHistoryUI()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun updateHistoryUI() {
        if (historyAdapter == null) {
            historyAdapter = ArrayAdapter(
                this,
                android.R.layout.simple_list_item_1,
                historyList.map { 
                    val pause = if (it.pauseSeconds > 0) " (пауза ${it.pauseSeconds}с)" else ""
                    "${historyList.indexOf(it) + 1}. ${it.fileName}$pause"
                }
            )
            lvHistory.adapter = historyAdapter
        } else {
            historyAdapter?.clear()
            historyAdapter?.addAll(
                historyList.map { 
                    val pause = if (it.pauseSeconds > 0) " (пауза ${it.pauseSeconds}с)" else ""
                    "${historyList.indexOf(it) + 1}. ${it.fileName}$pause"
                }
            )
            historyAdapter?.notifyDataSetChanged()
        }
    }
    
    // ============ ВОСПРОИЗВЕДЕНИЕ ============
    
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
                    handler.postDelayed(this, 100)
                } else if (player != null && !player.isPlaying && player.currentPosition > 0) {
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
