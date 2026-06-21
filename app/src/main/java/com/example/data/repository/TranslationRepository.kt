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
}
