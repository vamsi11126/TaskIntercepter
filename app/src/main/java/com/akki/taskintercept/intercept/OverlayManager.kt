package com.akki.taskintercept.intercept

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.os.CountDownTimer
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.akki.taskintercept.R
import com.akki.taskintercept.data.Priority
import com.akki.taskintercept.data.Task

/**
 * Adds/removes the task-brief overlay via WindowManager.
 *
 * The view and layout params are created once (lazily, on first show) and
 * reused across show/hide cycles — inflation cost is paid before the race
 * against the target app's first frame, not during it.
 *
 * [onReturnToTask] is invoked at the moment "Return to Task" is tapped,
 * before the home intent fires. The service uses it to reset its
 * foreground-package tracking immediately: logcat confirmed Android does
 * NOT reliably deliver a window-state event for this programmatic
 * home navigation, so waiting for one to update the tracker leaves it
 * stale. "Continue Anyway" deliberately does not invoke this — the user
 * stays in the target app, so the tracked package remains correct as-is.
 */
class OverlayManager(
    private val context: Context,
    private val onReturnToTask: (() -> Unit) = {}
) {

    companion object {
        private const val TAG = "TaskIntercept"

        /** Max task rows the overlay layout has slots for. */
        const val MAX_OVERLAY_TASKS = 3

        /** Dot tint per priority — matches MainActivity's priorityColor(). */
        private fun priorityTint(priority: Priority): Int = when (priority) {
            Priority.HIGH -> 0xFFE05B4B.toInt()
            Priority.MEDIUM -> 0xFFE0A84B.toInt()
            Priority.LOW -> 0xFF6B9E6E.toInt()
        }
    }

    private val windowManager: WindowManager by lazy {
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    // Delegate held separately so hideOverlay can check isInitialized():
    // cleanup paths (service onDestroy, screen-off) must not inflate a
    // view that was never shown just to conclude there's nothing to hide.
    private val overlayViewDelegate = lazy {
        LayoutInflater.from(context).inflate(R.layout.overlay_task_brief, null).also { view ->
            // Listeners set once at inflation — the view is reused across
            // show/hide cycles, so wiring here (not in showOverlay) avoids
            // re-registering on every show.
            view.findViewById<Button>(R.id.overlay_return_button).setOnClickListener {
                hideOverlay()
                // Navigation and tracker reset are both handled in the
                // onReturnToTask callback provided by the service:
                // it tries performGlobalAction(GLOBAL_ACTION_HOME) first
                // (more reliable during window animations), then falls
                // back to an explicit home Intent. It also stamps the
                // settle state with a compressed window so a rapid
                // intentional reopen re-triggers correctly.
                onReturnToTask()
            }
            view.findViewById<Button>(R.id.overlay_continue_button).setOnClickListener {
                // Removing the overlay reveals the target app underneath —
                // it was never blocked, just visually covered.
                hideOverlay()
            }
        }
    }

    private val overlayView: View by overlayViewDelegate

    /**
     * Pre-inflates the view (and resolves the row references) ahead of the
     * first show, so the first trigger's event→addView latency doesn't
     * include inflation. Call from the main thread at a quiet moment
     * (service connect). No-op if already inflated.
     */
    fun warmUp() {
        overlayView
        taskRows
    }

    private val layoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    )

    private var isShowing = false

    /**
     * Read-only visibility check for the service: lets it dismiss the
     * overlay on foreground changes / screen-off without reaching into
     * internal state.
     */
    val isOverlayShowing: Boolean
        get() = isShowing

    /**
     * Pacing cue only: counts 10 → 0 and stops. Nothing is forced at zero —
     * no auto-continue, no auto-return. Both buttons stay live throughout
     * and after; the user always makes the actual choice. Cancelled (and
     * recreated fresh) on every hide/show so a stale tick from a previous
     * overlay can't touch the current one.
     */
    private var countdownTimer: CountDownTimer? = null

    /**
     * The three fixed task-row slots, in display order (largest first).
     * Resolved lazily off overlayView so inflation still happens exactly
     * once, on first show.
     */
    private data class TaskRow(val container: LinearLayout, val dot: ImageView, val text: TextView)

    private val taskRows: List<TaskRow> by lazy {
        listOf(
            TaskRow(
                overlayView.findViewById(R.id.overlay_task_row_1),
                overlayView.findViewById(R.id.overlay_task_dot_1),
                overlayView.findViewById(R.id.overlay_task_text_1)
            ),
            TaskRow(
                overlayView.findViewById(R.id.overlay_task_row_2),
                overlayView.findViewById(R.id.overlay_task_dot_2),
                overlayView.findViewById(R.id.overlay_task_text_2)
            ),
            TaskRow(
                overlayView.findViewById(R.id.overlay_task_row_3),
                overlayView.findViewById(R.id.overlay_task_dot_3),
                overlayView.findViewById(R.id.overlay_task_text_3)
            )
        )
    }

    /**
     * Shows the overlay with the top active tasks (highest priority first,
     * as ordered by the DAO query — this method preserves the given order).
     * An empty list shows the no-task fallback. Rows are bound on every
     * call, not at inflation — the view is reused across show/hide cycles
     * and the task set can change between opens.
     *
     * [countdownSeconds] is the user-configured brief-screen length (Phase
     * 4) — read fresh from DataStore by the caller per show, not cached
     * here, so a settings change applies to the very next overlay.
     */
    fun showOverlay(tasks: List<Task>, countdownSeconds: Int) {
        // Already showing — don't stack a second view (the accessibility
        // event double-fires per app open; real de-dup comes later).
        if (isShowing) return

        // Belt-and-braces against isShowing drifting out of sync with the
        // actual window state (e.g. a previous addView threw after we'd
        // marked ourselves hidden, or the OS tore the window down without
        // us): if the view is somehow still attached, adding it again would
        // throw IllegalStateException. Treat "attached" as already showing.
        if (overlayView.isAttachedToWindow) {
            Log.w(TAG, "Overlay view already attached with isShowing=false — resyncing")
            isShowing = true
            return
        }

        val contextLabel = overlayView.findViewById<TextView>(R.id.overlay_context_label)
        if (tasks.isEmpty()) {
            // Empty state: collapse rows 2/3, repurpose row 1's text as the
            // fallback line (dot hidden — there's no priority to indicate).
            contextLabel.text = "Before you scroll…"
            taskRows.forEachIndexed { index, row ->
                row.container.visibility = if (index == 0) View.VISIBLE else View.GONE
            }
            taskRows[0].dot.visibility = View.GONE
            taskRows[0].text.text = "No task set — open TaskIntercept to add one."
        } else {
            contextLabel.text = if (tasks.size == 1) "You planned to:" else "Your plan:"
            taskRows.forEachIndexed { index, row ->
                val task = tasks.getOrNull(index)
                if (task != null) {
                    row.container.visibility = View.VISIBLE
                    row.dot.visibility = View.VISIBLE
                    row.dot.imageTintList = ColorStateList.valueOf(priorityTint(task.priority))
                    row.text.text = task.title
                } else {
                    row.container.visibility = View.GONE
                }
            }
        }

        // addView can throw at runtime even when everything was fine at
        // trigger time: SecurityException / WindowManager.BadTokenException
        // if the user revoked SYSTEM_ALERT_WINDOW while the service was
        // running. An uncaught throw here would crash the whole
        // accessibility service process — losing interception entirely
        // until the user notices — so a missed single overlay is strictly
        // the better failure mode.
        try {
            windowManager.addView(overlayView, layoutParams)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay view (permission revoked?)", e)
            return
        }
        isShowing = true
        startCountdown(countdownSeconds)
    }

    fun hideOverlay() {
        // Never inflated → never shown → nothing to hide. Checked before
        // isShowing so cleanup callers can't trigger a pointless inflation
        // via the lazy delegate.
        if (!overlayViewDelegate.isInitialized()) return
        if (!isShowing) return
        // Cancel the timer FIRST and clear isShowing even if removeView
        // throws — a stale timer ticking against a detached view, or a
        // stuck isShowing=true silently suppressing every future overlay,
        // are both worse than the (already-detached) view staying gone.
        countdownTimer?.cancel()
        countdownTimer = null
        isShowing = false
        try {
            windowManager.removeView(overlayView)
        } catch (e: IllegalArgumentException) {
            // View wasn't attached — the OS already tore the window down
            // (e.g. display/keyguard transitions). Nothing left to remove.
            Log.w(TAG, "Overlay view already detached on hide", e)
        }
    }

    private fun startCountdown(countdownSeconds: Int) {
        val countdownText = overlayView.findViewById<TextView>(R.id.overlay_countdown)
        countdownTimer?.cancel()
        countdownTimer = object : CountDownTimer(countdownSeconds * 1000L, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                // Round up so the display starts at the full duration and
                // shows each second fully rather than skipping straight down.
                countdownText.text = ((millisUntilFinished + 999) / 1000).toString()
            }

            override fun onFinish() {
                // Deliberately no action: the countdown is a pacing cue,
                // not a timed decision. Just settle the display at 0.
                countdownText.text = "0"
            }
        }.also {
            countdownText.text = countdownSeconds.toString()
            it.start()
        }
    }
}
