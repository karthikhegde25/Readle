package com.readle.app.ui.library

import android.app.Activity
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.result.IntentSenderRequest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.readle.app.data.repo.LibraryManager
import com.readle.app.domain.FormatSupport
import com.readle.app.files.OpenDocumentReadWrite
import com.readle.app.files.SafFiles
import com.readle.app.ui.LocalAppContainer
import com.readle.app.ui.components.ConfirmDialog
import kotlinx.coroutines.launch

/**
 * Deleting a file the user picked needs Android's permission for that file. This helper tries the delete;
 * for files Android refuses, it explains why and opens the system file picker at the file so the user can
 * grant access with one tap, then finishes the delete. Returns the function to call (after the user confirmed).
 *
 * [onResult] gets the summary message and the number of files deleted.
 */
@Composable
fun rememberDeleteFlow(onResult: (message: String, deleted: Int) -> Unit): (Collection<Long>) -> Unit {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var queue by remember { mutableStateOf<List<LibraryManager.PendingDelete>>(emptyList()) }
    var deletedSoFar by remember { mutableIntStateOf(0) }
    var failures by remember { mutableStateOf<List<String>>(emptyList()) }
    val contract = remember { OpenDocumentReadWrite() }
    var sys by remember { mutableStateOf<List<LibraryManager.SystemDelete>>(emptyList()) }

    fun finish(extraFailures: List<String> = emptyList(), deletedNow: Int = 0) {
        val total = deletedSoFar + deletedNow
        val fails = failures + extraFailures
        val msg = when {
            fails.isEmpty() -> "Deleted $total ${if (total == 1) "file" else "files"}"
            else -> "Deleted $total. Couldn't delete: ${fails.first()}"
        }
        queue = emptyList(); deletedSoFar = 0; failures = emptyList()
        onResult(msg, total)
    }

    val sysLauncher = rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val items = sys
        sys = emptyList()
        if (r.resultCode == Activity.RESULT_OK) {
            scope.launch {
                container.libraryManager.forgetDeleted(items.map { it.id })
                finish(deletedNow = items.size)
            }
        } else {
            finish(items.map { "${it.name}: deleting was cancelled" })
        }
    }

    /** Asks Android itself to confirm deleting files whose storage provider can't be asked directly (Android 11+). */
    fun startSystemDelete() {
        val items = sys
        try {
            if (Build.VERSION.SDK_INT < 30) error("Delete it from your Files app instead.")
            val req = MediaStore.createDeleteRequest(context.contentResolver, items.map { Uri.parse(it.mediaUri) })
            sysLauncher.launch(IntentSenderRequest.Builder(req.intentSender).build())
        } catch (e: Exception) {
            sys = emptyList()
            finish(items.map { "${it.name}: Android won't let apps delete this file from here. Delete it in your Files app." })
        }
    }

    /** Ends the flow, or hands the remaining files to Android's own delete confirmation first. */
    fun proceed(extraFailures: List<String> = emptyList()) {
        queue = emptyList()
        if (sys.isNotEmpty()) {
            failures = failures + extraFailures
            startSystemDelete()
        } else finish(extraFailures)
    }

    val picker = rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        val current = queue.firstOrNull() ?: return@rememberLauncherForActivityResult
        val uri: Uri? = r.data?.data
        if (uri == null) {
            // Cancelled: keep what was already deleted, report the rest as not deleted.
            val left = queue.map { "${it.name}: access was not granted" }
            proceed(left)
            return@rememberLauncherForActivityResult
        }
        if (uri.toString() != current.uri) {
            onResult("That is a different file, so nothing was deleted. Choose “${current.name}” to allow deleting it.", 0)
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            SafFiles.takePersistableReadWrite(context, uri)
            val out = container.libraryManager.deleteFiles(listOf(current.id))
            deletedSoFar += out.deleted
            sys = sys + out.systemConfirm
            failures = failures + out.failures + out.needAccess.map { "${it.name}: this storage location didn't grant delete access. Delete it from your Files app." }
            val rest = queue.drop(1)
            queue = rest
            if (rest.isEmpty()) proceed()
        }
    }

    queue.firstOrNull()?.let { current ->
        ConfirmDialog(
            title = "Allow Readle to delete this file?",
            message = "Android only lets an app delete a file you have given it access to. Tap Allow, then select “${current.name}” in the file picker (it opens at the file). Readle then deletes it." +
                if (queue.size > 1) "\n\n${queue.size - 1} more file${if (queue.size > 2) "s" else ""} will need the same step." else "",
            confirmLabel = "Allow",
            destructive = true,
            onConfirm = {
                picker.launch(contract.intentFor(context, FormatSupport.pickerMimeTypes, Uri.parse(current.uri)))
            },
            onDismiss = { proceed(queue.map { "${it.name}: access was not granted" }) }
        )
    }

    return { ids ->
        scope.launch {
            val out = container.libraryManager.deleteFiles(ids)
            deletedSoFar = out.deleted
            failures = out.failures
            sys = out.systemConfirm
            if (out.needAccess.isEmpty()) proceed() else queue = out.needAccess
        }
    }
}
