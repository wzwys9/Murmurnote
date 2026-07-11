package app.murmurnote.android.service

import java.util.ArrayDeque

/** Main-thread-owned request state for [TranscriptionService]. */
internal class SerialProcessingRequests<T : Any> {
    private val pending = ArrayDeque<T>()

    var current: T? = null
        private set

    var latestStartId: Int = 0
        private set

    val hasWork: Boolean
        get() = current != null || pending.isNotEmpty()

    fun observeStart(startId: Int) {
        latestStartId = maxOf(latestStartId, startId)
    }

    fun enqueue(request: T, startId: Int) {
        observeStart(startId)
        pending.addLast(request)
    }

    fun takeIfIdle(): T? {
        if (current != null) return null
        return pending.pollFirst()?.also { current = it }
    }

    fun complete(request: T) {
        check(current == request) { "Only the current processing request can complete" }
        current = null
    }

    fun cancelPending(): List<T> = buildList {
        while (pending.isNotEmpty()) add(pending.removeFirst())
    }
}
