package com.example.ui

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

internal fun InterpreterViewModel.refreshLiveSummary() {
    _currentAiSummaryText.value = "Tóm tắt đã tắt trong bản build này."
}

internal fun InterpreterViewModel.generateConversationSummary() {
    _activeSummary.value = "Tóm tắt đã tắt trong bản build này."
    _currentAiSummaryText.value = "Tóm tắt đã tắt trong bản build này."
}

internal fun InterpreterViewModel.dismissActiveSummary() { _activeSummary.value = null }
internal fun InterpreterViewModel.deleteHistoryRecord(id: Long) { viewModelScope.launch { repository.deleteRecord(id) } }
internal fun InterpreterViewModel.deleteSession(sessionId: String) {
    viewModelScope.launch {
        val recordsToDelete = translationRecords.value.filter { it.sessionId == sessionId }
        recordsToDelete.forEach { repository.deleteRecord(it.id) }
    }
}
internal fun InterpreterViewModel.clearCurrentSessionHistory() {
    val sessionId = _currentSessionId.value
    if (!sessionId.isNullOrBlank()) {
        deleteSession(sessionId)
    }
    _currentSessionId.value = null
    _currentOriginalText.value = ""
    _currentTranslatedText.value = ""
    _currentAiSummaryText.value = ""
}
internal fun InterpreterViewModel.clearAllHistory() { viewModelScope.launch { repository.clearHistory() } }
internal fun InterpreterViewModel.deleteSummary(id: Long) { viewModelScope.launch { repository.deleteSummary(id) } }
internal fun InterpreterViewModel.clearAllSummaries() { viewModelScope.launch { repository.clearSummaries() } }
