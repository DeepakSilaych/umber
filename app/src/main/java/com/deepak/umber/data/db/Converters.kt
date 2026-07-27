package com.deepak.umber.data.db

import androidx.room.TypeConverter
import com.deepak.umber.data.model.CategorySource
import com.deepak.umber.data.model.Channel
import com.deepak.umber.data.model.Direction
import com.deepak.umber.data.model.LocConfidence
import com.deepak.umber.data.model.SourceType

/**
 * Enums are stored by name, not ordinal. Ordinals would silently remap every existing row the first
 * time someone inserts a value in the middle of an enum.
 *
 * Unknown names decode to a safe default rather than throwing, so a downgrade can still read a
 * newer database.
 */
class Converters {

    @TypeConverter fun sourceToString(v: SourceType): String = v.name

    @TypeConverter
    fun stringToSource(v: String): SourceType =
        runCatching { SourceType.valueOf(v) }.getOrDefault(SourceType.SMS)

    @TypeConverter fun directionToString(v: Direction): String = v.name

    @TypeConverter
    fun stringToDirection(v: String): Direction =
        runCatching { Direction.valueOf(v) }.getOrDefault(Direction.DEBIT)

    @TypeConverter fun channelToString(v: Channel): String = v.name

    @TypeConverter
    fun stringToChannel(v: String): Channel =
        runCatching { Channel.valueOf(v) }.getOrDefault(Channel.UNKNOWN)

    @TypeConverter fun catSourceToString(v: CategorySource): String = v.name

    @TypeConverter
    fun stringToCatSource(v: String): CategorySource =
        runCatching { CategorySource.valueOf(v) }.getOrDefault(CategorySource.NONE)

    @TypeConverter fun locConfToString(v: LocConfidence): String = v.name

    @TypeConverter
    fun stringToLocConf(v: String): LocConfidence =
        runCatching { LocConfidence.valueOf(v) }.getOrDefault(LocConfidence.NONE)
}
