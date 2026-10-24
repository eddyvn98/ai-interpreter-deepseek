package com.example.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "translation_records")
data class TranslationRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val originalText: String,
    val translatedText: String,
    val sourceLang: String,
    val targetLang: String,
    val sessionId: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "conversation_summaries")
data class ConversationSummary(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val summaryText: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "meeting_audio_segments")
data class MeetingAudioSegment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val segmentIndex: Int,
    val filePath: String,
    val startTimeMs: Long,
    val durationSec: Int,
    val status: String, // "PENDING", "COMPLETED", "FAILED"
    val speakerLabel: String? = null,
    val transcribedText: String? = null
)

@Dao
interface TranslationDao {
    @Query("SELECT * FROM translation_records ORDER BY timestamp DESC")
    fun getAllRecords(): Flow<List<TranslationRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecord(record: TranslationRecord): Long

    @Query("SELECT * FROM translation_records WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getRecordBySessionId(sessionId: String): TranslationRecord?

    @Query("DELETE FROM translation_records")
    suspend fun clearAllRecords()

    @Query("DELETE FROM translation_records WHERE id = :id")
    suspend fun deleteRecord(id: Long)

    @Query("SELECT * FROM conversation_summaries ORDER BY timestamp DESC")
    fun getAllSummaries(): Flow<List<ConversationSummary>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSummary(summary: ConversationSummary): Long

    @Query("DELETE FROM conversation_summaries WHERE id = :id")
    suspend fun deleteSummary(id: Long)

    @Query("DELETE FROM conversation_summaries")
    suspend fun clearAllSummaries()

    // Meeting segments queries
    @Query("SELECT * FROM meeting_audio_segments WHERE sessionId = :sessionId ORDER BY segmentIndex ASC")
    fun getSegmentsBySessionId(sessionId: String): Flow<List<MeetingAudioSegment>>

    @Query("SELECT * FROM meeting_audio_segments WHERE sessionId = :sessionId ORDER BY segmentIndex ASC")
    suspend fun getSegmentsBySessionIdDirect(sessionId: String): List<MeetingAudioSegment>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSegment(segment: MeetingAudioSegment): Long

    @Query("UPDATE meeting_audio_segments SET status = :status, speakerLabel = :speakerLabel, transcribedText = :transcribedText WHERE id = :id")
    suspend fun updateSegmentStatus(id: Long, status: String, speakerLabel: String?, transcribedText: String?)

    @Query("SELECT * FROM meeting_audio_segments WHERE status = 'FAILED' OR status = 'PENDING'")
    suspend fun getUnfinishedSegments(): List<MeetingAudioSegment>

    @Query("DELETE FROM meeting_audio_segments WHERE sessionId = :sessionId")
    suspend fun deleteSegmentsBySession(sessionId: String)
}

@Database(entities = [TranslationRecord::class, ConversationSummary::class, MeetingAudioSegment::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun translationDao(): TranslationDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: android.content.Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "interpreter_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

