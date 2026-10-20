package cc.sche.apps.nexttask.smoketest

import android.graphics.Point
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

/**
 * Signs the installed app in to the fake Nextcloud (tools/fake-nextcloud), then makes the edits most worth
 * protecting and checks that each one reaches the server: this catches what the JVM tests cannot, such as R8
 * removing something the release build needs. Expects the server's starting tasks and a freshly installed app.
 */
@RunWith(AndroidJUnit4::class)
class ReleaseSmokeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)

    @Before
    fun startSignedOut() {
        device.executeShellCommand("pm clear $APP")
    }

    @Test
    fun signsInSyncsAndUploadsEdits() {
        launchApp()
        find(By.text("http://")).click()
        find(By.clazz("android.widget.EditText")).text = SERVER_ADDRESS
        find(By.text("Sign in with browser")).click()
        awaitBrowser()
        // The fake server's login page approves the login as soon as anyone opens it, like a click on "Grant access".
        server("login/v2/flow/abc")
        launchApp()

        find(By.text("Groceries"), timeoutMs = SYNC_TIMEOUT_MS)
        listOf("Call mom", "Bread", "Milk", "Laundry", "Taxes", "Forms").forEach { find(By.text(it)) }

        find(By.desc("Add task")).click()
        find(By.clazz("android.widget.EditText")).text = NEW_TASK
        find(By.text("Add")).click()
        awaitServer("the new task") { task(it, NEW_TASK) != null }

        checkboxOf(find(By.text(NEW_TASK))).click()
        awaitServer("the new task, ticked off") { task(it, NEW_TASK)?.getString("status") == "COMPLETED" }

        // Long-press Laundry and drop it between Bread and Milk: it becomes a subtask of Groceries.
        val laundry = find(By.text("Laundry")).visibleCenter
        val milk = find(By.text("Milk")).visibleBounds
        longPressAndDrag(laundry, Point(laundry.x, milk.top))
        awaitServer("Laundry moved into Groceries") { task(it, "Laundry")?.optString("parent") == "groceries" }
    }

    /** Opens the app, or brings it back to the front. Started by the shell: this test's process may not start activities from the background. */
    private fun launchApp() {
        val intent = instrumentation.context.packageManager.getLaunchIntentForPackage(APP) ?: error("$APP is not installed")
        device.executeShellCommand("am start -W -n ${intent.component!!.flattenToShortString()}")
        assertTrue("$APP did not open", device.wait(Until.hasObject(By.pkg(APP).depth(0)), UI_TIMEOUT_MS))
    }

    /**
     * Waits for the app to hand the login over to the browser, which then covers the app. A device without a
     * browser gets a notice in the app instead.
     */
    private fun awaitBrowser() {
        val deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (!device.hasObject(By.pkg(APP).depth(0)) || device.hasObject(By.textStartsWith("No browser found"))) return
            SystemClock.sleep(POLL_MS)
        }
        fail("The app neither opened a browser nor said there is none")
    }

    private fun find(selector: BySelector, timeoutMs: Long = UI_TIMEOUT_MS): UiObject2 =
        device.wait(Until.findObject(selector), timeoutMs) ?: throw AssertionError("Not on screen: $selector")

    /** The checkbox on the same row as [label]. */
    private fun checkboxOf(label: UiObject2): UiObject2 {
        val row = label.visibleBounds
        return device.findObjects(By.checkable(true)).firstOrNull { it.visibleCenter.y in row.top..row.bottom }
            ?: throw AssertionError("No checkbox next to ${label.text}")
    }

    /** Holds a finger down at [from] until it counts as a long press, moves it to [to] and lifts it. */
    private fun longPressAndDrag(from: Point, to: Point) {
        val downTime = SystemClock.uptimeMillis()
        fun inject(action: Int, at: Point) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, at.x.toFloat(), at.y.toFloat(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            check(instrumentation.uiAutomation.injectInputEvent(event, true)) { "Could not inject $event" }
            event.recycle()
        }
        inject(MotionEvent.ACTION_DOWN, from)
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + LONG_PRESS_MARGIN_MS)
        for (step in 1..DRAG_STEPS) {
            inject(MotionEvent.ACTION_MOVE, Point(from.x + (to.x - from.x) * step / DRAG_STEPS, from.y + (to.y - from.y) * step / DRAG_STEPS))
            SystemClock.sleep(DRAG_STEP_MS)
        }
        SystemClock.sleep(SETTLE_MS)
        inject(MotionEvent.ACTION_UP, to)
    }

    /** Waits until the fake server's tasks satisfy [condition]; [what] says what is missing if they never do. */
    private fun awaitServer(what: String, condition: (JSONObject) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + SYNC_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition(JSONObject(server("admin/state")))) return
            SystemClock.sleep(POLL_MS)
        }
        fail("Never reached the server: $what. Server has: ${server("admin/state")}")
    }

    /** The task named [summary] in the server's "Tasks" list, as `/admin/state` reports it. */
    private fun task(state: JSONObject, summary: String): JSONObject? {
        val tasks = state.getJSONArray("tasks")
        return (0 until tasks.length()).map(tasks::getJSONObject).firstOrNull { it.optString("summary") == summary }
    }

    private fun server(path: String): String {
        val connection = URL("http://$SERVER_ADDRESS/$path").openConnection() as HttpURLConnection
        try {
            check(connection.responseCode == HttpURLConnection.HTTP_OK) { "GET /$path: HTTP ${connection.responseCode}" }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val APP = "cc.sche.apps.nexttask"
        const val SERVER_ADDRESS = "localhost:8099"
        const val NEW_TASK = "Smoke test"
        const val UI_TIMEOUT_MS = 10_000L
        const val SYNC_TIMEOUT_MS = 30_000L
        const val POLL_MS = 250L
        const val LONG_PRESS_MARGIN_MS = 300L
        const val DRAG_STEPS = 10
        const val DRAG_STEP_MS = 30L
        const val SETTLE_MS = 300L
    }
}
