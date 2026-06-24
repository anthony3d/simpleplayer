package com.example.simpleplayer

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    
    private lateinit var btnSelect: Button
    private lateinit var btnPlayStop: Button
    private lateinit var spinnerPause: Spinner
    private lateinit var lvHistory: ListView
    private lateinit var tvStatus: TextView
    private lateinit var layoutTags: View
    private lateinit var tvArtist: TextView
    private lateinit var tvTitle: TextView
    private lateinit var tvAlbum: TextView
    
    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isPlaying = false
    private var currentUri: Uri? = null
    private var currentPauseSeconds = 5L
    private var checkPositionRunnable: Runnable? = null
    private var progressRunnable: Runnable? = null
    private var currentFileName = ""
    private var currentUriString = ""
    private var trackDuration = 0
    
    private val PICK_AUDIO_FILE = 1000
    private val MAX_HISTORY = 10
    private val PAUSE_VALUES = listOf(2L, 5L, 10L, 20L, 30L)
    
    private lateinit var sharedPrefs: SharedPreferences
    private var historyList = mutableListOf<HistoryItem>()
    private var historyAdapter: HistoryAdapter? = null
    private var currentPlayingPosition = -1
    
    data class HistoryItem(
        val fileName: String,
        val uriString: String,
        val pauseSeconds: Long
    )
    
    data class AudioTags(
        val artist: String,
        val title: String,
        val album: String
    )
    
    // ============ КАСТОМНЫЙ АДАПТЕР ДЛЯ ИСТОРИИ С ПРОГРЕССОМ ============
    
    inner class HistoryAdapter(context: MainActivity, private val items: MutableList<HistoryItem>) :
        ArrayAdapter<HistoryItem>(context, 0, items) {
        
        private var playingPosition = -1
        private var progress = 0f // 0.0 - 1.0
        
        fun setPlayingPosition(position: Int) {
            playingPosition = position
            notifyDataSetChanged()
        }
        
        fun updateProgress(progressValue: Float) {
            progress = progressValue
            // Обновляем только если есть активный трек
            if (playingPosition >= 0) {
                // Обновляем конкретную ячейку
                val view = lvHistory.getChildAt(playingPosition - lvHistory.firstVisiblePosition)
                if (view != null) {
                    updateProgressForView(view, progressValue)
                }
            }
        }
        
        private fun updateProgressForView(view: View, progressValue: Float) {
            val progressFill = view.findViewById<View>(R.id.progressFill)
            if (progressFill != null) {
                val width = (view.width * progressValue).toInt()
                progressFill.layoutParams.width = width
                progressFill.requestLayout()
            }
        }
        
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context).inflate(
                R.layout.history_item, parent, false
            )
            
            val textView = view.findViewById<TextView>(R.id.tvHistoryItem)
            val progressFill = view.findViewById<View>(R.id.progressFill)
            val progressBackground = view.findViewById<View>(R.id.progressBackground)
            
            val item = items[position]
            
            // Формируем текст
            val prefix = if (position == playingPosition) "▶ " else ""
            textView.text = "$prefix${item.fileName}"
            
            // Настройка цвета в зависимости от статуса
            if (position == playingPosition) {
                progressFill.setBackgroundColor(0xFF4CAF50.toInt())
                // Показываем прогресс
                val currentProgress = if (position == playingPosition) progress else 0f
                val width = (view.width * currentProgress).toInt()
                progressFill.layoutParams.width = width
                progressFill.visibility = View.VISIBLE
                progressBackground.visibility = View.VISIBLE
                textView.setTextColor(0xFFFFFFFF.toInt())
            } else {
                // Скрываем прогресс для неактивных треков
                progressFill.layoutParams.width = 0
                progressFill.visibility = View.GONE
                progressBackground.visibility = View.GONE
                textView.setTextColor(0xFF333333.toInt())
            }
            
            return view
        }
        
        override fun getItem(position: Int): HistoryItem {
            return items[position]
        }
        
        override fun getCount(): Int {
            return items.size
        }
    }
    
    // ============ ОСНОВНЫЕ МЕТОДЫ ============
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        btnSelect = findViewById(R.id.btnSelect)
        btnPlayStop = findViewById(R.id.btnPlayStop)
        spinnerPause = findViewById(R.id.spinnerPause)
        lvHistory = findViewById(R.id.lvHistory)
        tvStatus = findViewById(R.id.tvStatus)
        layoutTags = findViewById(R.id.layoutTags)
        tvArtist = findViewById(R.id.tvArtist)
        tvTitle = findViewById(R.id.tvTitle)
        tvAlbum = findViewById(R.id.tvAlbum)
        
        sharedPrefs = getSharedPreferences("player_history", MODE_PRIVATE)
        
        // Настройка спиннера
        val pauseOptions = resources.getStringArray(R.array.pause_options)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, pauseOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerPause.adapter = adapter
        spinnerPause.setSelection(1) // 5 сек по умолчанию
        
        spinnerPause.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                currentPauseSeconds = PAUSE_VALUES[position]
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        
        btnSelect.setOnClickListener { selectAudioFile() }
        btnPlayStop.setOnClickListener { togglePlayStop() }
        
        lvHistory.setOnItemClickListener { _, _, position, _ ->
            if (position < historyList.size) {
                val item = historyList[position]
                loadHistoryItem(item)
            }
        }
        
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
        updatePlayStopButton()
        clearTags()
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
                currentUri = uri
                currentUriString = uri.toString()
                
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, 
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                
                currentFileName = getFileName(uri)
                
                // Извлекаем теги
                val tags = extractTags(uri)
                displayTags(tags)
                
                tvStatus.text = "Выбран: $currentFileName"
                Toast.makeText(this, "Загружено: $currentFileName", Toast.LENGTH_SHORT).show()
                
                val pause = currentPauseSeconds
                addToHistory(currentFileName, currentUriString, pause)
                
                stopPlaying()
                updatePlayStopButton()
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
    
    // ============ ИЗВЛЕЧЕНИЕ ТЕГОВ ============
    
    private fun extractTags(uri: Uri): AudioTags {
        var artist = "---"
        var title = "---"
        var album = "---"
        
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(this, uri)
            
            artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "---"
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: "---"
            album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "---"
            
            if (title == "---") {
                title = currentFileName.removeSuffix(".mp3").removeSuffix(".MP3")
            }
            
            retriever.release()
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return AudioTags(artist, title, album)
    }
    
    private fun displayTags(tags: AudioTags) {
        tvArtist.text = "🎤 ${tags.artist}"
        tvTitle.text = "🎵 ${tags.title}"
        tvAlbum.text = "💿 ${tags.album}"
        layoutTags.visibility = View.VISIBLE
    }
    
    private fun clearTags() {
        tvArtist.text = "🎤 ---"
        tvTitle.text = "🎵 ---"
        tvAlbum.text = "💿 ---"
        layoutTags.visibility = View.GONE
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
    
    // ============ ИСТОРИЯ ============
    
    private fun addToHistory(fileName: String, uriString: String, pauseSeconds: Long) {
        historyList.removeAll { it.uriString == uriString }
        historyList.add(0, HistoryItem(fileName, uriString, pauseSeconds))
        
        if (historyList.size > MAX_HISTORY) {
            historyList = historyList.take(MAX_HISTORY).toMutableList()
        }
        
        saveHistory()
        updateHistoryUI()
    }
    
    private fun removeFromHistory(position: Int) {
        historyList.removeAt(position)
        if (currentPlayingPosition == position) {
            currentPlayingPosition = -1
        } else if (currentPlayingPosition > position) {
            currentPlayingPosition--
        }
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
            
            val tags = extractTags(uri)
            displayTags(tags)
            
            val pauseIndex = PAUSE_VALUES.indexOf(item.pauseSeconds)
            if (pauseIndex >= 0) {
                spinnerPause.setSelection(pauseIndex)
                currentPauseSeconds = item.pauseSeconds
            }
            
            tvStatus.text = "Загружено: ${item.fileName}"
            Toast.makeText(this, "Загружено: ${item.fileName}", Toast.LENGTH_SHORT).show()
            
            startPlaying()
            
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Ошибка загрузки файла", Toast.LENGTH_SHORT).show()
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
            historyAdapter = HistoryAdapter(this, historyList)
            lvHistory.adapter = historyAdapter
        } else {
            historyAdapter?.notifyDataSetChanged()
        }
        
        // Устанавливаем позицию активного трека
        historyAdapter?.setPlayingPosition(currentPlayingPosition)
    }
    
    // ============ ВОСПРОИЗВЕДЕНИЕ ============
    
    private fun togglePlayStop() {
        if (isPlaying) {
            stopPlaying()
        } else {
            startPlaying()
        }
        updatePlayStopButton()
    }
    
    private fun startPlaying() {
        if (currentUri == null) {
            tvStatus.text = "Сначала загрузите файл"
            Toast.makeText(this, "Нажмите 'Загрузить MP3'", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (layoutTags.visibility == View.GONE) {
            val tags = extractTags(currentUri!!)
            displayTags(tags)
        }
        
        stopPlaying()
        isPlaying = true
        
        startPlayback()
        
        tvStatus.text = "▶ $currentFileName"
        Toast.makeText(this, "Воспроизведение: $currentFileName", Toast.LENGTH_SHORT).show()
        updatePlayStopButton()
        updateHistoryUI()
    }
    
    private fun startPlayback() {
        if (!isPlaying || currentUri == null) return
        
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(this@MainActivity, currentUri!!)
                prepare()
                trackDuration = duration
                setVolume(1.0f, 1.0f)
                start()
            }
            
            currentPlayingPosition = historyList.indexOfFirst { it.uriString == currentUriString }
            historyAdapter?.setPlayingPosition(currentPlayingPosition)
            updateHistoryUI()
            
            startMonitoringPlayback()
            startProgressUpdater()
            
        } catch (e: Exception) {
            e.printStackTrace()
            tvStatus.text = "Ошибка: ${e.message}"
            Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
            isPlaying = false
            updatePlayStopButton()
        }
    }
    
    private fun startProgressUpdater() {
        progressRunnable?.let { handler.removeCallbacks(it) }
        
        progressRunnable = object : Runnable {
            override fun run() {
                if (!isPlaying) return
                
                val player = mediaPlayer
                if (player != null && player.isPlaying && trackDuration > 0) {
                    val currentPosition = player.currentPosition
                    val progress = currentPosition.toFloat() / trackDuration.toFloat()
                    
                    // Обновляем прогресс в адаптере
                    historyAdapter?.updateProgress(progress)
                    
                    handler.postDelayed(this, 100)
                } else if (player != null && !player.isPlaying && player.currentPosition > 0) {
                    // Трек на паузе, показываем финальный прогресс
                    // Ничего не делаем
                } else {
                    handler.postDelayed(this, 100)
                }
            }
        }
        
        handler.post(progressRunnable!!)
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
        progressRunnable?.let { handler.removeCallbacks(it) }
        progressRunnable = null
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
            updatePlayStopButton()
            // Сбрасываем прогресс
            historyAdapter?.updateProgress(0f)
            historyAdapter?.setPlayingPosition(-1)
            currentPlayingPosition = -1
            updateHistoryUI()
        }
    }
    
    private fun updatePlayStopButton() {
        if (isPlaying) {
            btnPlayStop.text = "⏹ Стоп"
        } else {
            btnPlayStop.text = "▶ Воспроизвести"
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopPlaying()
    }
}
