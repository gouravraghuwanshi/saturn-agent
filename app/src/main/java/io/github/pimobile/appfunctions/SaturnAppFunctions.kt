package io.github.pimobile.appfunctions

import androidx.annotation.RequiresApi
import androidx.appfunctions.AppFunctionDeclaration
import androidx.appfunctions.AppFunctionElementNotFoundException
import androidx.appfunctions.AppFunctionInvalidArgumentException
import androidx.appfunctions.AppFunctionSerializable
import androidx.appfunctions.AppFunctionService
import androidx.appfunctions.AppFunctionServiceEntryPoint
import io.github.pimobile.runtime.TaskManager
import io.github.pimobile.ui.state.Item
import io.github.pimobile.ui.state.TaskUi
import io.github.pimobile.ui.state.UiStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Saturn's AppFunctions: what Gemini and other approved assistants on Android
 * 16+ can ask Saturn to do. Saturn is an AI agent on this phone; these hand it
 * work (a new task or a follow-up) and read back progress and the files it
 * made. Nothing here deletes anything.
 */
@RequiresApi(36)
@AppFunctionServiceEntryPoint(
    serviceName = "SaturnAppFunctionService",
    appFunctionXmlFileName = "saturn_app_function_service",
)
abstract class BaseSaturnAppFunctionService : AppFunctionService() {

    private suspend fun <T> onMain(block: () -> T): T = withContext(Dispatchers.Main) {
        UiStore.init(applicationContext)
        block()
    }

    /**
     * Start a new task in Saturn, the AI agent on this phone. Saturn works on it by itself and can run commands,
     * browse the web, write files (web pages, documents, scripts), use the phone's features and set timers.
     * Use this when the user wants Saturn to do something. Saturn uses the AI model the user set up in it.
     *
     * @param instruction What Saturn should do, in the user's words (e.g. "Make me a packing list web page for Goa").
     * @param projectName Optional name of an existing Saturn project to work in; leave empty for the general one.
     * @return The new task, including its number for getTask and continueTask.
     * @throws AppFunctionInvalidArgumentException If the instruction is blank, or the project doesn't exist. If thrown, ask the user what Saturn should do or which project they meant.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun startTask(instruction: String, projectName: String?): SaturnTask {
        if (instruction.isBlank()) throw AppFunctionInvalidArgumentException("The instruction is empty; ask the user what Saturn should do.")
        return onMain {
            val cwd = projectFolder(projectName)
            UiStore.newTask(instruction.trim(), cwd).toSaturnTask()
        }
    }

    /**
     * Send a follow-up message to an existing Saturn task, for example an answer to Saturn's question or a change of plan.
     *
     * @param taskNumber The task's number (shown as #number in Saturn, and returned by startTask and listTasks).
     * @param message The follow-up text from the user.
     * @return The task after the message was sent.
     * @throws AppFunctionElementNotFoundException If there is no task with that number. If thrown, call listTasks to find the right one.
     * @throws AppFunctionInvalidArgumentException If the message is blank.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun continueTask(taskNumber: Int, message: String): SaturnTask {
        if (message.isBlank()) throw AppFunctionInvalidArgumentException("The message is empty.")
        return onMain {
            val ui = find(taskNumber)
            UiStore.send(ui, message.trim(), followUp = true)
            ui.toSaturnTask()
        }
    }

    /**
     * Get the status of a Saturn task and Saturn's latest reply in it.
     *
     * @param taskNumber The task's number (#number in Saturn).
     * @return The task's status and Saturn's most recent reply text.
     * @throws AppFunctionElementNotFoundException If there is no task with that number. If thrown, call listTasks.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun getTask(taskNumber: Int): SaturnTaskDetails = onMain {
        val ui = find(taskNumber)
        val t = ui.toSaturnTask()
        SaturnTaskDetails(t.taskNumber, t.title, t.status, t.project,
            latestReply = ((ui.items.lastOrNull { it is Item.Assistant && it.text.isNotBlank() } as? Item.Assistant)?.text
                ?: lastReplyFromSession(ui))?.take(4000) ?: "")
    }

    /**
     * List the user's most recent Saturn tasks (newest first), not including archived ones.
     *
     * @return Up to 15 recent tasks with their number, title and status.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun listTasks(): List<SaturnTask> = onMain {
        UiStore.tasks.filter { !it.archived && !it.hidden }.take(15).map { it.toSaturnTask() }
    }

    /**
     * List the files Saturn made for the user (its Artifacts): web pages, documents, images and other files, newest first.
     *
     * @param projectName Optional project to list; leave empty for all projects.
     * @return Up to 30 artifacts with their name, project and size.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun listArtifacts(projectName: String?): List<SaturnArtifact> = withContext(Dispatchers.IO) {
        UiStore.init(applicationContext)
        val root = UiStore.runtime.artifacts
        val dirs = (root.listFiles() ?: emptyArray()).filter { it.isDirectory }
            .filter { projectName.isNullOrBlank() || it.name.equals(projectName.trim(), ignoreCase = true) }
        dirs.flatMap { d -> d.walkTopDown().maxDepth(3).filter { it.isFile && !it.name.startsWith(".") }.map { d.name to it } }
            .sortedByDescending { it.second.lastModified() }.take(30)
            .map { (project, f) -> SaturnArtifact(f.name, project, f.length(), f.lastModified()) }
    }

    // ----------------------------------------------------------------- helpers

    private fun find(number: Int): TaskUi = UiStore.tasks.firstOrNull { it.task.number == number }
        ?: throw AppFunctionElementNotFoundException("No Saturn task #$number. Call listTasks to see the user's tasks.")

    /** History isn't loaded until a task is opened, so read pi's session file directly. */
    private fun lastReplyFromSession(ui: TaskUi): String? = runCatching {
        val f = io.github.pimobile.runtime.Transfer.sessionFile(applicationContext, ui.task.sessionId) ?: return null
        f.readLines().asReversed().asSequence().mapNotNull { runCatching { org.json.JSONObject(it) }.getOrNull() }
            .mapNotNull { it.optJSONObject("message") }.filter { it.optString("role") == "assistant" }
            .map { m -> m.optJSONArray("content")?.let { c -> (0 until c.length()).mapNotNull { c.optJSONObject(it) }
                .filter { it.optString("type") == "text" }.joinToString("") { it.optString("text") } }.orEmpty() }
            .firstOrNull { it.isNotBlank() }
    }.getOrNull()

