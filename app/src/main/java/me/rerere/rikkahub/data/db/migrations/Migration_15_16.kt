package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.migrateToolNodes
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker
import me.rerere.rikkahub.utils.JsonInstant

private const val TAG = "Migration_15_16"

private val expectedManagedFilesColumnsV16 = setOf(
    "id",
    "folder",
    "relative_path",
    "display_name",
    "mime_type",
    "size_bytes",
    "created_at",
    "updated_at"
)

private val expectedFavoritesColumnsV16 = setOf(
    "id",
    "type",
    "ref_key",
    "ref_json",
    "snapshot_json",
    "meta_json",
    "created_at",
    "updated_at"
)

private fun tableColumnsV16(db: SupportSQLiteDatabase, tableName: String): Set<String> {
    val cursor = db.query("PRAGMA table_info($tableName)")
    val columns = mutableSetOf<String>()
    cursor.use {
        val nameIndex = it.getColumnIndex("name")
        while (it.moveToNext()) {
            columns.add(it.getString(nameIndex))
        }
    }
    return columns
}

private fun recreateManagedFilesTableV16(db: SupportSQLiteDatabase) {
    db.execSQL("DROP TABLE IF EXISTS managed_files")
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS managed_files (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            folder TEXT NOT NULL,
            relative_path TEXT NOT NULL,
            display_name TEXT NOT NULL,
            mime_type TEXT NOT NULL,
            size_bytes INTEGER NOT NULL,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
        )
        """.trimIndent()
    )
    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_managed_files_relative_path ON managed_files(relative_path)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_managed_files_folder ON managed_files(folder)")
}

private fun recreateFavoritesTableV16(db: SupportSQLiteDatabase) {
    db.execSQL("DROP TABLE IF EXISTS favorites")
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS favorites (
            id TEXT NOT NULL PRIMARY KEY,
            type TEXT NOT NULL,
            ref_key TEXT NOT NULL,
            ref_json TEXT NOT NULL,
            snapshot_json TEXT NOT NULL,
            meta_json TEXT,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
        )
        """.trimIndent()
    )
    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_favorites_ref_key ON favorites(ref_key)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_favorites_type ON favorites(type)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_favorites_created_at ON favorites(created_at)")
}

val Migration_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 15 to 16 (eager tool message migration)")
        DatabaseMigrationTracker.onMigrationStart(15, 16)
        db.beginTransaction()
        try {
            val conversationColumns = tableColumnsV16(db, "conversationentity")
            if ("workflow_state" !in conversationColumns) {
                db.execSQL("ALTER TABLE conversationentity ADD COLUMN workflow_state TEXT NOT NULL DEFAULT ''")
            }

            val managedFilesColumns = tableColumnsV16(db, "managed_files")
            if (managedFilesColumns != expectedManagedFilesColumnsV16) {
                recreateManagedFilesTableV16(db)
            }

            val favoritesColumns = tableColumnsV16(db, "favorites")
            if (favoritesColumns != expectedFavoritesColumnsV16) {
                recreateFavoritesTableV16(db)
            } else {
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_favorites_ref_key ON favorites(ref_key)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_favorites_type ON favorites(type)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_favorites_created_at ON favorites(created_at)")
            }

            data class NodeRow(val id: String, val messages: List<UIMessage>, val selectIndex: Int)

            // Get all distinct conversation IDs
            val convCursor = db.query("SELECT DISTINCT conversation_id FROM message_node")
            val conversationIds = mutableListOf<String>()
            while (convCursor.moveToNext()) {
                conversationIds.add(convCursor.getString(0))
            }
            convCursor.close()

            var updatedConversations = 0

            for (conversationId in conversationIds) {
                // Load all nodes for this conversation ordered by node_index
                val nodeCursor = db.query(
                    "SELECT id, messages, node_index, select_index FROM message_node WHERE conversation_id = ? ORDER BY node_index ASC",
                    arrayOf(conversationId)
                )

                val rows = mutableListOf<NodeRow>()
                while (nodeCursor.moveToNext()) {
                    val id = nodeCursor.getString(0)
                    val messagesJson = nodeCursor.getString(1)
                    val selectIndex = nodeCursor.getInt(3)
                    runCatching {
                        val messages = JsonInstant.decodeFromString<List<UIMessage>>(messagesJson)
                        rows.add(NodeRow(id, messages, selectIndex))
                    }.onFailure {
                        Log.w(TAG, "migrate: failed to parse messages for node $id", it)
                    }
                }
                nodeCursor.close()

                if (rows.isEmpty()) continue

                // Apply migration: merge TOOL role nodes into preceding ASSISTANT nodes,
                // and convert legacy ToolCall/ToolResult parts to the unified Tool part
                val migrated = rows.migrateToolNodes(
                    getMessages = { it.messages },
                    setMessages = { row, msgs -> row.copy(messages = msgs) }
                )

                // Skip if nothing changed
                val changed = migrated.size != rows.size ||
                    migrated.zip(rows).any { (a, b) -> a.messages != b.messages }
                if (!changed) continue

                // Delete old nodes and re-insert migrated ones with corrected node_index
                db.execSQL("DELETE FROM message_node WHERE conversation_id = ?", arrayOf(conversationId))
                migrated.forEachIndexed { index, row ->
                    val messagesJson = JsonInstant.encodeToString(row.messages)
                    db.execSQL(
                        "INSERT INTO message_node (id, conversation_id, node_index, messages, select_index) VALUES (?, ?, ?, ?, ?)",
                        arrayOf<Any?>(row.id, conversationId, index, messagesJson, row.selectIndex)
                    )
                }
                updatedConversations++
            }

            db.setTransactionSuccessful()
            Log.i(TAG, "migrate: migrate from 15 to 16 success ($updatedConversations conversations updated)")
        } finally {
            db.endTransaction()
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
