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
        
        // Добавляем views в itemView
        itemView.addView(infoText)
        itemView.addView(playBtn)
        
        // Добавляем itemView в historyContainer
        historyContainer.addView(itemView)
    }
}
