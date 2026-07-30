package com.tensal.denden.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        DenDenEvent::class,
        TrashedChannel::class,
        EventTag::class,
        ChannelState::class,
        MessageReceipt::class,
        PendingAlert::class,
        StopTombstone::class
    ],
    version = 1,
    exportSchema = true
)
abstract class EventDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao
    abstract fun messageQueryDao(): MessageQueryDao
    abstract fun directMessageDao(): DirectMessageDao

    companion object {
        @Volatile
        private var instance: EventDatabase? = null

        fun getInstance(context: Context): EventDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                EventDatabase::class.java,
                "denden_events.db"
            ).build().also { instance = it }
        }
    }
}
