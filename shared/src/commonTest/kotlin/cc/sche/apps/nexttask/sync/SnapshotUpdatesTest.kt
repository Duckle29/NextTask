package cc.sche.apps.nexttask.sync

import cc.sche.apps.nexttask.caldav.RemoteTask
import cc.sche.apps.nexttask.caldav.SyncResult
import cc.sche.apps.nexttask.model.Task
import cc.sche.apps.nexttask.model.TaskChange
import cc.sche.apps.nexttask.model.TaskStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SnapshotUpdatesTest {

    private val calendar = "/cal/"

    private fun task(uid: String, summary: String = uid) = Task(uid = uid, summary = summary)

    private fun synced(vararg tasks: Task) = CalendarSnapshot(
        href = calendar,
        name = "Tasks",
        tasks = tasks.associate { it.uid to StoredTask("$calendar${it.uid}.ics", "\"${it.uid}-1\"", it) },
    )

    private fun created(snapshot: CalendarSnapshot, task: Task) = snapshot.applyChanges(listOf(TaskChange(null, task)))
    private fun deleted(snapshot: CalendarSnapshot, task: Task) = snapshot.applyChanges(listOf(TaskChange(task, null)))

    private fun result(vararg changed: Task, deleted: List<String> = emptyList()) = SyncResult(
        syncToken = "t",
        changed = changed.map { RemoteTask("$calendar${it.uid}.ics", "\"${it.uid}-2\"", it) },
        deletedHrefs = deleted,
        unsupportedHrefs = emptyList(),
    )

    // --- creating

    @Test
    fun aCreatedTaskIsNewAndHasItsResourceNameFromTheUid() {
        val snapshot = created(synced(), task("a b"))
        val stored = snapshot.tasks.getValue("a b")

        assertTrue(stored.isNew)
        assertNull(stored.etag)
        assertEquals("/cal/a%20b.ics", stored.href)
        assertEquals(1, snapshot.pendingChanges)
        assertEquals(listOf(task("a b")), snapshot.currentTasks)
    }

    @Test
    fun editingANewTaskKeepsItNewWithNothingToCompare() {
        val snapshot = created(synced(), task("a")).applyEdits(listOf(task("a", "Renamed")))
        val stored = snapshot.tasks.getValue("a")

        assertTrue(stored.isNew)
        assertNull(stored.base)
        assertEquals("Renamed", stored.task.summary)
    }

    // --- deleting

    @Test
    fun aDeletedTaskIsHiddenButRememberedUntilTheServerKnows() {
        val a = task("a")
        val snapshot = deleted(synced(a, task("b")), a)

        assertEquals(listOf(task("b")), snapshot.currentTasks)
        assertTrue(snapshot.tasks.getValue("a").isDeleted)
        assertEquals(1, snapshot.pendingChanges)
    }

    @Test
    fun deletingATaskThatNeverReachedTheServerLeavesNothingToTellIt() {
        val a = task("a")
        val snapshot = deleted(created(synced(), a), a)

        assertEquals(0, snapshot.pendingChanges)
        assertTrue(snapshot.withoutAbandonedTasks().tasks.isEmpty())
    }

    @Test
    fun editsToADeletedTaskAreIgnored() {
        val a = task("a")
        val snapshot = deleted(synced(a), a)
        assertEquals(snapshot, snapshot.applyEdits(listOf(task("a", "Edited"))))
    }

    @Test
    fun deletingTwiceOrDeletingUnknownTasksChangesNothing() {
        val a = task("a")
        val once = deleted(synced(a), a)
        assertEquals(once, deleted(once, a))
        assertEquals(synced(), deleted(synced(), task("ghost")))
    }

    // --- undoing

    @Test
    fun undoingADeletionBeforeItWasSentLeavesNothingPending() {
        val a = task("a")
        val snapshot = deleted(synced(a), a).applyChanges(listOf(TaskChange(null, a)))

        assertEquals(listOf(a), snapshot.currentTasks)
        assertEquals(0, snapshot.pendingChanges)
        assertFalse(snapshot.tasks.getValue("a").isNew)
    }

    @Test
    fun undoingADeletionAfterItWasSentCreatesTheTaskAgain() {
        val a = task("a")
        val snapshot = created(synced(), a) // the server has nothing under this uid any more

        assertTrue(snapshot.tasks.getValue("a").isNew)
        assertEquals(1, snapshot.pendingChanges)
    }

    @Test
    fun undoingACreationBeforeItWasSentAndRedoingItBringsItBack() {
        val a = task("a")
        val undone = deleted(created(synced(), a), a)
        val redone = undone.applyChanges(listOf(TaskChange(null, a)))

        assertEquals(listOf(a), redone.currentTasks)
        assertTrue(redone.tasks.getValue("a").isNew)
        assertEquals(1, redone.pendingChanges)
    }

    // --- uploads finishing

    @Test
    fun aTaskDeletedWhileItsUploadWasInFlightStillGetsDeleted() {
        val a = task("a")
        val snapshot = deleted(created(synced(), a), a).applyUploaded("a", a, "\"x\"")
        val stored = snapshot.tasks.getValue("a")

        assertTrue(stored.isDeleted)
        assertFalse(stored.isNew)
        assertEquals("\"x\"", stored.etag)
        assertEquals(1, snapshot.pendingChanges)
    }

    @Test
    fun theServerConfirmingADeletionRemovesTheTombstone() {
        val a = task("a")
        assertTrue(deleted(synced(a), a).applyDeletedOnServer("a").tasks.isEmpty())
    }

    @Test
    fun aTaskBroughtBackWhileItsDeletionWasInFlightIsCreatedAgain() {
        val a = task("a")
        val restored = deleted(synced(a), a).applyChanges(listOf(TaskChange(null, a))).applyDeletedOnServer("a")
        val stored = restored.tasks.getValue("a")

        assertTrue(stored.isNew)
        assertNull(stored.etag)
        assertEquals(a, stored.task)
    }

    // --- pulling

    @Test
    fun tasksCreatedHereSurviveAFullListingThatLacksThem() {
        val snapshot = created(synced(task("old")), task("new"))
        val pulled = snapshot.applyPull(result(), fullListing = true).snapshot

        assertEquals(setOf("new"), pulled.tasks.keys)
    }

    @Test
    fun aDeletionStandsWhenTheServerCopyIsUnchanged() {
        val a = task("a")
        val snapshot = deleted(synced(a), a)
        val unchanged = SyncResult("t", listOf(RemoteTask("/cal/a.ics", "\"a-1\"", a)), emptyList(), emptyList())
        val outcome = snapshot.applyPull(unchanged, fullListing = true)

        assertTrue(outcome.snapshot.tasks.getValue("a").isDeleted)
        assertEquals(0, outcome.restoredDeletions)
    }

    @Test
    fun aDeletionIsGivenUpWhenAnotherClientChangedTheTask() {
        val a = task("a")
        val outcome = deleted(synced(a), a).applyPull(result(task("a", "Changed elsewhere")), fullListing = false)
        val stored = outcome.snapshot.tasks.getValue("a")

        assertFalse(stored.isDeleted)
        assertEquals("Changed elsewhere", stored.task.summary)
        assertEquals(1, outcome.restoredDeletions)
        assertEquals(0, outcome.snapshot.pendingChanges)
    }

    @Test
    fun deletingATaskTheServerAlreadyDeletedIsNotReportedAsLostWork() {
        val a = task("a")
        val outcome = deleted(synced(a), a).applyPull(result(deleted = listOf("/cal/a.ics")), fullListing = false)

        assertTrue(outcome.snapshot.tasks.isEmpty())
        assertEquals(0, outcome.discardedEdits)
    }

    @Test
    fun aCreatedTaskThatTheServerAlreadyHasIsTreatedAsOurEarlierUpload() {
        val a = task("a")
        val outcome = created(synced(), a).applyPull(result(a), fullListing = false)
        val stored = outcome.snapshot.tasks.getValue("a")

        assertFalse(stored.isNew)
        assertEquals("\"a-2\"", stored.etag)
        assertEquals(0, outcome.snapshot.pendingChanges)
    }

    @Test
    fun aCreatedTaskThatChangedAfterItsLostUploadKeepsTheNewerLocalVersion() {
        val a = task("a")
        val snapshot = created(synced(), a).applyEdits(listOf(task("a", "Renamed here")))
        val stored = snapshot.applyPull(result(a), fullListing = false).snapshot.tasks.getValue("a")

        assertEquals("Renamed here", stored.task.summary)
        assertEquals(a, stored.base)
        assertTrue(stored.isDirty)
    }

    @Test
    fun aDeletedTaskWhoseEtagWasUnknownGetsItNowAndStaysDeleted() {
        val a = task("a")
        val snapshot = deleted(created(synced(), a), a).applyUploaded("a", a, null)
        val pulled = snapshot.applyPull(result(a), fullListing = false).snapshot.tasks.getValue("a")

        assertTrue(pulled.isDeleted)
        assertEquals("\"a-2\"", pulled.etag)
        assertEquals(TaskStatus.NeedsAction, pulled.task.status)
    }
}
