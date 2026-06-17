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
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File

class MainActivity : AppCompatActivity() {
    
    private lateinit var etPause: EditText
    private lateinit var btnPlay: Button
    private lateinit var btnStop: Button
    private lateinit var btnSelect: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvFileName: TextView
    private lateinit var tagsContainer: LinearLayout
    private lateinit var tvNoTags: TextView
    private lateinit var historyContainer: LinearLayout
    
    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isPlaying = false
    private var currentUri: Uri? = null
    private var currentPauseSeconds = 0L
    private var checkPositionRunnable: Runnable? = null
    private var currentFileName = ""
    private var currentFilePath: String? = null
    
    private val PICK_AUDIO_FILE = 1000
    private val MAX_HISTORY = 5
    
    private lateinit var prefs: SharedPreferences
    private val historyList = mutableListOf<TrackInfo>()
    
    data class TrackInfo(
        val uri: String,
        val fileName: String,
        val tags: Map<String, String>
    )
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        etPause = findViewById(R.id.etPause)
        btnPlay = findViewById(R.id.btnPlay)
        btnStop = findViewById(R.id.btnStop)
        btnSelect = findViewById(R.id.btnSelect)
        tvStatus = findViewById(R.id.tvStatus)
        tvFileName = findViewById(R.id.tvFileName)
        tagsContainer = findViewById(R.id.tagsContainer)
        tvNoTags = findViewById(R.id.tvNoTags)
        historyContainer = findViewById(R.id.historyContainer)
        
        prefs = getSharedPreferences("player_prefs", MODE_PRIVATE)
        
        etPause.setText("2")
        
        btnSelect.setOnClickListener { selectAudioFile() }
        btnPlay.setOnClickListener { startPlaying() }
        btnStop.setOnClickListener { stopPlaying() }
        
        checkPermissions()
        
        loadHistory()
        updateHistoryUI()
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
                
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                
                currentFileName = getFileName(uri)
                currentFilePath = getFilePathFromUri(uri)
                
                tvFileName.text = currentFileName
                
                val tags = readAllTags()
                displayTags(tags)
                
                addToHistory(TrackInfo(
                    uri = uri.toString(),
                    fileName = currentFileName,
                    tags = tags
                ))
                
                tvStatus.text = "Выбран: $currentFileName"
                Toast.makeText(this, "Файл выбран: $currentFileName", Toast.LENGTH_SHORT).show()
                
