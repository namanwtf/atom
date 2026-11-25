package atom.vdisplay.present

import android.view.Display
import atom.vdisplay.io.Commands
import atom.vdisplay.io.RootDisplayIo
import atom.vdisplay.io.TaskEntry
import atom.vdisplay.io.TaskList
import atom.vdisplay.route.DisplayLease
import atom.vdisplay.route.DisplayRouter
import atom.vdisplay.route.DisplayUnavailable
import kotlinx.coroutines.delay

/**
 * "Present to main screen": moves the hidden task (payment confirmation, QR code, final result) onto the real
 * screen with its state intact, then releases the hidden display. Tried in order, each verified with `am stack list`:
 *
 *  1. `am display move-stack <rootTask> 0` (shell command; present on many builds).
 *  2. Root helper → IActivityTaskManager.moveRootTaskToDisplay (works where the shell command was removed).
 *  3. `am start --display 0 --activity-reorder-to-front` with the task's top activity (the activity starter moves
 *     an existing task to the requested display).
 *  4. Relaunch of the app on the main screen; the page may reset, and the result says so.
 */
object Presenter {
    sealed interface Outcome {
        data class Moved(val how: String, val packageName: String) : Outcome
        data class Relaunched(val packageName: String) : Outcome
        data class Failed(val why: String) : Outcome
    }

    suspend fun present(lease: DisplayLease): Outcome {
        try {
            DisplayRouter.require(lease)
        } catch (e: DisplayUnavailable) {
            return Outcome.Failed(e.message ?: "display unavailable")
        }
        if (lease.main) return Outcome.Moved("already on the main screen", lease.targetPackage ?: "")
        val io = DisplayRouter.rootIo
        val task = TaskList.pick(io.tasks(), lease.displayId, lease.targetPackage)
            ?: return Outcome.Failed("no app is open on the background display")
        val outcome = tryMove(io, task) ?: relaunch(io, task)
        if (outcome !is Outcome.Failed) DisplayRouter.release()
        return outcome
    }

    private suspend fun tryMove(io: RootDisplayIo, task: TaskEntry): Outcome? {
        val main = Display.DEFAULT_DISPLAY
        val attempts: List<Pair<String, suspend () -> Unit>> = listOf(
            "move-stack" to { io.run(Commands.moveStack(task.rootTaskId, main)) },
            "task move" to { io.helper("move-task", task.rootTaskId.toString(), main.toString()) },
            "reorder to front" to { io.run(Commands.reorderToFront(main, task.component)) },
        )
        for ((how, attempt) in attempts) {
            attempt()
            if (onMain(io, task)) return Outcome.Moved(how, task.packageName)
        }
        return null
    }

    private suspend fun onMain(io: RootDisplayIo, task: TaskEntry): Boolean {
        repeat(3) {
            if (io.tasks().any { it.taskId == task.taskId && it.displayId == Display.DEFAULT_DISPLAY }) return true
            delay(150)
        }
        return false
    }

    private suspend fun relaunch(io: RootDisplayIo, task: TaskEntry): Outcome {
        val err = io.launch(Display.DEFAULT_DISPLAY, task.packageName)
        return if (err == null) Outcome.Relaunched(task.packageName) else Outcome.Failed(err)
    }
}
