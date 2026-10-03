package com.talkback.translator.engine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class HistoryItem(
    val originalText: String,
    val germanText: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun formatTimestamp(): String {
        return try {
            val sdf = java.text.SimpleDateFormat("HH:mm, dd.MM.", java.util.Locale.getDefault())
            sdf.format(java.util.Date(timestamp))
        } catch (_: Exception) {
            ""
        }
    }
}

class HistoryManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("translator_history", Context.MODE_PRIVATE)
    private val key = "items"
    private val maxItems = 30

    fun addTranslation(original: String, german: String) {
        if (original.isBlank() || german.isBlank()) return
        val current = getHistory().toMutableList()
        // Wenn identischer Eintrag schon ganz oben steht, überspringen
        if (current.isNotEmpty() && current.first().originalText == original && current.first().germanText == german) {
            return
        }
        current.add(0, HistoryItem(original, german))
        while (current.size > maxItems) {
            current.removeAt(current.size - 1)
        }
        saveHistory(current)
    }

    fun getHistory(): List<HistoryItem> {
        val jsonStr = prefs.getString(key, null) ?: return emptyList()
        val list = mutableListOf<HistoryItem>()
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    HistoryItem(
                        originalText = obj.optString("orig", ""),
                        germanText = obj.optString("de", ""),
                        timestamp = obj.optLong("time", 0L)
                    )
                )
            }
        } catch (_: Exception) {}
        return list
    }

    fun clearHistory() {
        prefs.edit().remove(key).apply()
    }

    private fun saveHistory(list: List<HistoryItem>) {
        try {
            val jsonArray = JSONArray()
            for (item in list) {
                val obj = JSONObject().apply {
                    put("orig", item.originalText)
                    put("de", item.germanText)
                    put("time", item.timestamp)
                }
                jsonArray.put(obj)
            }
            prefs.edit().putString(key, jsonArray.toString()).apply()
        } catch (_: Exception) {}
    }
}
