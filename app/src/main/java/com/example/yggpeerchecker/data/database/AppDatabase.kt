package com.example.yggpeerchecker.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Host::class, CheckResult::class, DnsCache::class],
    version = 4,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun hostDao(): HostDao
    abstract fun checkResultDao(): CheckResultDao
    abstract fun dnsCacheDao(): DnsCacheDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // Миграция v3 → v4: ip4/ip5 + dnsSource1-5
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Host таблица: ip4, ip5, dnsSource1-5
                db.execSQL("ALTER TABLE hosts ADD COLUMN dnsIp4 TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE hosts ADD COLUMN dnsIp5 TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE hosts ADD COLUMN dnsSource1 TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE hosts ADD COLUMN dnsSource2 TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE hosts ADD COLUMN dnsSource3 TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE hosts ADD COLUMN dnsSource4 TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE hosts ADD COLUMN dnsSource5 TEXT DEFAULT NULL")
                // DnsCache таблица: ip4, ip5, dnsSource1-5
                db.execSQL("ALTER TABLE dns_cache ADD COLUMN ip4 TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE dns_cache ADD COLUMN ip5 TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE dns_cache ADD COLUMN dnsSource1 TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE dns_cache ADD COLUMN dnsSource2 TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE dns_cache ADD COLUMN dnsSource3 TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE dns_cache ADD COLUMN dnsSource4 TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE dns_cache ADD COLUMN dnsSource5 TEXT DEFAULT NULL")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ygg_peer_checker_db"
                )
                    .addMigrations(MIGRATION_3_4)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
