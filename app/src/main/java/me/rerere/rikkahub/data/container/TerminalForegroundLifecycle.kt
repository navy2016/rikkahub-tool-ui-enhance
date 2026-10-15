package me.rerere.rikkahub.data.container

/** Main-thread state machine. No Context, Service, callback or optimistic foreground acknowledgement. */
internal class TerminalForegroundLifecycle {
    data class Request(val id: Long)
    private var serial = 0L
    private var instanceSerial = 0L
    private var instance: Long? = null
    private var pending: Request? = null
    private var confirmedCount: Int? = null
    private var promotions = 0L
    private var destructions = 0L
    var desiredCount: Int = 0
        private set

    fun request(count: Int): Request? {
        require(count >= 0)
        desiredCount = count
        // A stop during start must wait for onStartCommand to discharge startForegroundService.
        if (pending != null || confirmedCount == count || (count == 0 && instance == null)) return null
        return Request(++serial).also { pending = it }
    }

    fun failed(request: Request) {
        if (pending != request) return
        pending = null
        confirmedCount = null
    }

    fun created(): Long = (++instanceSerial).also {
        instance = it
        confirmedCount = null
    }

    /** Called only AFTER the platform has accepted startForeground for this instance. */
    fun promoted(token: Long, requestId: Long): Int? {
        if (instance != token) return null
        if (pending != null && requestId != pending?.id) return null
        pending = null
        promotions++
        confirmedCount = desiredCount.takeIf { it > 0 }
        return desiredCount
    }

    fun destroyed(token: Long) {
        if (instance != token) return
        instance = null
        confirmedCount = null
        destructions++
        // A newer start request can already be in Android's queue; do not discard it here.
    }

    val startPending: Boolean get() = pending != null
    val acknowledgedCount: Int? get() = confirmedCount
    data class Diagnostic(val requests: Long, val promotions: Long, val destructions: Long,
                          val pending: Boolean, val live: Boolean, val desired: Int, val acknowledged: Int?)
    fun diagnostic() = Diagnostic(serial, promotions, destructions, pending != null, instance != null,
        desiredCount, confirmedCount)
}
