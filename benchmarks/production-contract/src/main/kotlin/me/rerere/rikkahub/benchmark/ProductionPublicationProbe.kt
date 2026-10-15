package me.rerere.rikkahub.benchmark

/** Fixture-only scalar evidence; never a source of production layout, scrolling or observable state. */
internal class ProductionPublicationProbe {
    data class Work(
        val publications: Int,
        val panelCommits: Int,
        val pairingChecks: Int,
        val transcriptEntries: Int,
        val cachedEntries: Int,
        val viewBuilds: Int,
        val chunkWrappers: Int,
        val rowSlices: Int,
        val liveViews: Int,
        val sparseBlockBuilds: Int = 0,
        val sparseRowsCreated: Int = 0,
    )

    private var publications = 0
    private var panelCommits = 0
    private var pairingChecks = 0
    private var transcriptEntries = 0
    private var cachedEntries = 0
    private var viewBuilds = 0
    private var chunkWrappers = 0
    private var rowSlices = 0
    private var sparseBlockBuilds = 0
    private var sparseRowsCreated = 0
    var liveViews = 0
        private set

    fun published() { publications++ }
    fun panelCommitted() { panelCommits++ }

    fun transcriptComposed(cached: Boolean) {
        transcriptEntries++
        check(cached) { "V6 transcript bypassed published chunk provenance" }
        cachedEntries++
    }

    fun sparseBlockCreated(rows: Int) {
        require(rows in 1..128)
        sparseBlockBuilds++
        sparseRowsCreated = Math.addExact(sparseRowsCreated, rows)
    }

    fun viewsCreated(chunks: Int, slices: Int) {
        check(chunks > 0 && slices == chunks + 1) { "V6 transcript allocated uncached row slices" }
        viewBuilds++
        chunkWrappers = Math.addExact(chunkWrappers, chunks)
        rowSlices = Math.addExact(rowSlices, slices)
    }

    fun viewLifetime(delta: Int) {
        check(delta == -1 || delta == 1)
        val next = liveViews + delta
        check(next in 0..1) { "V6 chunk cache has overlapping or missing owners" }
        liveViews = next
    }

    /** Caller has checked exact source/frame/immutable row view identity before recording this. */
    fun paired(cachedExpected: Boolean) {
        check(publications > 0 && panelCommits > 0) { "V6 publication has not committed" }
        check(liveViews == if (cachedExpected) 1 else 0) { "V6 cache lifetime does not match renderer" }
        check(!cachedExpected || cachedEntries > 0) { "V6 eager publication was never consumed" }
        pairingChecks++
    }

    fun snapshot(): Work = Work(publications, panelCommits, pairingChecks, transcriptEntries,
        cachedEntries, viewBuilds, chunkWrappers, rowSlices, liveViews, sparseBlockBuilds, sparseRowsCreated)
}
