package com.playceviri.tv.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Phase { IDLE, WAKING, CONNECTING, STREAMING, RECONNECTING, ERROR }

/** Ekranda ve tanılama kaydında gösterilen canlı durum. */
data class ServiceState(
    val phase: Phase = Phase.IDLE,
    val message: String = "Hazır",
    val ocrMs: Long = 0,
    val translateMs: Long = 0,
    val totalMs: Long = 0,
    val engine: String = "",
    val lastOriginal: String = "",
    val lastTranslated: String = "",
    val readsPerSec: Float = 0f,
    val skippedSame: Long = 0,
    val rejected: Long = 0,
    val lastRejected: String = "",
    val game: String = "",
    val tvLink: String = "",
)

object Live {
    private val _state = MutableStateFlow(ServiceState())
    val state: StateFlow<ServiceState> = _state.asStateFlow()
    fun update(f: (ServiceState) -> ServiceState) {
        while (true) {
            val cur = _state.value
            if (_state.compareAndSet(cur, f(cur))) return
        }
    }
    fun reset() { _state.value = ServiceState() }
}
