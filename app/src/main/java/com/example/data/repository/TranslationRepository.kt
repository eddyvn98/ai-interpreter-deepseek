package com.example.data.repository

import com.example.data.database.ConversationSummary
import com.example.data.database.TranslationDao
import com.example.data.database.TranslationRecord
import kotlinx.coroutines.flow.Flow

class TranslationRepository(private val dao: TranslationDao) {
    val allRecords: Flow<List<TranslationRecord>> = dao.getAllRecords()
    val allSummaries: Flow<List<ConversationSummary>> = dao.getAllSummaries()

    suspend fun insertRecord(
        originalText: String,
        translatedText: String,
        sourceLang: String,
        targetLang: String,
        sessionId: String
    ): Long {
        val record = TranslationRecord(
            originalText = originalText,
            translatedText = translatedText,
            sourceLang = sourceLang,
            targetLang = targetLang,
            sessionId = sessionId
        )
        return dao.insertRecord(record)
    }

    suspend fun getRecordBySessionId(sessionId: String): TranslationRecord? {
        return dao.getRecordBySessionId(sessionId)
    }

    suspend fun insertRecordDirectly(record: TranslationRecord): Long {
        return dao.insertRecord(record)
    }

    suspend fun deleteRecord(id: Long) {
        dao.deleteRecord(id)
    }

    suspend fun clearHistory() {
        dao.clearAllRecords()
    }

    suspend fun insertSummary(summaryText: String): Long {
        return dao.insertSummary(ConversationSummary(summaryText = summaryText))
    }

    suspend fun deleteSummary(id: Long) {
        dao.deleteSummary(id)
    }

    suspend fun clearSummaries() {
        dao.clearAllSummaries()
    }

    // Meeting segments repository methods
    fun getSegmentsBySessionId(sessionId: String): Flow<List<com.example.data.database.MeetingAudioSegment>> {
        return dao.getSegmentsBySessionId(sessionId)
    }

    suspend fun getSegmentsBySessionIdDirect(sessionId: String): List<com.example.data.database.MeetingAudioSegment> {
        return dao.getSegmentsBySessionIdDirect(sessionId)
    }

    suspend fun insertSegment(segment: com.example.data.database.MeetingAudioSegment): Long {
        return dao.insertSegment(segment)
    }

    suspend fun updateSegmentStatus(id: Long, status: String, speakerLabel: String?, transcribedText: String?) {
        dao.updateSegmentStatus(id, status, speakerLabel, transcribedText)
    }

    suspend fun getUnfinishedSegments(): List<com.example.data.database.MeetingAudioSegment> {
        return dao.getUnfinishedSegments()
    }

    suspend fun deleteSegmentsBySession(sessionId: String) {
        dao.deleteSegmentsBySession(sessionId)
    }
}

