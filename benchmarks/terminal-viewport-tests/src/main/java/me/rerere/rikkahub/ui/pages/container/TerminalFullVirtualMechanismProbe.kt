package me.rerere.rikkahub.ui.pages.container

import java.lang.ref.WeakReference

/** Test-APK-only, non-observable counters. No time measurements or production state writes. */
internal object TerminalFullVirtualMechanismProbe {
    var active = false
        private set
    private var definition: WeakReference<Any>? = null
    private var screen: WeakReference<Any>? = null
    private var frame: WeakReference<Any>? = null
    private var definitions = 0
    private var definitionChanges = 0
    private var screenChanges = 0
    private var frameChanges = 0
    private var intervalBuilds = 0
    private var itemAttempts = 0
    private val items = LinkedHashMap<Long, Int>()
    private val rows = LinkedHashMap<Long, Int>()
    var boundedItemsUsed = false
        private set
    fun branchCommitted(value: Boolean) { if (active) boundedItemsUsed = value }
    data class Counts(val definitions: Int, val definitionChanges: Int, val screenChanges: Int,
        val frameChanges: Int, val intervalBuilds: Int, val itemAttempts: Int,
        val items: Map<Long, Int>, val rows: Map<Long, Int>)

    fun start() { stop(); active = true }
    fun beginWindow() {
        definitions=0;definitionChanges=0;screenChanges=0;frameChanges=0;intervalBuilds=0;itemAttempts=0
        items.clear();rows.clear()
    }
    fun definitionCommitted(content: Any, currentFrame: Any, currentScreen: Any) {
        if (!active) return
        definitions++
        if (definition?.get() !== content) definitionChanges++
        if (screen?.get() !== currentScreen) screenChanges++
        if (frame?.get() !== currentFrame) frameChanges++
        definition=WeakReference(content);screen=WeakReference(currentScreen);frame=WeakReference(currentFrame)
    }
    fun intervalBuilt() { if (active) intervalBuilds++ }
    fun itemEntered() { if (active) itemAttempts++ }
    private fun increment(target: MutableMap<Long, Int>, id: Long) {
        check(target.size < 256 || id in target) { "Mechanism probe row inventory overflow" }
        target[id]=(target[id] ?: 0)+1
    }
    fun itemCommitted(id: Long) { if (active) increment(items,id) }
    fun rowCommitted(id: Long) { if (active) increment(rows,id) }
    fun snapshot() = Counts(definitions,definitionChanges,screenChanges,frameChanges,intervalBuilds,itemAttempts,
        items.toMap(),rows.toMap())
    fun stop() { active=false;boundedItemsUsed=false;definition=null;screen=null;frame=null;beginWindow() }
}
