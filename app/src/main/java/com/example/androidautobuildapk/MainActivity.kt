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
import android.widget.LinearLayout
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
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvSelectionInfo: TextView
    private lateinit var btnSetStart: Button
    private lateinit var btnSetEnd: Button
    private lateinit var btnClearSelection: Button
    
    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isPlaying = false
    private var currentUri: Uri? = null
    private var currentPauseSeconds = 5L
    private var checkPositionRunnable: Runnable? = null
    private var progressRunnable: Runnable? = null
    private var timeUpdaterRunnable: Runnable? = null
    private var currentFileName = ""
    private var currentUriString = ""
    private var trackDuration = 0
    
    // Переменные для выделения участка
    private var selectionStart = -1  // -1 означает не установлено
    private var selectionEnd = -1    // -1 означает не установлено
    private var isLoopingSelection = false
    
    private val PICK_AUDIO_FILE = 1000
    private val MAX_HISTORY = 10
    private val PAUSE_VALUES = listOf(2L, 5L, 10L, 20L, 30L)
    private val WAVEFORM_COLUMNS = 200
    
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
    
    // ============ КАСТОМНЫЙ АДАПТЕР ДЛЯ ИСТОРИИ ============
    
    inner class HistoryAdapter(context: MainActivity, private val items: MutableList<HistoryItem>) :
        ArrayAdapter<HistoryItem>(context, 0, items) {
        
        private var playingPosition = -1
        private var progress = 0f
        
        fun setPlayingPosition(position: Int) {
            playingPosition = position
            notifyDataSetChanged()
        }
        
        fun updateProgress(progressValue: Float) {
            progress = progressValue
            if (playingPosition >= 0) {
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
            val waveformContainer = view.findViewById<LinearLayout>(R.id.waveformContainer)
            
            val item = items[position]
            
            val prefix = if (position == playingPosition) "▶ " else ""
            textView.text = "$prefix${item.fileName}"
            
            if (position == playingPosition) {
                progressFill.setBackgroundColor(0xFF4CAF50.toInt())
                textView.setTextColor(0xFFFFFFFF.toInt())
                textView.setShadowLayer(2f, 1f, 1f, 0xCC000000.toInt())
            } else {
                progressFill.setBackgroundColor(0x334CAF50.toInt())
                textView.setTextColor(0xFF333333.toInt())
                textView.setShadowLayer(0f, 0f, 0f, 0)
            }
            
            drawWaveform(waveformContainer, position)
            
            val currentProgress = if (position == playingPosition) progress else 0f
            val width = (view.width * currentProgress).toInt()
            progressFill.layoutParams.width = width
            progressFill.requestLayout()
            
            return view
        }
        
        private fun drawWaveform(container: LinearLayout, position: Int) {
            container.removeAllViews()
            
            val waveData = getWaveformForItem(items[position])
            
            if (waveData == null || waveData.isEmpty()) {
                val dummyView = View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        1f
                    )
                }
                container.addView(dummyView)
                return
            }
            
            val maxHeight = 60
            val density = context.resources.displayMetrics.density
            val maxHeightPx = (maxHeight * density).toInt()
            val barWidth = 1f / waveData.size
            
            for (i in waveData.indices) {
                val value = waveData[i]
                val height = (value * maxHeightPx).toInt()
                
                val bar = View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        height.coerceAtLeast(2),
                        barWidth
                    )
                    
                    if (position == playingPosition) {
                        setBackgroundColor(0xFF4CAF50.toInt())
                    } else {
                        setBackgroundColor(0x666666.toInt())
                    }
                }
                
                container.addView(bar)
            }
        }
        
        override fun getItem(position: Int): HistoryItem {
            return items[position]
        }
        
        override fun getCount(): Int {
            return items.size
        }
    }
    
    // ============ ИЗВЛЕЧЕНИЕ ВОЛНОВОЙ ФОРМЫ ============
    
    private fun getWaveformForItem(item: HistoryItem): FloatArray? {
        if (item.uriString == currentUriString && waveformData != null) {
            return waveformData
        }
        
        try {
            val uri = Uri.parse(item.uriString)
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(this, uri)
            
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 30000
            
            retriever.release()
            
            return generateWaveform(duration)
            
        } catch (e: Exception) {
            e.printStackTrace()
            return generateRandomWaveform()
        }
    }
    
    private fun generateWaveform(durationMs: Long): FloatArray {
        val columns = WAVEFORM_COLUMNS
        val result = FloatArray(columns)
        
        val seed = (durationMs / 1000).toInt()
        val random = java.util.Random(seed.toLong())
        
        val pattern = FloatArray(columns)
        for (i in 0 until columns) {
            val envelope = Math.sin(i.toDouble() / columns * Math.PI).toFloat()
            val noise = (0.3f + random.nextFloat() * 0.7f)
            val variation = Math.sin(i.toDouble() * 0.3).toFloat() * 0.3f + 0.7f
            pattern[i] = envelope * noise * variation
        }
        
        for (i in 10 until columns - 10 step 3) {
            val peak = random.nextFloat() * 0.3f
            pattern[i] = (pattern[i] + peak).coerceAtMost(1f)
        }
        
        val max = pattern.maxOrNull() ?: 1f
        for (i in pattern.indices) {
            result[i] = pattern[i] / max
        }
        
        return result
    }
    
    private fun generateRandomWaveform(): FloatArray {
        val columns = WAVEFORM_COLUMNS
        val result = FloatArray(columns)
        val random = java.util.Random()
        
        for (i in 0 until columns) {
            val base = 0.2f + random.nextFloat() * 0.8f
            val smooth = Math.sin(i.toDouble() * 0.05).toFloat() * 0.2f + 0.8f
            result[i] = base * smooth
        }
        
        return result
    }
    
    private fun extractWaveform(uri: Uri) {
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(this, uri)
            
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 30000
            
            retriever.release()
            
            waveformData = generateWaveform(duration)
            
        } catch (e: Exception) {
            e.printStackTrace()
            waveformData = generateRandomWaveform()
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
        tvCurrentTime = findViewById(R.id.tvCurrentTime)
        tvSelectionInfo = findViewById(R.id.tvSelectionInfo)
        btnSetStart = findViewById(R.id.btnSetStart)
        btnSetEnd = findViewById(R.id.btnSetEnd)
        btnClearSelection = findViewById(R.id.btnClearSelection)
        
        sharedPrefs = getSharedPreferences("player_history", MODE_PRIVATE)
        
        // Настройка спиннера
        val pauseOptions = resources.getStringArray(R.array.pause_options)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, pauseOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerPause.adapter = adapter
        spinnerPause.setSelection(1)
        
        spinnerPause.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                currentPauseSeconds = PAUSE_VALUES[position]
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        
        btnSelect.setOnClickListener { selectAudioFile() }
        btnPlayStop.setOnClickListener { togglePlayStop() }
        btnSetStart.setOnClickListener { setSelectionStart() }
        btnSetEnd.setOnClickListener { setSelectionEnd() }
        btnClearSelection.setOnClickListener { clearSelection() }
        
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
        updateSelectionInfo()
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
                
                val tags = extractTags(uri)
                displayTags(tags)
                
                extractWaveform(uri)
                
                // Сбрасываем выделение при загрузке нового файла
                clearSelection()
                
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
    
    // ============ ВЫДЕЛЕНИЕ УЧАСТКА ============
    
    private fun setSelectionStart() {
        val player = mediaPlayer
        if (player == null || !player.isPlaying) {
            Toast.makeText(this, "Сначала запустите воспроизведение", Toast.LENGTH_SHORT).show()
            return
        }
        
        selectionStart = player.currentPosition
        if (selectionEnd != -1 && selectionStart > selectionEnd) {
            // Если начало позже конца, меняем местами
            val temp = selectionStart
            selectionStart = selectionEnd
            selectionEnd = temp
        }
        
        updateSelectionInfo()
        Toast.makeText(this, "Начало: ${formatTime(selectionStart)}", Toast.LENGTH_SHORT).show()
    }
    
    private fun setSelectionEnd() {
        val player = mediaPlayer
        if (player == null || !player.isPlaying) {
            Toast.makeText(this, "Сначала запустите воспроизведение", Toast.LENGTH_SHORT).show()
            return
        }
        
        selectionEnd = player.currentPosition
        if (selectionStart != -1 && selectionEnd < selectionStart) {
            // Если конец раньше начала, меняем местами
            val temp = selectionEnd
            selectionEnd = selectionStart
            selectionStart = temp
        }
        
        updateSelectionInfo()
        Toast.makeText(this, "Конец: ${formatTime(selectionEnd)}", Toast.LENGTH_SHORT).show()
    }
    
    private fun clearSelection() {
        selectionStart = -1
        selectionEnd = -1
        isLoopingSelection = false
        updateSelectionInfo()
        Toast.makeText(this, "Выделение сброшено", Toast.LENGTH_SHORT).show()
    }
    
    private fun updateSelectionInfo() {
        if (selectionStart == -1 && selectionEnd == -1) {
            tvSelectionInfo.text = "Участок не выделен (весь трек)"
            tvSelectionInfo.setBackgroundColor(0xFFF5F5F5.toInt())
        } else if (selectionStart != -1 && selectionEnd != -1) {
            val startStr = formatTime(selectionStart)
            val endStr = formatTime(selectionEnd)
            tvSelectionInfo.text = "🔵 $startStr  →  🔴 $endStr  (${formatTime(selectionEnd - selectionStart)})"
            tvSelectionInfo.setBackgroundColor(0xCC4CAF50.toInt())
        } else if (selectionStart != -1) {
            tvSelectionInfo.text = "🔵 Начало: ${formatTime(selectionStart)}  (конец не установлен)"
            tvSelectionInfo.setBackgroundColor(0xCCFFF3CD.toInt())
        } else {
            tvSelectionInfo.text = "🔴 Конец: ${formatTime(selectionEnd)}  (начало не установлено)"
            tvSelectionInfo.setBackgroundColor(0xCCFFCDD2.toInt())
        }
    }
    
    private fun formatTime(millis: Int): String {
        if (millis < 0) return "00:00"
        val seconds = millis / 1000
        val minutes = seconds / 60
        val secs = seconds % 60
        return String.format("%02d:%02d", minutes, secs)
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
            
            extractWaveform(uri)
            
            // Сбрасываем выделение при загрузке из истории
            clearSelection()
            
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
        
        if (waveformData == null) {
            extractWaveform(currentUri!!)
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
                
                // Если выделен участок, начинаем с начала участка
                if (selectionStart != -1 && selectionEnd != -1 && selectionStart < selectionEnd) {
                    seekTo(selectionStart)
                } else if (selectionStart != -1) {
                    seekTo(selectionStart)
                } else if (selectionEnd != -1) {
                    // Если только конец, начинаем с начала
                    seekTo(0)
                }
                
                start()
            }
            
            currentPlayingPosition = historyList.indexOfFirst { it.uriString == currentUriString }
            historyAdapter?.setPlayingPosition(currentPlayingPosition)
            updateHistoryUI()
            
            startMonitoringPlayback()
            startProgressUpdater()
            startTimeUpdater()
            
        } catch (e: Exception) {
            e.printStackTrace()
            tvStatus.text = "Ошибка: ${e.message}"
            Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
            isPlaying = false
            updatePlayStopButton()
        }
    }
    
    private fun startTimeUpdater() {
        timeUpdaterRunnable?.let { handler.removeCallbacks(it) }
        
        timeUpdaterRunnable = object : Runnable {
            override fun run() {
                if (!isPlaying) return
                
                val player = mediaPlayer
                if (player != null) {
                    val currentPos = player.currentPosition
                    tvCurrentTime.text = formatTime(currentPos)
                }
                
                handler.postDelayed(this, 200)
            }
        }
        
        handler.post(timeUpdaterRunnable!!)
    }
    
    private fun startProgressUpdater() {
        progressRunnable?.let { handler.removeCallbacks(it) }
        
        progressRunnable = object : Runnable {
            override fun run() {
                if (!isPlaying) return
                
                val player = mediaPlayer
                if (player != null && player.isPlaying && trackDuration > 0) {
                    val currentPosition = player.currentPosition
                    
                    // Вычисляем прогресс относительно всего трека или выделенного участка
                    val progress = if (selectionStart != -1 && selectionEnd != -1 && selectionStart < selectionEnd) {
                        // Прогресс в пределах выделенного участка
                        val rangeLength = (selectionEnd - selectionStart).toFloat()
                        if (rangeLength > 0) {
                            (currentPosition - selectionStart).toFloat() / rangeLength
                        } else {
                            0f
                        }
                    } else {
                        // Прогресс всего трека
                        currentPosition.toFloat() / trackDuration.toFloat()
                    }
                    
                    historyAdapter?.updateProgress(progress.coerceIn(0f, 1f))
                    
                    // Проверяем, не достигли ли конца выделенного участка
                    if (selectionStart != -1 && selectionEnd != -1 && selectionStart < selectionEnd) {
                        if (currentPosition >= selectionEnd) {
                            // Достигли конца участка - перематываем на начало
                            player.seekTo(selectionStart)
                            // Продолжаем воспроизведение
                        }
                    }
                    
                    handler.postDelayed(this, 100)
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
        timeUpdaterRunnable?.let { handler.removeCallbacks(it) }
        timeUpdaterRunnable = null
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
            tvCurrentTime.text = "00:00"
            updatePlayStopButton()
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
