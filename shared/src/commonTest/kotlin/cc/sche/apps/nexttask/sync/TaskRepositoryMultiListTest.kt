package cc.sche.apps.nexttask.sync

import cc.sche.apps.nexttask.model.Task
import cc.sche.apps.nexttask.model.TaskStatus
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TaskRepositoryMultiListTest {

    private val server = FakeCalDavServer()
    private val tasks = server.calendarHref
    private val work = server.addCalendar("work", "Work")

    private fun task(uid: String, summary: String = uid) = Task(uid = uid, summary = summary)

    private fun done(task: Task) = task.copy(status = TaskStatus.Completed, percentComplete = 100, completed = "20261018T100000Z")

    private fun repository() = TaskRepository(server.client(), AccountSnapshot(server.credentials.serverUrl, server.credentials.loginName))

    private fun TaskRepository.list(href: String) = snapshot.value.calendar(href)

    private fun TaskRepository.uids(href: String) = list(href)?.currentTasks?.map { it.uid }?.toSet()

    @Test
    fun syncLoadsEveryListWithItsOwnTasks() = runTest {
        server.write(task("a"))
        server.write(task("b"), work)
        val repo = repository().also { it.sync() }

        assertEquals(listOf("Tasks", "Work"), repo.snapshot.value.calendars.map { it.name })
        assertEquals(listOf("#0082c9", "#0082c9"), repo.snapshot.value.calendars.map { it.color })
        assertEquals(setOf("a"), repo.uids(tasks))
        assertEquals(setOf("b"), repo.uids(work))
    }

    @Test
    fun editsGoToTheListTheyWereMadeIn() = runTest {
        server.write(task("a"))
        server.write(task("b"), work)
        val repo = repository().also { it.sync() }

        repo.edit(work, listOf(done(task("b"))))
        repo.create(tasks, Task.create("c", "New", parentUid = null, now = "20261018T100000Z"))
        repo.sync()

        assertEquals(TaskStatus.Completed, server.task("b", work)?.status)
        assertTrue(server.hasTask("c", tasks))
        assertFalse(server.hasTask("c", work))
        assertEquals(0, repo.snapshot.value.pendingChanges)
    }

    @Test
    fun theSameUidInTwoListsIsTwoTasks() = runTest {
        server.write(task("same", "In Tasks"))
        server.write(task("same", "In Work"), work)
        val repo = repository().also { it.sync() }

        repo.edit(work, listOf(done(task("same", "In Work"))))
        repo.sync()

        assertEquals(TaskStatus.Completed, server.task("same", work)?.status)
        assertEquals(TaskStatus.NeedsAction, server.task("same", tasks)?.status)
        assertEquals("In Tasks", repo.list(tasks)?.tasks?.getValue("same")?.task?.summary)
    }

    @Test
    fun pendingChangesArePerListAndAddUp() = runTest {
        server.write(task("a"))
        server.write(task("b"), work)
        val repo = repository().also { it.sync() }

        server.offline = true
        repo.edit(tasks, listOf(done(task("a"))))
        repo.create(work, task("c"))
        repo.create(work, task("d"))

        assertEquals(1, repo.list(tasks)?.pendingChanges)
        assertEquals(2, repo.list(work)?.pendingChanges)
        assertEquals(3, repo.snapshot.value.pendingChanges)
    }

    @Test
    fun aLightweightSyncOnlyTouchesListsWithChangesAndSkipsDiscovery() = runTest {
        server.write(task("a"))
        server.write(task("b"), work)
        val repo = repository().also { it.sync() }
        server.requests.clear()

        repo.edit(work, listOf(done(task("b"))))
        repo.sync(full = false)

        assertTrue(server.requests.none { it.startsWith("PROPFIND") }, "no discovery: ${server.requests}")
        assertTrue(server.requests.all { it.contains("/work/") }, "only the changed list: ${server.requests}")
        assertEquals(TaskStatus.Completed, server.task("b", work)?.status)
    }

    @Test
    fun aLightweightSyncWithNothingPendingDoesNotContactTheServer() = runTest {
        server.write(task("a"))
        val repo = repository().also { it.sync() }
        server.requests.clear()

        repo.sync(full = false)

        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun theFirstSyncDiscoversListsEvenWhenLightweight() = runTest {
        server.write(task("a"))
        val repo = repository()

        repo.sync(full = false)

        assertTrue(repo.snapshot.value.isSynced)
        assertEquals(setOf("a"), repo.uids(tasks))
    }

    @Test
    fun newlyCreatedAndRenamedListsAreNoticedOnAFullSync() = runTest {
        val repo = repository().also { it.sync() }
        val errands = server.addCalendar("errands", "Errands")
        server.write(task("e"), errands)
        server.renameCalendar(work, "Job")

        repo.sync()

        assertEquals(listOf("Tasks", "Job", "Errands"), repo.snapshot.value.calendars.map { it.name })
        assertEquals(setOf("e"), repo.uids(errands))
    }

    @Test
    fun aListRemovedOnTheServerDisappearsAndItsPendingEditsAreReported() = runTest {
        server.write(task("a"))
        server.write(task("b"), work)
        val repo = repository().also { it.sync() }

        repo.edit(work, listOf(done(task("b"))))
        repo.create(work, task("c"))
        server.removeCalendar(work)
        val report = repo.sync()

        assertEquals(listOf("Tasks"), repo.snapshot.value.calendars.map { it.name })
        assertEquals(2, report.discardedEdits)
    }

    @Test
    fun aFailureInOneListKeepsWhatEarlierListsAlreadyGot() = runTest {
        server.write(task("a"))
        server.write(task("b"), work)
        val repo = repository().also { it.sync() }

        repo.edit(tasks, listOf(done(task("a"))))
        repo.edit(work, listOf(done(task("b"))))
        server.beforePut = { server.offline = true } // the connection drops once the first upload has started
        assertFailsWith<RuntimeException> { repo.sync() }

        val pending = repo.snapshot.value.calendars.associate { it.href to it.pendingChanges }
        assertEquals(1, pending.values.sum(), "one list was uploaded, one is still waiting: $pending")
        server.offline = false
        repo.sync()
        assertEquals(0, repo.snapshot.value.pendingChanges)
        assertEquals(TaskStatus.Completed, server.task("b", work)?.status)
    }

    @Test
    fun anAccountWithoutTaskListsIsAnError() = runTest {
        server.removeCalendar(tasks)
        server.removeCalendar(work)
        assertFailsWith<NoTaskListException> { repository().sync() }
    }

    @Test
    fun editsToAListThatDoesNotExistAreIgnored() = runTest {
        val repo = repository().also { it.sync() }
        val before = repo.snapshot.value

        repo.create("/nowhere/", task("x"))

        assertEquals(before, repo.snapshot.value)
        assertNotNull(repo.list(tasks))
    }

    @Test
    fun aListCreatedOfflineIsUsableAtOnceAndReachesTheServerWithItsTasks() = runTest {
        val repo = repository().also { it.sync() }

        server.offline = true
        val errands = repo.createList("Errands")
        repo.create(errands, task("e"))
        assertFailsWith<RuntimeException> { repo.sync(full = false) }

        assertEquals(setOf("e"), repo.uids(errands))
        assertEquals(2, repo.list(errands)?.pendingChanges, "the list and its task")
        assertTrue(errands.startsWith("/remote.php/dav/calendars/u/"), errands)

        server.offline = false
        repo.sync(full = false)

        assertEquals("Errands", server.calendarNames()[errands])
        assertTrue(server.hasTask("e", errands))
        assertEquals(false, repo.list(errands)?.isNew)
        assertEquals(0, repo.snapshot.value.pendingChanges)
    }

    @Test
    fun aCreatedListIsCreatedOnceAndKeepsItsPlace() = runTest {
        val repo = repository().also { it.sync() }

        val errands = repo.createList("Errands")
        repo.sync()
        repo.sync()

        assertEquals(listOf("Tasks", "Work", "Errands"), repo.snapshot.value.calendars.map { it.name })
        assertEquals(errands, repo.snapshot.value.calendars.last().href)
        assertEquals(1, server.requests.count { it.startsWith("MKCALENDAR") })
    }

    @Test
    fun aNewListWhoseNameIsTakenIsRenamedInsteadOfMerged() = runTest {
        val repo = repository().also { it.sync() }
        server.addCalendar("errands", "Errands") // made on another device meanwhile

        val first = repo.createList("errands")
        val second = repo.createList("Errands")
        val report = repo.sync(full = false)

        assertEquals("errands (offline)", server.calendarNames()[first])
        assertEquals("Errands (offline 2)", server.calendarNames()[second])
        assertEquals(
            listOf(RenamedList("errands", "errands (offline)"), RenamedList("Errands", "Errands (offline 2)")),
            report.renamedLists,
        )
        assertEquals(listOf("Tasks", "Work", "Errands", "errands (offline)", "Errands (offline 2)"), repo.snapshot.value.calendars.map { it.name })
    }

    @Test
    fun aNewListTheServerAlreadyHasIsOursFromAnUnconfirmedUpload() = runTest {
        val repo = repository().also { it.sync() }
        val errands = repo.createList("Errands")
        repo.create(errands, task("e"))
        server.addCalendarAt(errands, "Errands") // created, but the answer never arrived

        repo.sync(full = false)

        assertTrue(server.requests.none { it.startsWith("MKCALENDAR") })
        assertEquals(listOf("Tasks", "Work", "Errands"), repo.snapshot.value.calendars.map { it.name })
        assertTrue(server.hasTask("e", errands))
        assertEquals(0, repo.snapshot.value.pendingChanges)
    }

    @Test
    fun listsCanOnlyBeCreatedOnceTheServerHasBeenReached() {
        assertFailsWith<CalendarHomeUnknownException> { repository().createList("Errands") }
    }

    @Test
    fun aNewListNotYetOnTheServerIsDeletedLocally() = runTest {
        val repo = repository().also { it.sync() }
        val errands = repo.createList("Errands")
        repo.create(errands, task("e"))
        server.requests.clear()

        repo.deleteList(errands)
        repo.sync(full = false)

        assertTrue(server.requests.isEmpty(), "${server.requests}")
        assertEquals(listOf("Tasks", "Work"), repo.snapshot.value.calendars.map { it.name })
    }

    @Test
    fun aDeletedListIsGoneFromTheServerAndTheDevice() = runTest {
        server.write(task("b"), work)
        val repo = repository().also { it.sync() }
        repo.create(work, task("c"))

        repo.deleteList(work)

        assertFalse(work in server.calendarNames())
        assertEquals(listOf("Tasks"), repo.snapshot.value.calendars.map { it.name })
        assertEquals(0, repo.snapshot.value.pendingChanges)
    }

    @Test
    fun deletingAListOfflineFailsAndKeepsIt() = runTest {
        val repo = repository().also { it.sync() }

        server.offline = true
        assertFailsWith<RuntimeException> { repo.deleteList(work) }

        assertNotNull(repo.list(work))
    }

    @Test
    fun theLastListCannotBeDeleted() = runTest {
        server.removeCalendar(work)
        val repo = repository().also { it.sync() }
        server.requests.clear()

        assertFailsWith<LastTaskListException> { repo.deleteList(tasks) }

        assertTrue(server.requests.isEmpty())
        assertNotNull(repo.list(tasks))
    }

    @Test
    fun listsThatAlsoHoldEventsAreMarked() = runTest {
        val personal = server.addCalendar("personal", "Personal", acceptsEvents = true)
        val repo = repository().also { it.sync() }

        assertEquals(true, repo.list(personal)?.acceptsEvents)
        assertEquals(false, repo.list(work)?.acceptsEvents)
    }
}
