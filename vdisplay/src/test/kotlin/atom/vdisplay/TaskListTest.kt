package atom.vdisplay

import atom.vdisplay.io.TaskEntry
import atom.vdisplay.io.TaskList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TaskListTest {
    private val modern = """
        RootTask id=1 bounds=[0,0][1080,2400] displayId=0 userId=0
         configuration={1.0 ?mcc?mnc}
          taskId=1: com.android.launcher3/.Launcher bounds=[0,0][1080,2400] userId=0 visible=true topActivity=ComponentInfo{com.android.launcher3/com.android.launcher3.Launcher}
        RootTask id=23 bounds=[0,0][720,1600] displayId=9 userId=0
          taskId=23: com.shop/com.shop.PayActivity bounds=[0,0][720,1600] userId=0 visible=true topActivity=ComponentInfo{com.shop/com.shop.PayActivity}
        RootTask id=24 bounds=[0,0][720,1600] displayId=9 userId=0
          taskId=24: com.chat/.Main bounds=[0,0][720,1600] userId=0 visible=false
    """.trimIndent()

    private val legacy = """
        Stack id=4 bounds=[0,0][1080,2340] displayId=2 userId=0
          taskId=8: com.shop/.Main bounds=[0,0][1080,2340] userId=0 visible=true
    """.trimIndent()

    @Test fun parsesModernFormat() {
        val tasks = TaskList.parse(modern)
        assertEquals(3, tasks.size)
        assertEquals(TaskEntry(23, 23, 9, "com.shop/com.shop.PayActivity", true), tasks[1])
        assertEquals(false, tasks[2].visible)
        assertEquals("com.chat", tasks[2].packageName)
    }

    @Test fun parsesLegacyStackFormat() =
        assertEquals(listOf(TaskEntry(4, 8, 2, "com.shop/.Main", true)), TaskList.parse(legacy))

    @Test fun picksTargetElseTop() {
        val tasks = TaskList.parse(modern)
        assertEquals(24, TaskList.pick(tasks, 9, "com.chat")?.taskId)
        assertEquals(23, TaskList.pick(tasks, 9, "com.other")?.taskId)
        assertNull(TaskList.pick(tasks, 4, null))
    }

    @Test fun garbageIsIgnored() = assertEquals(emptyList(), TaskList.parse("Error: unknown command\n\n"))

    @Test fun resumedPerDisplay() {
        val dump = """
            Display #0 (activities from top to bottom):
              ResumedActivity:ActivityRecord{fc1d6ba u0 com.chat/.Main t2}
            Display #9 (activities from top to bottom):
              ResumedActivity:ActivityRecord{aa11 u0 com.shop/.PayActivity t23}
              topResumedActivity=ActivityRecord{aa11 u0 com.shop/.PayActivity t23}
        """.trimIndent()
        assertEquals("com.chat", TaskList.resumedPackage(dump, 0))
        assertEquals("com.shop", TaskList.resumedPackage(dump, 9))
        assertNull(TaskList.resumedPackage(dump, 3))
        assertEquals("com.old", TaskList.resumedPackage("Display #0\n    mResumedActivity: ActivityRecord{1 u0 com.old/.A t5}", 0))
    }
}
