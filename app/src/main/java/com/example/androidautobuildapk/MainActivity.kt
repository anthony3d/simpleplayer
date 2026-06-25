package com.example.simpleplayer

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
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
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

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
    private val WAVEFORM_COLUMNS = 200
    
    private lateinit var sharedPrefs: SharedPreferences
    private var historyList = mutableListOf<HistoryItem>()
    private var historyAdapter: HistoryAdapter? = null
    private var currentPlayingPosition = -1
    
    // Кеш для волновых форм
    private val waveformCache = mutableMapOf<String, FloatArray>()
    
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
            
            // Формируем текст
            val prefix = if (position == playingPosition) "▶ " else ""
            textView.text = "$prefix${item.fileName}"
            
            // Настройка цвета
            if (position == playingPosition) {
                progressFill.setBackgroundColor(0xFF4CAF50.toInt())
                textView.setTextColor(0xFFFFFFFF.toInt())
                textView.setShadowLayer(2f, 1f, 1f, 0xCC000000.toInt())
            } else {
                progressFill.setBackgroundColor(0x334CAF50.toInt())
                textView.setTextColor(0xFF333333.toInt())
                textView.setShadowLayer(0f, 0f, 0f, 0)
            }
            
            // Рисуем волновую форму
            drawWaveform(waveformContainer, position)
            
            // Обновляем прогресс
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
    
    // ============ ИЗВЛЕЧЕНИЕ РЕАЛЬНОЙ ВОЛНОВОЙ ФОРМЫ ============
    
    private fun getWaveformForItem(item: HistoryItem): FloatArray? {
        // Проверяем кеш
        if (waveformCache.containsKey(item.uriString)) {
            return waveformCache[item.uriString]
        }
        
        // Извлекаем реальную волновую форму
        try {
            val uri = Uri.parse(item.uriString)
            val waveform = extractRealWaveform(uri)
            
            if (waveform != null && waveform.isNotEmpty()) {
                waveformCache[item.uriString] = waveform
                return waveform
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        // Если не удалось, возвращаем null
        return null
    }
    
    private fun extractRealWaveform(uri: Uri): FloatArray? {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(this, uri, null)
            
            // Находим аудио-трек
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("audio/") == true) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }
            
            if (audioTrackIndex == -1 || audioFormat == null) {
                return null
            }
            
            extractor.selectTrack(audioTrackIndex)
            
            // Создаем декодер
            val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return null
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(audioFormat, null, null, 0)
            decoder.start()
            
            // Буферы для декодирования
            val inputBuffers = decoder.inputBuffers
            val outputBuffers = decoder.outputBuffers
            val bufferInfo = MediaCodec.BufferInfo()
            
            var isEos = false
            var allSamples = mutableListOf<Float>()
            var sampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channelCount = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            
            // Читаем и декодируем аудио
            while (!isEos) {
                // Входные данные
                val inputIndex = decoder.dequeueInputBuffer(10000)
                if (inputIndex >= 0) {
                    val inputBuffer = inputBuffers[inputIndex]
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        isEos = true
                    } else {
                        val presentationTime = extractor.sampleTime
                        decoder.queueInputBuffer(inputIndex, 0, sampleSize, presentationTime, 0)
                        extractor.advance()
                    }
                }
                
                // Выходные данные
                val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000)
                if (outputIndex >= 0) {
                    val outputBuffer = outputBuffers[outputIndex]
                    
                    if (bufferInfo.size > 0) {
                        // Конвертируем байты в PCM
                        val pcmData = decodePCM(outputBuffer, bufferInfo)
                        allSamples.addAll(pcmData)
                    }
                    
                    decoder.releaseOutputBuffer(outputIndex, false)
                    
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        isEos = true
                    }
                }
            }
            
            decoder.stop()
            decoder.release()
            extractor.release()
            
            // Если нет данных, возвращаем null
            if (allSamples.isEmpty()) {
                return null
            }
            
            // Создаем волновую форму
            return createWaveformFromSamples(allSamples, sampleRate)
            
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                decoder?.stop()
                decoder?.release()
                extractor?.release()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
            return null
        }
    }
    
    private fun decodePCM(buffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo): List<Float> {
        val samples = mutableListOf<Float>()
        val bytes = ByteArray(bufferInfo.size)
        buffer.get(bytes)
        
        // Предполагаем 16-bit PCM (наиболее распространенный)
        for (i in 0 until bytes.size step 2) {
            if (i + 1 < bytes.size) {
                // Конвертируем 2 байта в short (16-bit)
                val sample = ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xFF))
                val normalized = sample.toFloat() / Short.MAX_VALUE
                samples.add(normalized)
            }
        }
        
        return samples
    }
    
    private fun createWaveformFromSamples(samples: List<Float>, sampleRate: Int): FloatArray {
        if (samples.isEmpty()) return FloatArray(WAVEFORM_COLUMNS) { 0.1f }
        
        val columns = WAVEFORM_COLUMNS
        val result = FloatArray(columns)
        
        // Количество сэмплов на столбец
        val samplesPerColumn = samples.size / columns
        if (samplesPerColumn == 0) {
            // Если сэмплов меньше чем столбцов
            for (i in 0 until columns) {
                val index = (i * samples.size.toFloat() / columns).toInt()
                result[i] = samples.getOrElse(index) { 0.1f }
            }
            return result
        }
        
        // Для каждого столбца берем пиковое значение
        for (i in 0 until columns) {
            val start = i * samplesPerColumn
            val end = (i + 1) * samplesPerColumn
            var max = 0f
            
            for (j in start until end.coerceAtMost(samples.size)) {
                val value = Math.abs(samples[j])
                if (value > max) {
                    max = value
                }
            }
            
            // Минимальная амплитуда для видимости
            result[i] = max.coerceAtLeast(0.05f)
        }
        
        // Нормализуем
        val max = result.maxOrNull() ?: 1f
        if (max > 0) {
            for (i in result.indices) {
                result[i] = result[i] / max
            }
        }
        
        return result
    }
    
    private fun extractWaveform(uri: Uri) {
        try {
            val waveform = extractRealWaveform(uri)
            if (waveform != null && waveform.isNotEmpty()) {
                waveformCache[uri.toString()] = waveform
            }
        } catch (e: Exception) {
            e.printStackTrace()
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
        spinnerPause.setSelection(1)
        
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
                
                // Извлекаем реальную волновую форму
                extractWaveform(uri)
                
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
            
            extractWaveform(uri)
            
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
                    
                    historyAdapter?.updateProgress(progress)
                    
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
