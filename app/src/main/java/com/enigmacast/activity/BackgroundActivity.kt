package com.enigmacast.activity

import com.enigmacast.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
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
    /** The first sync after the wizard; the window only ever shows while this runs. */
    val session: SetupSession? = null,
)

/**
 * The initial setup run that starts when the wizard finishes. The status window shows during it
 * and never otherwise (not for scheduled refreshes, sync-server syncs on zapping, or later starts).
 * It ends once every [expected] kind has finished and the work has settled, after a long idle
 * stretch when an expected task never comes, or at the latest after [ActivityRules.SESSION_TIMEOUT_MS].
 * Persisted, so a restart in the middle of setup keeps showing it until it is over.
 */
data class SetupSession(
    val startedMs: Long,
    val expected: Set<ActivityKind>,
    val finished: Set<ActivityKind> = emptySet(),
    /** When the last task ended inside this session; 0 before any did. */
    val lastEndMs: Long = 0L,
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
    /** Safety net: a setup session never lasts longer than this. */
    const val SESSION_TIMEOUT_MS = 10 * 60_000L
    /** All expected work done: the session ends once nothing new started for this long (Done has shown). */
    const val SETTLE_MS = 8_000L
    /** Expected work that never arrives (no film key, IPTV login gone...): end after this much quiet. */
    const val IDLE_END_MS = 60_000L

    fun begin(board: ActivityBoard, session: SetupSession): ActivityBoard =
        board.copy(session = session, showedSomething = false, failed = null)

    /** True when [session] is over at [nowMs]; [idle] means no task runs. */
    fun sessionOver(session: SetupSession, idle: Boolean, nowMs: Long): Boolean {
        if (nowMs - session.startedMs >= SESSION_TIMEOUT_MS) return true
        if (!idle || session.lastEndMs < session.startedMs || session.lastEndMs == 0L) return false
        val quiet = nowMs - session.lastEndMs
        return (session.finished.containsAll(session.expected) && quiet >= SETTLE_MS) || quiet >= IDLE_END_MS
    }

    /** [board] without its session when that is over; the same board otherwise. */
    fun expire(board: ActivityBoard, nowMs: Long): ActivityBoard {
        val session = board.session ?: return board
        return if (sessionOver(session, board.tasks.isEmpty(), nowMs)) board.copy(session = null) else board
    }

    fun start(board: ActivityBoard, task: ActivityTask): ActivityBoard {
        // A new run after the previous Done has gone starts fresh.
        val fresh = board.tasks.isEmpty() && task.startedMs - board.lastEndMs >= DONE_MS
        return board.copy(tasks = board.tasks + task, showedSomething = if (fresh) false else board.showedSomething)
    }

    fun progress(board: ActivityBoard, id: Long, done: Int?, total: Int?): ActivityBoard =
        board.copy(tasks = board.tasks.map { if (it.id == id) it.copy(done = done, total = total?.takeIf { t -> t > 0 }) else it })

    fun finish(board: ActivityBoard, id: Long, nowMs: Long, failed: Boolean): ActivityBoard {
        val task = board.tasks.firstOrNull { it.id == id } ?: return board
        // A task that followed straight on a shown one was on screen too (see [view]).
        val wasShown = task.visible(nowMs) || (board.showedSomething && task.startedMs - board.lastEndMs < DONE_MS)
        val session = board.session?.let { it.copy(finished = it.finished + task.kind, lastEndMs = nowMs) }
        return board.copy(
            session = session,
            tasks = board.tasks - task,
            showedSomething = board.showedSomething || wasShown,
            lastEndMs = nowMs,
            failed = if (failed && wasShown) task.kind else board.failed,
            failedAtMs = if (failed && wasShown) nowMs else board.failedAtMs,
        )
    }

    fun view(board: ActivityBoard, nowMs: Long): ActivityView? {
        // Only during the setup run after the wizard; tracking goes on, the window stays away.
        val session = board.session ?: return null
        if (sessionOver(session, board.tasks.isEmpty(), nowMs)) return null
        val failed = board.failed
        if (failed != null && nowMs - board.failedAtMs < FAILED_MS) return ActivityView.Failed(failed)
        // Straight after a task that was on screen the next one shows at once, so the window does
        // not blink out between channel lists, guide and Home.
        val continuing = board.showedSomething && nowMs - board.lastEndMs < DONE_MS
        val shown = board.tasks.filter { continuing || it.visible(nowMs) }
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
        if (board.showedSomething) add(board.lastEndMs + DONE_MS)
        board.session?.let { session ->
            add(session.startedMs + SESSION_TIMEOUT_MS)
            if (session.lastEndMs > 0) {
                add(session.lastEndMs + SETTLE_MS)
                add(session.lastEndMs + IDLE_END_MS)
            }
        }
    }.filter { it > nowMs }.minOrNull()
}

