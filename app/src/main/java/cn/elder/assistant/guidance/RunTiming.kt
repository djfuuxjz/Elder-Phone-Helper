package cn.elder.assistant.guidance

/** Preparation and page settling must not consume the measured task budget. */
class RunTiming {
    var waiting = false; private set
    private var firstObservation: Long? = null
    fun reset(waitForStart: Boolean) { waiting = waitForStart; firstObservation = null }
    fun release() { waiting = false }
    fun observe(now: Long): Boolean {
        if (waiting) return false
        if (firstObservation == null) firstObservation = now
        return true
    }
    fun elapsed(now: Long): Long? = firstObservation?.let { (now - it).coerceAtLeast(0) }
}
