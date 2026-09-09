package ru.injent.service.google

import com.google.api.client.googleapis.json.GoogleJsonResponseException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

// ponytail: one queue for reads and writes in this single-account process;
// use a shared per-account limiter if the server is deployed in multiple replicas.
internal class SheetsRequestGate(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val mutex = Mutex()
    private var nextRequestAt = 0L
    private val waiting = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = waiting

    suspend fun <T> execute(request: () -> T): T = mutex.withLock {
        try {
            for (attempt in 0..5) {
                val wait = nextRequestAt - nowMillis()
                if (wait > 0) {
                    waiting.value = "Запросы к Google Sheets выполняются по очереди. Пожалуйста, подождите."
                    pause(wait)
                }
                // At most 50 requests/minute, leaving headroom below the 60/minute quota.
                nextRequestAt = nowMillis() + 1_200
                try {
                    return@withLock request()
                } catch (error: GoogleJsonResponseException) {
                    val quota = error.statusCode == 429 || (error.statusCode == 403 &&
                        error.details?.errors.orEmpty().any {
                            it.reason in setOf("rateLimitExceeded", "userRateLimitExceeded", "quotaExceeded")
                        })
                    if ((!quota && error.statusCode != 503) || attempt == 5) throw error
                    val backoff = maxOf(
                        error.headers?.retryAfter?.toLongOrNull()?.times(1_000) ?: 0,
                        minOf(32_000L, 1_000L shl attempt) + Random.nextLong(1_000),
                    )
                    waiting.value = "Google Sheets временно ограничил запросы. Повторим автоматически — пожалуйста, подождите."
                    pause(backoff)
                }
            }
            error("Unreachable Sheets retry state")
        } finally {
            waiting.value = null
        }
    }
}
