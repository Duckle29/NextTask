package cc.sche.apps.nexttask.sync

import cc.sche.apps.nexttask.model.Edit
import cc.sche.apps.nexttask.model.Task
import cc.sche.apps.nexttask.model.TaskChange
import cc.sche.apps.nexttask.tree.TaskTree
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaskRepositoryCreateDeleteTest {

    private val server = FakeCalDavServer()
    private val now = "20261018T100000Z"

    private fun task(uid: String, summary: String = uid, parent: String? = null) =
        Task(uid = uid, summary = summary, parentUid = parent)

    private fun repository() = TaskRepository(server.client(), AccountSnapshot(server.credentials.serverUrl, server.credentials.loginName))

    private fun TaskRepository.visible() = snapshot.value.currentTasks.map { it.uid }.toSet()

    @Test
    fun aTaskCreatedOfflineIsUploadedLaterWithItsParent() = runTest {
        server.write(task("parent"))
        val repo = repository()
        repo.sync()

        server.offline = true
        repo.create(Task.create("child", "Buy milk", parentUid = "parent", now = now))
        assertEquals(setOf("parent", "child"), repo.visible())
        assertFailsWith<RuntimeException> { repo.sync() }
        assertFalse(server.hasTask("child"))
        assertEquals(1, repo.snapshot.value.pendingChanges)

        server.offline = false
        val report = repo.sync()

        assertEquals(1, report.uploaded)
        val onServer = server.task("child")!!
        assertEquals("Buy milk", onServer.summary)
        assertEquals("parent", onServer.parentUid)
        assertEquals(0, repo.snapshot.value.pendingChanges)
        assertFalse(repo.snapshot.value.tasks.getValue("child").isNew)
    }

    @Test
    fun aCreatedTaskCanBeEditedBeforeItIsUploaded() = runTest {
        val repo = repository().also { it.sync() }
        val created = Task.create("a", "Draft", parentUid = null, now = now)

        repo.create(created)
        repo.edit(listOf(created.copy(summary = "Final")))
        repo.sync()

        assertEquals("Final", server.task("a")?.summary)
    }

    @Test
    fun aTaskDeletedOfflineIsDeletedOnTheServerLater() = runTest {
        server.write(task("a"))
        server.write(task("b"))
        val repo = repository()
        repo.sync()

        server.offline = true
        repo.delete(listOf("a"))
        assertEquals(setOf("b"), repo.visible())
        assertFailsWith<RuntimeException> { repo.sync() }
        assertTrue(server.hasTask("a"))

        server.offline = false
        repo.sync()

        assertFalse(server.hasTask("a"))
        assertTrue(server.hasTask("b"))
        assertEquals(setOf("b"), repo.snapshot.value.tasks.keys)
        assertEquals(0, repo.snapshot.value.pendingChanges)
    }

    @Test
    fun deletingAParentWithItsSubtasksRemovesTheWholeSubtree() = runTest {
        server.write(task("p"))
        server.write(task("c", parent = "p"))
        server.write(task("g", parent = "c"))
        server.write(task("other"))
        val repo = repository()
        repo.sync()

        repo.delete(TaskTree.subtreeUids(repo.snapshot.value.currentTasks, "p"))
        repo.sync()

        assertEquals(setOf("other"), server.run { listOf("p", "c", "g", "other").filter { hasTask(it) }.toSet() })
        assertEquals(setOf("other"), repo.visible())
    }

    @Test
    fun aTaskCreatedAndDeletedOfflineNeverTouchesTheServer() = runTest {
        val repo = repository().also { it.sync() }

        repo.create(task("a"))
        repo.delete(listOf("a"))
        val report = repo.sync()

        assertEquals(0, report.uploaded)
        assertFalse(server.hasTask("a"))
        assertTrue(repo.snapshot.value.tasks.isEmpty())
    }

    @Test
    fun aDeletionIsGivenUpWhenTheTaskWasChangedOnTheServer() = runTest {
        server.write(task("a", "Old"))
        val repo = repository()
        repo.sync()

        repo.delete(listOf("a"))
        server.write(task("a", "Edited by someone else"))
        val report = repo.sync()

        assertEquals(1, report.restoredDeletions)
        assertEquals("Edited by someone else", server.task("a")?.summary)
        assertEquals(setOf("a"), repo.visible())
        assertEquals(0, repo.snapshot.value.pendingChanges)
    }

    @Test
    fun deletingATaskTheServerAlreadyDeletedSucceeds() = runTest {
        server.write(task("a"))
        val repo = repository()
        repo.sync()

        repo.delete(listOf("a"))
        server.delete("a")
        val report = repo.sync()

        assertEquals(0, report.restoredDeletions)
        assertEquals(0, report.discardedEdits)
        assertTrue(repo.snapshot.value.tasks.isEmpty())
    }

    @Test
    fun undoingADeletionBeforeSyncingNeverContactsTheServerAboutIt() = runTest {
        server.write(task("a"))
        val repo = repository()
        repo.sync()
        val original = repo.snapshot.value.tasks.getValue("a").task
        val deletion = Edit(listOf(TaskChange(original, null)))

        repo.apply(deletion)
        repo.apply(deletion.inverse())
        val report = repo.sync()

        assertEquals(0, report.uploaded)
        assertTrue(server.hasTask("a"))
        assertEquals(setOf("a"), repo.visible())
    }

    @Test
    fun undoingADeletionAfterSyncingCreatesTheTaskAgain() = runTest {
        server.write(task("a", "Keep me"))
        val repo = repository()
        repo.sync()
        val original = repo.snapshot.value.tasks.getValue("a").task
        val deletion = Edit(listOf(TaskChange(original, null)))

        repo.apply(deletion)
        repo.sync()
        assertFalse(server.hasTask("a"))
        repo.apply(deletion.inverse())
        repo.sync()

        assertEquals("Keep me", server.task("a")?.summary)
        assertEquals(setOf("a"), repo.visible())
        assertEquals(0, repo.snapshot.value.pendingChanges)
    }

    @Test
    fun undoingACreationAfterSyncingDeletesItAgain() = runTest {
        val repo = repository().also { it.sync() }
        val created = Task.create("a", "Oops", parentUid = null, now = now)
        val creation = Edit(listOf(TaskChange(null, created)))

        repo.apply(creation)
        repo.sync()
        assertTrue(server.hasTask("a"))
        repo.apply(creation.inverse())
        repo.sync()

        assertFalse(server.hasTask("a"))
        assertTrue(repo.snapshot.value.tasks.isEmpty())
    }

    @Test
    fun anUploadWhoseConfirmationWasLostDoesNotDuplicateOrFail() = runTest {
        val repo = repository().also { it.sync() }
        val created = Task.create("a", "Once", parentUid = null, now = now)

        repo.create(created)
        // The first upload reached the server, but the app never heard back.
        server.write(created)
        val report = repo.sync()

        // Pulling first shows the server already has it, so there is nothing left to upload.
        assertEquals(0, report.uploaded)
        assertEquals("Once", server.task("a")?.summary)
        assertEquals(setOf("a"), repo.visible())
        assertEquals(0, repo.snapshot.value.pendingChanges)
    }

    @Test
    fun aFullResyncKeepsTasksCreatedOffline() = runTest {
        server.write(task("a"))
        val repo = repository()
        repo.sync()

        server.offline = true
        repo.create(task("local"))
        assertFailsWith<RuntimeException> { repo.sync() }
        server.offline = false
        server.oldestValidToken = 1_000 // the stored sync token is no longer accepted
        repo.sync()

        assertEquals(setOf("a", "local"), repo.visible())
        assertTrue(server.hasTask("local"))
    }

    @Test
    fun deletedTasksAreNotExportedOrShown() = runTest {
        server.write(task("a"))
        server.write(task("b"))
        val repo = repository()
        repo.sync()

        repo.delete(listOf("a"))

        assertEquals(listOf("b"), repo.snapshot.value.currentTasks.map { it.uid })
    }
}
