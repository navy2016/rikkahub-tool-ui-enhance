package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

val Migration_23_24 = object : Migration(23, 24) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DatabaseMigrationTracker.onMigrationStart(23, 24)
        try {
            db.execSQL("ALTER TABLE ConversationEntity ADD COLUMN dialogue_summary_text TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE ConversationEntity ADD COLUMN dialogue_summary_token_estimate INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE ConversationEntity ADD COLUMN dialogue_summary_updated_at INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE ConversationEntity ADD COLUMN memory_ledger_status TEXT NOT NULL DEFAULT 'idle'")
            db.execSQL("ALTER TABLE ConversationEntity ADD COLUMN memory_ledger_error TEXT NOT NULL DEFAULT ''")

            db.execSQL("ALTER TABLE compression_event ADD COLUMN dialogue_summary_text TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE compression_event ADD COLUMN dialogue_summary_preview TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE compression_event ADD COLUMN ledger_snapshot TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE compression_event ADD COLUMN base_dialogue_summary_text TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE compression_event ADD COLUMN base_ledger_json TEXT NOT NULL DEFAULT ''")
        } finally {
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
