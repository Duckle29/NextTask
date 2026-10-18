package cc.sche.apps.nexttask.sync

import cc.sche.apps.nexttask.caldav.CalDavClient
import cc.sche.apps.nexttask.model.Task
import cc.sche.apps.nexttask.model.TaskStatus
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskRepositoryTest {

    private val server = FakeCalDavServer()
    private val client: CalDavClient = server.client()

    private fun task(uid: String, summary: String = uid, parent: String? = null) =
        Task(uid = uid, summary = summary, parentUid = parent)

    private fun done(task: Task) = task.copy(status = TaskStatus.Completed, percentComplete = 100, completed = "20261018T100000Z")

    private fun repository(initial: AccountSnapshot = AccountSnapshot(server.credentials.serverUrl, server.credentials.loginName)) =
        TaskRepository(client, initial)

    private fun TaskRepository.local(uid: String) = snapshot.value.only().tasks[uid]?.task

    @Test
    fun firstSyncLoadsEverythingAndRemembersTheCalendar() = runTest {
        server.write(task("a"))
        server.write(task("b", parent = "a"))
        val repo = repository()

        repo.sync()

        val snapshot = repo.snapshot.value
        assertTrue(snapshot.isSynced)
        assertEquals("Tasks", snapshot.only().name)
        assertEquals(setOf("a", "b"), snapshot.only().tasks.keys)
        assertEquals("a", repo.local("b")?.parentUid)
        assertNotNull(snapshot.only().syncToken)
        assertEquals(0, snapshot.pendingChanges)
    }

    @Test
    fun editsWhileOfflineAreKeptAndUploadedLater() = runTest {
        server.write(task("a"))
        val repo = repository()
        repo.sync()

        server.offline = true
        repo.edit(server.calendarHref, listOf(done(task("a"))))
        assertFailsWith<RuntimeException> { repo.sync() }

        assertEquals(TaskStatus.Completed, repo.local("a")?.status)
        assertEquals(1, repo.snapshot.value.pendingChanges)
        assertEquals(TaskStatus.NeedsAction, server.task("a")?.status)

        server.offline = false
        val report = repo.sync()

        assertEquals(1, report.uploaded)
        assertEquals(0, repo.snapshot.value.pendingChanges)
        assertEquals(TaskStatus.Completed, server.task("a")?.status)
    }

    @Test
    fun snapshotSurvivesRestartAndStillSyncs() = runTest {
        server.write(task("a"))
        val first = repository()
        first.sync()
        first.edit(server.calendarHref, listOf(done(task("a"))))

        // "Restart": a new repository from what was saved before the app died.
        val second = repository(first.snapshot.value)
        server.write(task("b"))
        val report = second.sync()

        assertEquals(1, report.uploaded)
        assertEquals(setOf("a", "b"), second.snapshot.value.only().tasks.keys)
        assertEquals(TaskStatus.Completed, server.task("a")?.status)
    }

    @Test
    fun ourOwnUploadComingBackIsNotReportedAsAServerChange() = runTest {
        server.write(task("a"))
        val repo = repository().also { it.sync() }

        repo.edit(server.calendarHref, listOf(done(task("a"))))
        repo.sync(full = false)
        // Edited again before the next pull, which returns the version uploaded above.
        repo.edit(server.calendarHref, listOf(task("a")))
        val report = repo.sync(full = false)

        assertEquals(0, report.mergedEdits)
        assertEquals(TaskStatus.NeedsAction, server.task("a")?.status)
        assertEquals(0, repo.snapshot.value.pendingChanges)
    }

    @Test
    fun offlineEditAndServerEditToDifferentFieldsAreBothKept() = runTest {
        server.write(task("a", "Old"))
        val repo = repository()
        repo.sync()

        repo.edit(server.calendarHref, listOf(done(task("a", "Old"))))
        server.write(task("a", "Renamed on the server"))
        val report = repo.sync()

        assertEquals(1, report.mergedEdits)
        val onServer = server.task("a")!!
        assertEquals("Renamed on the server", onServer.summary)
        assertEquals(TaskStatus.Completed, onServer.status)
        assertEquals(onServer.summary, repo.local("a")?.summary)
        assertEquals(0, repo.snapshot.value.pendingChanges)
    }

    @Test
    fun serverEditToAnotherTaskArrivesAndDoesNotDisturbPendingEdits() = runTest {
        server.write(task("a"))
        server.write(task("b"))
        val repo = repository()
        repo.sync()

        server.offline = true
        repo.edit(server.calendarHref, listOf(done(task("a"))))
        server.offline = false
        server.write(task("b", "Renamed"))
        repo.sync()

        assertEquals("Renamed", repo.local("b")?.summary)
        assertEquals(TaskStatus.Completed, server.task("a")?.status)
    }

    @Test
    fun editsToATaskDeletedOnTheServerAreDiscardedAndReported() = runTest {
        server.write(task("a"))
        server.write(task("b"))
        val repo = repository()
        repo.sync()

        repo.edit(server.calendarHref, listOf(done(task("a"))))
        server.delete("a")
        server.delete("b")
        val report = repo.sync()

        assertEquals(1, report.discardedEdits)
        assertTrue(repo.snapshot.value.only().tasks.isEmpty())
        assertFalse(server.hasTask("a"))
    }

    @Test
    fun changeBetweenPullAndUploadIsMergedOnTheNextRound() = runTest {
        server.write(task("a", "Old"))
        val repo = repository()
        repo.sync()

        repo.edit(server.calendarHref, listOf(done(task("a", "Old"))))
        server.beforePut = { server.write(task("a", "Changed at the worst moment")) }
        val report = repo.sync()

        assertEquals(1, report.uploaded)
        val onServer = server.task("a")!!
        assertEquals("Changed at the worst moment", onServer.summary)
        assertEquals(TaskStatus.Completed, onServer.status)
        assertEquals(0, repo.snapshot.value.pendingChanges)
    }

    @Test
    fun expiredSyncTokenFallsBackToAFullResyncAndNoticesDeletions() = runTest {
        server.write(task("a"))
        server.write(task("b"))
        val repo = repository()
        repo.sync()

        server.delete("b")
        server.write(task("c"))
        server.oldestValidToken = 1_000

        repo.sync()

        assertEquals(setOf("a", "c"), repo.snapshot.value.only().tasks.keys)
    }

    @Test
    fun secondSyncWithNothingNewChangesNothing() = runTest {
        server.write(task("a"))
        val repo = repository()
        repo.sync()
        val before = repo.snapshot.value

        val report = repo.sync()

        assertEquals(SyncReport(), report)
        assertEquals(before, repo.snapshot.value)
    }

    @Test
    fun undoingAnUnsyncedEditLeavesNothingToUpload() = runTest {
        server.write(task("a"))
        val repo = repository()
        repo.sync()
        val original = repo.local("a")!!

        repo.edit(server.calendarHref, listOf(done(original)))
        assertEquals(1, repo.snapshot.value.pendingChanges)
        repo.edit(server.calendarHref, listOf(original))

        assertEquals(0, repo.snapshot.value.pendingChanges)
    }

    @Test
    fun editsMadeDuringAnUploadStayPending() {
        val a = task("a")
        val snapshot = CalendarSnapshot("/cal/", "Tasks", tasks = mapOf("a" to StoredTask("/a.ics", "\"1\"", a))).applyEdits(listOf(done(a)))
        val uploaded = snapshot.tasks.getValue("a").task

        val editedAgain = snapshot.applyEdits(listOf(uploaded.copy(summary = "Edited meanwhile")))
        val result = editedAgain.applyUploaded("a", uploaded, "\"2\"")

        val stored = result.tasks.getValue("a")
        assertEquals("Edited meanwhile", stored.task.summary)
        assertEquals(uploaded, stored.base)
        assertEquals("\"2\"", stored.etag)
        assertTrue(stored.isDirty)
    }

    @Test
    fun editsToUnknownTasksAreIgnored() {
        val snapshot = CalendarSnapshot("/cal/", "Tasks")
        assertEquals(snapshot, snapshot.applyEdits(listOf(task("ghost"))))
        assertNull(snapshot.tasks["ghost"])
    }

    @Test
    fun snapshotOnlyBelongsToItsOwnAccount() {
        val snapshot = AccountSnapshot("https://a.example", "alice")
        assertTrue(snapshot.belongsTo("https://a.example", "alice"))
        assertFalse(snapshot.belongsTo("https://a.example", "bob"))
        assertFalse(snapshot.belongsTo("https://b.example", "alice"))
        assertFalse(snapshot.copy(schemaVersion = 0).belongsTo("https://a.example", "alice"))
    }
}
