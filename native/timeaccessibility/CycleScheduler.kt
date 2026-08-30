package __PACKAGE__.timeaccessibility

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

class CycleScheduler(
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) {
    private var scheduledTask: Runnable? = null

    fun scheduleAt(dueElapsed: Long, action: () -> Unit) {
        cancel()
        val task = object : Runnable {
            override fun run() {
                val remaining = (dueElapsed - elapsedRealtime()).coerceAtLeast(0L)
                if (remaining > 0L) {
                    handler.postDelayed(this, remaining)
                    return
                }
                scheduledTask = null
                action()
            }
        }
        scheduledTask = task
        handler.post(task)
    }

    fun cancel() {
        scheduledTask?.let(handler::removeCallbacks)
        scheduledTask = null
    }

    fun hasScheduledTask(): Boolean = scheduledTask != null
}
