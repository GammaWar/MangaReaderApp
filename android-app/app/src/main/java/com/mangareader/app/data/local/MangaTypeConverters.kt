package com.mangareader.app.data.local

import androidx.room.TypeConverter
import org.json.JSONArray

class MangaTypeConverters {

    @TypeConverter
    fun fromStringList(list: List<String>?): String? {
        if (list == null) return null
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        return arr.toString()
    }

    @TypeConverter
    fun toStringList(json: String?): List<String>? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            val arr = JSONArray(json)
            val result = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val value = arr.optString(i, "")
                if (value.isNotEmpty()) {
                    result.add(value)
                }
            }
            result
        }.getOrDefault(emptyList())
    }
}
