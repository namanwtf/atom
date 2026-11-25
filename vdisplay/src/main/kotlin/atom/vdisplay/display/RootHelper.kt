package atom.vdisplay.display

import android.os.IBinder

/**
 * Tiny root helper run from Atom's own APK with `app_process` (see [atom.vdisplay.io.Commands.helper]): a one-shot
 * process that makes the two system calls no shell command offers on every Android version, then exits. Prints
 * "ok" or "error: ..." and exits 0 / 1. Never runs inside the app.
 *
 *  - `ime-policy <displayId> <policy>`: IWindowManager.setDisplayImePolicy (Android 12+; 2 = hide), or
 *    setShouldShowIme(displayId, false) on Android 10-11.
 *  - `move-task <taskId> <displayId>`: IActivityTaskManager.moveRootTaskToDisplay (12+) / moveStackToDisplay.
 */
object RootHelper {
    @JvmStatic
    fun main(args: Array<String>) {
        val code = try {
            when (args.getOrNull(0)) {
                "ime-policy" -> imePolicy(args[1].toInt(), args[2].toInt())
                "move-task" -> moveTask(args[1].toInt(), args[2].toInt())
                else -> throw IllegalArgumentException("unknown command ${args.getOrNull(0)}")
            }
            println("ok")
            0
        } catch (t: Throwable) {
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            println("error: ${cause.javaClass.simpleName}: ${cause.message}")
            1
        }
        System.exit(code)
    }

    private fun service(name: String, stub: String): Any {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, name) as IBinder
        return Class.forName(stub).getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
    }

    private fun imePolicy(displayId: Int, policy: Int) {
        val wm = service("window", "android.view.IWindowManager\$Stub")
        val set = wm.javaClass.methods.firstOrNull { it.name == "setDisplayImePolicy" && it.parameterCount == 2 }
        if (set != null) {
            set.invoke(wm, displayId, policy)
            return
        }
        wm.javaClass.getMethod("setShouldShowIme", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).invoke(wm, displayId, false)
    }

    private fun moveTask(taskId: Int, displayId: Int) {
        val atm = service("activity_task", "android.app.IActivityTaskManager\$Stub")
        val move = atm.javaClass.methods.firstOrNull { (it.name == "moveRootTaskToDisplay" || it.name == "moveStackToDisplay") && it.parameterCount == 2 }
            ?: throw NoSuchMethodException("moveRootTaskToDisplay")
        move.invoke(atm, taskId, displayId)
    }
}