                stopPlaying()
            }
        }
    }
    
    private fun getFilePathFromUri(uri: Uri): String? {
        if (uri.scheme == "file") {
            return uri.path
        }
        return null
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
    
    private fun readAllTags(): Map<String, String> {
        val tags = mutableMapOf<String, String>()
        
        try {
            if (currentFilePath != null && File(currentFilePath).exists()) {
                val audioFile = AudioFileIO.read(File(currentFilePath))
                val tag = audioFile.tag
                
                if (tag != null) {
                    // Читаем все доступные теги
                    tag.getFirst(FieldKey.TITLE)?.let { if (it.isNotEmpty()) tags["Название"] = it }
                    tag.getFirst(FieldKey.ARTIST)?.let { if (it.isNotEmpty()) tags["Исполнитель"] = it }
                    tag.getFirst(FieldKey.ALBUM)?.let { if (it.isNotEmpty()) tags["Альбом"] = it }
                    tag.getFirst(FieldKey.YEAR)?.let { if (it.isNotEmpty()) tags["Год"] = it }
                    tag.getFirst(FieldKey.GENRE)?.let { if (it.isNotEmpty()) tags["Жанр"] = it }
                    tag.getFirst(FieldKey.COMMENT)?.let { if (it.isNotEmpty()) tags["Комментарий"] = it }
                    tag.getFirst(FieldKey.TRACK)?.let { if (it.isNotEmpty()) tags["Трек"] = it }
                    tag.getFirst(FieldKey.COMPOSER)?.let { if (it.isNotEmpty()) tags["Композитор"] = it }
                    tag.getFirst(FieldKey.ALBUM_ARTIST)?.let { if (it.isNotEmpty()) tags["Исполнитель альбома"] = it }
                    tag.getFirst(FieldKey.DISC_NO)?.let { if (it.isNotEmpty()) tags["Номер диска"] = it }
                    tag.getFirst(FieldKey.LYRICS)?.let { if (it.isNotEmpty()) tags["Текст"] = it }
                    tag.getFirst(FieldKey.COPYRIGHT)?.let { if (it.isNotEmpty()) tags["Авторские права"] = it }
                    tag.getFirst(FieldKey.ENCODER)?.let { if (it.isNotEmpty()) tags["Кодировщик"] = it }
                    tag.getFirst(FieldKey.BPM)?.let { if (it.isNotEmpty()) tags["BPM"] = it }
                    tag.getFirst(FieldKey.GROUPING)?.let { if (it.isNotEmpty()) tags["Группировка"] = it }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return tags
    }
    
    private fun displayTags(tags: Map<String, String>) {
        tagsContainer.removeAllViews()
        
        if (tags.isEmpty()) {
            tvNoTags.visibility = TextView.VISIBLE
            return
        }
        
        tvNoTags.visibility = TextView.GONE
        
        val priorityKeys = listOf("Название", "Исполнитель", "Альбом")
        val sortedKeys = priorityKeys.filter { it in tags.keys } + 
                         tags.keys.filter { it !in priorityKeys }.sorted()
        
        for (key in sortedKeys) {
            val value = tags[key] ?: continue
            val tv = TextView(this).apply {
                text = "$key: $value"
                textSize = 14f
                setPadding(5, 3, 5, 3)
            }
            tagsContainer.addView(tv)
        }
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
    
    // ==================== ИСТОРИЯ ====================
    
    private fun addToHistory(track: TrackInfo) {
        historyList.removeAll { it.uri == track.uri }
        historyList.add(0, track)
        while (historyList.size > MAX_HISTORY) {
            historyList.removeAt(historyList.lastIndex)
        }
        saveHistory()
        updateHistoryUI()
    }
    
    private fun loadHistory() {
        historyList.clear()
        val count = prefs.getInt("history_count", 0)
        for (i in 0 until count) {
            val uri = prefs.getString("history_${i}_uri", "") ?: ""
            val fileName = prefs.getString("history_${i}_name", "") ?: ""
            if (uri.isNotEmpty()) {
                val tags = mutableMapOf<String, String>()
                val tagsCount = prefs.getInt("history_${i}_tags_count", 0)
                for (j in 0 until tagsCount) {
                    val key = prefs.getString("history_${i}_tag_${j}_key", "") ?: ""
                    val value = prefs.getString("history_${i}_tag_${j}_value", "") ?: ""
                    if (key.isNotEmpty() && value.isNotEmpty()) {
                        tags[key] = value
                    }
                }
                historyList.add(TrackInfo(uri, fileName, tags))
            }
        }
    }
    
    private fun saveHistory() {
        val editor = prefs.edit()
        editor.putInt("history_count", historyList.size)
        historyList.forEachIndexed { index, track ->
            editor.putString("history_${index}_uri", track.uri)
            editor.putString("history_${index}_name", track.fileName)
            
            editor.putInt("history_${index}_tags_count", track.tags.size)
            track.tags.forEachIndexed { tagIndex, (key, value) ->
                editor.putString("history_${index}_tag_${tagIndex}_key", key)
                editor.putString("history_${index}_tag_${tagIndex}_value", value)
            }
        }
        editor.apply()
    }
    
    private fun updateHistoryUI() {
        historyContainer.removeAllViews()
        
        if (historyList.isEmpty()) {
            val tv = TextView(this).apply {
                text = "Нет истории"
                textSize = 14f
                setPadding(10, 10, 10, 10)
            }
            historyContainer.addView(tv)
            return
        }
        
        historyList.forEachIndexed { index, track ->
            val itemView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(10, 10, 10, 10)
                background = ContextCompat.getDrawable(this@MainActivity, android.R.drawable.btn_default)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            
            val infoText = TextView(this).apply {
                text = "${index + 1}. ${track.fileName}"
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(10, 5, 10, 5)
            }
            
            val playBtn = Button(this).apply {
                text = "▶"
                setOnClickListener {
                    loadTrackFromHistory(index)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            
            addView(infoText)
            addView(playBtn)
            
            historyContainer.addView(itemView)
        }
    }
    
    private fun loadTrackFromHistory(index: Int) {
        if (index < 0 || index >= historyList.size) return
        
        val track = historyList[index]
        currentUri = Uri.parse(track.uri)
        currentFileName = track.fileName
        
        tvFileName.text = currentFileName
        displayTags(track.tags)
        
        tvStatus.text = "Загружен: $currentFileName"
        Toast.makeText(this, "Загружен: $currentFileName", Toast.LENGTH_SHORT).show()
        
        stopPlaying()
        startPlaying()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopPlaying()
    }
}
