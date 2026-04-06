package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

val Migration_24_25 = object : Migration(24, 25) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DatabaseMigrationTracker.onMigrationStart(24, 25)
        try {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `pending_ledger_batch` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `conversation_id` TEXT NOT NULL,
                    `event_id` INTEGER NOT NULL,
                    `start_index` INTEGER NOT NULL,
                    `end_index` INTEGER NOT NULL,
                    `incremental_messages` TEXT NOT NULL,
                    `status` TEXT NOT NULL DEFAULT 'pending',
                    `attempt_count` INTEGER NOT NULL DEFAULT 0,
                    `last_error` TEXT NOT NULL DEFAULT '',
                    `created_at` INTEGER NOT NULL,
                    `updated_at` INTEGER NOT NULL,
                    FOREIGN KEY(`conversation_id`) REFERENCES `ConversationEntity`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_pending_ledger_batch_conversation_id` ON `pending_ledger_batch` (`conversation_id`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_pending_ledger_batch_conversation_id_status` ON `pending_ledger_batch` (`conversation_id`, `status`)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_pending_ledger_batch_conversation_id_event_id` ON `pending_ledger_batch` (`conversation_id`, `event_id`)"
            )
        } finally {
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
