package com.enigmacast.activity

import com.enigmacast.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What EnigmaCast can be busy with in the background. [label] names it in the status window;
 * a task only shows after [visibleAfterMs], so quick ones never flash on screen.
 */
enum class ActivityKind(val label: Int, val visibleAfterMs: Long = 1_200) {
    CHANNELS(R.string.activity_channels),
    GUIDE(R.string.activity_guide),
    HOME(R.string.activity_home),
    IPTV(R.string.activity_iptv),
    /** Profiles, favourites and resume positions; routine syncs are short, so only slow ones show. */
    SYNC_SERVER(R.string.activity_sync_server, visibleAfterMs = 4_000),
}

data class ActivityTask(
    val id: Long,
    val kind: ActivityKind,
    val startedMs: Long,
    val done: Int? = null,
    /** Null when the total is unknown: the window shows a moving bar instead of a filled one. */
    val total: Int? = null,
) {
    fun visible(nowMs: Long) = nowMs - startedMs >= kind.visibleAfterMs
}

/** Everything the status window needs; changed only through [ActivityRules]. */
data class ActivityBoard(
    val tasks: List<ActivityTask> = emptyList(),
    /** A task that was on screen has ended since the last idle period: Done may show. */
    val showedSomething: Boolean = false,
    val lastEndMs: Long = 0L,
    val failed: ActivityKind? = null,
    val failedAtMs: Long = 0L,
)

/** What the window shows right now. */
sealed interface ActivityView {
    /** [waiting] other tasks run as well. [total] null means unknown. */
    data class Running(val kind: ActivityKind, val done: Int?, val total: Int?, val waiting: Int) : ActivityView {
        val fraction: Float? get() = total?.takeIf { it > 0 }?.let { ((done ?: 0).toFloat() / it).coerceIn(0f, 1f) }
    }
    data object Done : ActivityView
    data class Failed(val kind: ActivityKind) : ActivityView
}

/** Pure rules for the status window, so they can be tested without Android. */
object ActivityRules {
    const val DONE_MS = 2_000L
    const val FAILED_MS = 5_000L

    fun start(board: ActivityBoard, task: ActivityTask): ActivityBoard {
        // A new run after the previous Done has gone starts fresh.
        val fresh = board.tasks.isEmpty() && task.startedMs - board.lastEndMs >= DONE_MS
        return board.copy(tasks = board.tasks + task, showedSomething = if (fresh) false else board.showedSomething)
    }

    fun progress(board: ActivityBoard, id: Long, done: Int?, total: Int?): ActivityBoard =
        board.copy(tasks = board.tasks.map { if (it.id == id) it.copy(done = done, total = total?.takeIf { t -> t > 0 }) else it })

    fun finish(board: ActivityBoard, id: Long, nowMs: Long, failed: Boolean): ActivityBoard {
        val task = board.tasks.firstOrNull { it.id == id } ?: return board
        val wasShown = task.visible(nowMs)
        return board.copy(
            tasks = board.tasks - task,
            showedSomething = board.showedSomething || wasShown,
            lastEndMs = nowMs,
            failed = if (failed && wasShown) task.kind else board.failed,
            failedAtMs = if (failed && wasShown) nowMs else board.failedAtMs,
        )
    }

    fun view(board: ActivityBoard, nowMs: Long): ActivityView? {
        val failed = board.failed
        if (failed != null && nowMs - board.failedAtMs < FAILED_MS) return ActivityView.Failed(failed)
        val shown = board.tasks.filter { it.visible(nowMs) }
        if (shown.isNotEmpty()) {
            val current = shown.minBy { it.startedMs }
            return ActivityView.Running(current.kind, current.done, current.total, waiting = board.tasks.size - 1)
        }
        if (board.tasks.isEmpty() && board.showedSomething && nowMs - board.lastEndMs < DONE_MS) return ActivityView.Done
        return null
    }

    /** The next moment [view] can change without a new report; null when nothing is pending. */
    fun nextChangeMs(board: ActivityBoard, nowMs: Long): Long? = buildList {
        board.tasks.forEach { if (!it.visible(nowMs)) add(it.startedMs + it.kind.visibleAfterMs) }
        if (board.failed != null) add(board.failedAtMs + FAILED_MS)
        if (board.tasks.isEmpty() && board.showedSomething) add(board.lastEndMs + DONE_MS)
    }.filter { it > nowMs }.minOrNull()
}

/**
 * The app-wide record of background work (bouquets, guide, Home data, IPTV, sync server).
 * Safe from any thread: every change is one atomic update of [board]. Use [track], which always
 * finishes the task, also on errors and cancellation.
 */
@Singleton
class BackgroundActivityTracker(private val clock: () -> Long) {
    @Inject constructor() : this(System::currentTimeMillis)

    private val ids = AtomicLong()
    private val _board = MutableStateFlow(ActivityBoard())
    val board: StateFlow<ActivityBoard> = _board.asStateFlow()

    fun now(): Long = clock()

    fun start(kind: ActivityKind): Long {
        val id = ids.incrementAndGet()
        _board.update { ActivityRules.start(it, ActivityTask(id, kind, clock())) }
        return id
    }

    fun progress(id: Long, done: Int?, total: Int?) {
        _board.update { ActivityRules.progress(it, id, done, total) }
    }

    fun finish(id: Long, failed: Boolean = false) {
        _board.update { ActivityRules.finish(it, id, clock(), failed) }
    }

    /** Runs [block] as one task; an exception marks it failed and is rethrown, cancellation is quiet. */
    suspend fun <T> track(kind: ActivityKind, block: suspend (Reporter) -> T): T {
        val id = start(kind)
        var failed = true
        try {
            val result = block(Reporter(this, id))
            failed = false
            return result
        } catch (cancel: CancellationException) {
            failed = false
            throw cancel
        } finally {
            finish(id, failed)
        }
    }

    class Reporter internal constructor(private val tracker: BackgroundActivityTracker, private val id: Long) {
        fun progress(done: Int?, total: Int?) = tracker.progress(id, done, total)
    }
}