/** Where the setup session survives an app restart. */
interface SetupSessionStore {
    fun load(): SetupSession?
    fun save(session: SetupSession?)
}

/** SharedPreferences, read and written off the main thread by [BackgroundActivityTracker]. */
class PrefsSetupSessionStore(private val context: android.content.Context) : SetupSessionStore {
    private val prefs by lazy { context.getSharedPreferences("background_activity", android.content.Context.MODE_PRIVATE) }

    override fun load(): SetupSession? {
        val started = prefs.getLong(STARTED, 0L).takeIf { it > 0 } ?: return null
        fun kinds(key: String) = prefs.getString(key, "").orEmpty().split(',')
            .mapNotNull { name -> ActivityKind.entries.firstOrNull { it.name == name } }.toSet()
        return SetupSession(started, kinds(EXPECTED), kinds(FINISHED), prefs.getLong(LAST_END, 0L))
    }

    override fun save(session: SetupSession?) {
        prefs.edit().apply {
            if (session == null) {
                clear()
            } else {
                putLong(STARTED, session.startedMs)
                putString(EXPECTED, session.expected.joinToString(",") { it.name })
                putString(FINISHED, session.finished.joinToString(",") { it.name })
                putLong(LAST_END, session.lastEndMs)
            }
        }.apply()
    }

    private companion object {
        const val STARTED = "setup_session.started"
        const val EXPECTED = "setup_session.expected"
        const val FINISHED = "setup_session.finished"
        const val LAST_END = "setup_session.last_end"
    }
}

/**
 * The app-wide record of background work (bouquets, guide, Home data, IPTV, sync server).
 * Safe from any thread: every change is one atomic update of [board]. Use [track], which always
 * finishes the task, also on errors and cancellation. The window only shows it during the
 * [SetupSession] begun by [beginInitialSetup]; storage runs on [io] (null: inline, for tests).
 */
@Singleton
class BackgroundActivityTracker(
    private val clock: () -> Long,
    private val store: SetupSessionStore,
    io: kotlinx.coroutines.CoroutineDispatcher?,
) {
    @Inject constructor(@dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context) :
        this(System::currentTimeMillis, PrefsSetupSessionStore(context), kotlinx.coroutines.Dispatchers.IO)

    private val ids = AtomicLong()
    private val _board = MutableStateFlow(ActivityBoard())
    val board: StateFlow<ActivityBoard> = _board.asStateFlow()
    private val scope = io?.let { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + it.limitedParallelism(1)) }

    init {
        storage {
            val saved = store.load()
            if (saved != null) {
                _board.update { if (it.session == null) it.copy(session = saved) else it }
                refresh()
            }
        }
    }

    private fun storage(block: () -> Unit) {
        if (scope == null) block() else scope.launch { block() }
    }

    fun now(): Long = clock()

    /** The wizard finished: show the window until the first full sync ([expected] kinds) is done. */
    fun beginInitialSetup(expected: Set<ActivityKind>) {
        val session = SetupSession(clock(), expected)
        _board.update { ActivityRules.begin(it, session) }
        storage { store.save(session) }
    }

    /** Ends a session that is over; called by the window whenever its view can change. */
    fun refresh() {
        val before = _board.value.session ?: return
        val after = _board.updateAndGet { ActivityRules.expire(it, clock()) }
        if (after.session == null) storage { store.save(null) }
    }

    fun start(kind: ActivityKind): Long {
        val id = ids.incrementAndGet()
        _board.update { ActivityRules.start(it, ActivityTask(id, kind, clock())) }
        return id
    }

    fun progress(id: Long, done: Int?, total: Int?) {
        _board.update { ActivityRules.progress(it, id, done, total) }
    }

    fun finish(id: Long, failed: Boolean = false) {
        val before = _board.value.session
        val after = _board.updateAndGet { ActivityRules.finish(it, id, clock(), failed) }.session
        if (after != null && after != before) storage { store.save(after) }
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
