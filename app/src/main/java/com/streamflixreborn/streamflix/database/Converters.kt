package com.streamflixreborn.streamflix.database

import androidx.room.TypeConverter
import com.streamflixreborn.streamflix.models.Download
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.utils.format
import com.streamflixreborn.streamflix.utils.toCalendar
import java.util.Calendar

class Converters {

    @TypeConverter
    fun fromCalendar(value: Calendar?): String? {
        return value?.format("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
    }

    @TypeConverter
    fun toCalendar(value: String?): Calendar? {
        return value?.toCalendar()
    }


    @TypeConverter
    fun fromTvShow(value: TvShow?): String? {
        return value?.id
    }

    @TypeConverter
    fun toTvShow(value: String?): TvShow? {
        return value?.let { TvShow(it, "") }
    }


    @TypeConverter
    fun fromSeason(value: Season?): String? {
        return value?.id
    }

    @TypeConverter
    fun toSeason(value: String?): Season? {
        return value?.let { Season(it, 0) }
    }

    @TypeConverter
    fun fromDownloadContentType(value: Download.ContentType): String {
        return value.name
    }

    @TypeConverter
    fun toDownloadContentType(value: String): Download.ContentType {
        return Download.ContentType.valueOf(value)
    }

    @TypeConverter
    fun fromDownloadStatus(value: Download.DownloadStatus): String {
        return value.name
    }

    @TypeConverter
    fun toDownloadStatus(value: String): Download.DownloadStatus {
        return Download.DownloadStatus.valueOf(value)
    }

    @TypeConverter
    fun fromStringMap(value: Map<String, String>?): String? {
        if (value == null) return null
        return value.entries.joinToString("|") { "${it.key}=${it.value}" }
    }

    @TypeConverter
    fun toStringMap(value: String?): Map<String, String> {
        if (value.isNullOrEmpty()) return emptyMap()
        return value.split("|").associate { entry ->
            val parts = entry.split("=", limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else parts[0] to ""
        }
    }
}