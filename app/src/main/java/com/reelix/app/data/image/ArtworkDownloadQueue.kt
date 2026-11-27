package com.reelix.app.data.image

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Cancellation alone cannot stop blocking image IO. Validate again at the atomic persistence point. */
class ArtworkCommitGate {
    private var sequence = 0L
    private val latest = mutableMapOf<String, Long>()
    @Synchronized fun begin(key: String): Ticket = Ticket(key, ++sequence).also { latest[key] = sequence }
    inner class Ticket internal constructor(private val key: String, private val sequence: Long) {
        fun isCurrent(): Boolean = synchronized(this@ArtworkCommitGate) { latest[key] == sequence }
        fun commit(action: () -> Unit): Boolean = synchronized(this@ArtworkCommitGate) {
            if (latest[key] != sequence) false else { action(); true }
        }
    }
}

enum class ArtworkDownloadPhase { RUNNING, SUCCEEDED, FAILED }
data class ArtworkDownloadStatus(val title: String, val phase: ArtworkDownloadPhase)

/** The supplied scope belongs to the process, never to the search/preview dialog. */
class ArtworkDownloadQueue(private val scope: CoroutineScope,
    private val onFinished: (ArtworkDownloadStatus) -> Unit = {}) {
    private val gate = ArtworkCommitGate()
    private val jobs = mutableMapOf<String, Job>()
    private val retries = mutableMapOf<String, Pair<String, suspend (ArtworkCommitGate.Ticket) -> Boolean>>()
    private val mutableStates = MutableStateFlow<Map<String, ArtworkDownloadStatus>>(emptyMap())
    val states = mutableStates.asStateFlow()

    @Synchronized fun submit(key: String, title: String, transfer: suspend (ArtworkCommitGate.Ticket) -> Boolean) {
        val ticket = gate.begin(key)
        jobs.remove(key)?.cancel()
        retries[key] = title to transfer
        mutableStates.value = mutableStates.value + (key to ArtworkDownloadStatus(title, ArtworkDownloadPhase.RUNNING))
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val saved = transfer(ticket)
                ensureActive()
                if (saved) finish(key, title, ticket, ArtworkDownloadPhase.SUCCEEDED)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { finish(key, title, ticket, ArtworkDownloadPhase.FAILED) }
        }
        jobs[key] = job
        job.start()
    }
    @Synchronized private fun finish(key: String, title: String, ticket: ArtworkCommitGate.Ticket, phase: ArtworkDownloadPhase) {
        if (!ticket.isCurrent()) return
        jobs.remove(key)
        if (phase == ArtworkDownloadPhase.SUCCEEDED) retries.remove(key)
        val status = ArtworkDownloadStatus(title, phase)
        // Bound process-only feedback; downloaded artwork and metadata have their own durable store.
        val running = mutableStates.value.filterValues { it.phase == ArtworkDownloadPhase.RUNNING } - key
        val finished = (mutableStates.value - key - running.keys).entries.toList().takeLast(63).associate { it.toPair() }
        mutableStates.value = running + finished + (key to status)
        val retained = mutableStates.value.keys
        retries.keys.retainAll(retained)
        onFinished(status)
    }
    @Synchronized fun retry(key: String): Boolean {
        if (mutableStates.value[key]?.phase != ArtworkDownloadPhase.FAILED) return false
        val (title, transfer) = retries[key] ?: return false
        submit(key, title, transfer)
        return true
    }
}