    private fun projectFolder(name: String?): File {
        val root = UiStore.runtime.projects
        if (name.isNullOrBlank() || name.equals("general", true)) return root
        return root.listFiles()?.firstOrNull { it.isDirectory && it.name.equals(name.trim(), ignoreCase = true) }
            ?: throw AppFunctionInvalidArgumentException("Saturn has no project called \"$name\". Leave the project empty to use the general one.")
    }

    private fun TaskUi.toSaturnTask() = SaturnTask(
        taskNumber = task.number,
        title = title,
        status = when (status) {
            TaskManager.Status.STARTING, TaskManager.Status.RUNNING -> "working"
            TaskManager.Status.NEEDS_INPUT -> "needs the user's answer"
            TaskManager.Status.FAILED -> "failed"
            else -> "done"
        },
        project = UiStore.runtime.projectName(task.cwd),
    )
}

/** A Saturn task. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class SaturnTask(
    /** The task's number, shown as #number in Saturn. */
    val taskNumber: Int,
    /** A short title (the start of the instruction). */
    val title: String,
    /** "working", "needs the user's answer", "done" or "failed". */
    val status: String,
    /** The Saturn project the task works in. */
    val project: String,
)

/** A Saturn task with Saturn's latest reply. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class SaturnTaskDetails(
    /** The task's number, shown as #number in Saturn. */
    val taskNumber: Int,
    /** A short title (the start of the instruction). */
    val title: String,
    /** "working", "needs the user's answer", "done" or "failed". */
    val status: String,
    /** The Saturn project the task works in. */
    val project: String,
    /** Saturn's most recent reply in this task (Markdown), or empty if it hasn't replied yet. */
    val latestReply: String,
)

/** A file Saturn made for the user. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class SaturnArtifact(
    /** The file name, e.g. "trip-plan.html". */
    val name: String,
    /** The Saturn project it belongs to. */
    val project: String,
    /** Size in bytes. */
    val sizeBytes: Long,
    /** When it was last changed, in milliseconds since 1970. */
    val modifiedEpochMillis: Long,
)
