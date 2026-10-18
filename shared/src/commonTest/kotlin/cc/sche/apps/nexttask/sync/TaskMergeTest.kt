package cc.sche.apps.nexttask.sync

import cc.sche.apps.nexttask.model.Task
import cc.sche.apps.nexttask.model.TaskStatus
import cc.sche.apps.nexttask.model.withLastModified
import kotlin.test.Test
import kotlin.test.assertEquals

class TaskMergeTest {

    private val base = Task(uid = "u", summary = "Base", description = "d", priority = 1, sortOrder = 5)
    private fun done(task: Task) = task.copy(status = TaskStatus.Completed, percentComplete = 100, completed = "20261018T100000Z")

    @Test
    fun keepsLocalCompletionAndRemoteSummary() {
        val merged = mergeTask(base, local = done(base), remote = base.copy(summary = "Renamed"))

        assertEquals("Renamed", merged.summary)
        assertEquals(TaskStatus.Completed, merged.status)
        assertEquals("20261018T100000Z", merged.completed)
    }

    @Test
    fun keepsRemoteChangesToEveryFieldTheUserLeftAlone() {
        val remote = base.copy(description = "new", priority = 9, sortOrder = 1, parentUid = "p")
        assertEquals(remote, mergeTask(base, local = base, remote = remote))
    }

    @Test
    fun localWinsWhenBothChangedTheSameField() {
        val merged = mergeTask(base, local = base.copy(summary = "Mine"), remote = base.copy(summary = "Theirs"))
        assertEquals("Mine", merged.summary)
    }

    @Test
    fun completionFieldsStayTogether() {
        // The server reopened the task and set a percentage; the user completed it. All three come from the user.
        val remote = base.copy(status = TaskStatus.InProcess, percentComplete = 40)
        val merged = mergeTask(base, local = done(base), remote = remote)

        assertEquals(TaskStatus.Completed, merged.status)
        assertEquals(100, merged.percentComplete)
        assertEquals("20261018T100000Z", merged.completed)
    }

    @Test
    fun unmodelledPropertiesComeFromTheServer() {
        val withStart = base.copy(unmapped = listOf(cc.sche.apps.nexttask.ical.Property("DTSTART", "20261020T120000Z")))
        val merged = mergeTask(base, local = done(base), remote = withStart)
        assertEquals(withStart.unmapped, merged.unmapped.filterNot { it.name == "LAST-MODIFIED" })
    }

    @Test
    fun aDueDateSetOfflineIsKept() {
        val due = cc.sche.apps.nexttask.ical.ICalDate.ofDate(2026, 10, 20)
        val merged = mergeTask(base, local = base.copy(due = due), remote = base.copy(summary = "Renamed"))
        assertEquals(due, merged.due)
        assertEquals("Renamed", merged.summary)
    }

    @Test
    fun aDueDateChangedOnTheServerIsKept() {
        val due = cc.sche.apps.nexttask.ical.ICalDate.ofUtc("20261020T100000Z")
        val merged = mergeTask(base, local = done(base), remote = base.copy(due = due))
        assertEquals(due, merged.due)
    }

    @Test
    fun resultIsStampedWithWhenTheUserEdited() {
        val local = done(base).withLastModified("20261018T101500Z")
        val merged = mergeTask(base, local, remote = base.copy(summary = "Renamed"))
        assertEquals("20261018T101500Z", merged.unmapped.single { it.name == "LAST-MODIFIED" }.value)
    }

    @Test
    fun noLocalChangeMeansTheServerVersionUnchanged() {
        val remote = base.copy(summary = "Renamed")
        assertEquals(remote, mergeTask(base, local = base, remote = remote))
    }

    @Test
    fun editsThatMatchTheServerVersionLeaveNothingToUpload() {
        val remote = done(base)
        assertEquals(remote, mergeTask(base, local = done(base), remote = remote))
    }
}
