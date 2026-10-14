package me.rerere.rikkahub.benchmark

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** One launch, scalar receipts only. Completion is durable even if it arrives before await(). */
internal class ProductionBenchmarkProgress(private val token: String) {
    private val phases = listOf("prepared", "mounted", "done")
    private val receipts = phases.associateWith { CountDownLatch(1) }
    private val evidence = mutableMapOf<String, String>()
    @Volatile var currentPhase = "preparing"
        private set
    @Volatile private var failure: String? = null

    @Synchronized
    fun accept(receivedToken: String?, next: String?, publication: String? = null): Boolean {
        if (receivedToken != token || next == null) return false
        if (failure != null) return true
        if (next == currentPhase) {
            if (publication == null || evidence[next] == publication) return true
            failure = "Conflicting benchmark publication receipt: $next"
        }
        if (failure != null) {
            // Wake all waiters below; a duplicate cannot replace the original publication.
        } else if (next.startsWith("failed:")) {
            failure = next
        } else if (phases.indexOf(next) != phases.indexOf(currentPhase) + 1 || next !in receipts) {
            failure = "Invalid benchmark phase transition: $currentPhase -> $next"
        } else {
            if (publication != null) evidence[next] = publication
            currentPhase = next
            receipts.getValue(next).countDown()
            return true
        }
        currentPhase = checkNotNull(failure)
        receipts.values.forEach { it.countDown() }
        return true
    }

    @Synchronized fun publication(phase: String): String? = evidence[phase]

    fun await(expected: String, timeoutMs: Long): Boolean {
        require(timeoutMs >= 0)
        val received = receipts.getValue(expected).await(timeoutMs, TimeUnit.MILLISECONDS)
        check(failure == null) { checkNotNull(failure) }
        return received
    }
}
