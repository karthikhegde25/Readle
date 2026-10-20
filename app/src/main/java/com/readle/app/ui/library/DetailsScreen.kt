package com.readle.app.ui.library

import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.readle.app.data.db.DocumentEntity
import com.readle.app.data.repo.FileOpResult
import com.readle.app.domain.DocFormat
import com.readle.app.domain.Formatters
import com.readle.app.domain.MetadataNormalizer
import com.readle.app.domain.ReadStatus
import com.readle.app.ui.LocalAppContainer
import com.readle.app.ui.components.BackIconButton
import com.readle.app.ui.components.ConfirmDialog
import com.readle.app.ui.components.EmptyState
import com.readle.app.ui.components.LabeledRow
import com.readle.app.ui.components.SectionHeader
import com.readle.app.ui.components.TextInputDialog
import com.readle.app.ui.components.minTouchTarget
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private enum class DetailsDialog { EDIT, RENAME, REMOVE, DELETE }

/** Everything about one book, with the file-level actions that need explicit confirmation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailsScreen(
    docId: Long,
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    onOpenAnnotations: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val runDelete = rememberDeleteFlow { message, deleted ->
        if (deleted > 0) onBack() else scope.launch { snackbar.showSnackbar(message) }
    }

    val doc by container.libraryRepository.observeDocument(docId).collectAsState(initial = null)
    val collections by container.collectionRepository.observeCollections().collectAsState(initial = emptyList())
    val memberOf by container.collectionRepository.observeCollectionIdsFor(docId).collectAsState(initial = emptyList())
    val item by remember(docId) {
        container.libraryRepository.observeItems().map { list -> list.firstOrNull { it.doc.id == docId } }
    }.collectAsState(initial = null)
    val bookmarkCount by container.readerRepository.observeBookmarkCount(docId).collectAsState(initial = 0)
    val noteCount by container.readerRepository.observeAnnotationCount(docId).collectAsState(initial = 0)

    var dialog by remember { mutableStateOf<DetailsDialog?>(null) }
    var showCollections by remember { mutableStateOf(false) }

    val relinkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            when (val r = container.libraryManager.relink(docId, uri)) {
                is FileOpResult.Success -> snackbar.showSnackbar("File relinked")
                is FileOpResult.Failure -> snackbar.showSnackbar(r.reason)
            }
        }
    }

    // Re-check availability on entry so a file that came back (or went away) is reflected honestly.
    LaunchedEffect(docId) { container.libraryManager.refreshAvailability(docId) }

    val d = doc
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = { BackIconButton(onBack) },
                title = { Text("Details") },
                actions = {
                    if (d != null) {
                        IconButton(onClick = {
                            scope.launch { container.libraryRepository.setFavorite(listOf(d.id), !d.isFavorite) }
                        }) {
                            Icon(
                                if (d.isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                                contentDescription = if (d.isFavorite) "Remove from favorites" else "Add to favorites"
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (d == null) {
            Box(Modifier.padding(padding)) {
                EmptyState(
                    icon = Icons.Filled.WarningAmber,
                    title = "Book not found",
                    message = "This book is no longer in your library.",
                    primaryLabel = "Back",
                    onPrimary = onBack
                )
            }
            return@Scaffold
        }
        val available = d.availabilityStatus == DocumentEntity.AVAILABLE
        val format = DocFormat.fromStored(d.format)
        val fraction = item?.progressFraction ?: 0f
        val status = item?.status ?: ReadStatus.UNREAD

        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            Row(Modifier.padding(16.dp)) {
                CoverImage(d, true, Modifier.width(110.dp).height(160.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(d.title, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(4.dp))
                    Text(d.author ?: "Unknown author", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    d.series?.let { Text("Series: $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Spacer(Modifier.height(8.dp))
                    FormatBadge(format)
                }
            }

            if (!available) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text(
                        "This file can't be reached. It may have been moved, renamed or deleted, or Readle lost permission to it. Your progress, bookmarks and notes are kept.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { relinkPicker.launch(PICKER_MIME_TYPES) }, modifier = Modifier.sizeIn(minHeight = minTouchTarget())) {
                        Text("Locate the file again")
                    }
                }
            } else {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onOpen(d.id) }, modifier = Modifier.sizeIn(minHeight = minTouchTarget())) {
                        Text(if (status == ReadStatus.READING) "Continue reading" else if (status == ReadStatus.FINISHED) "Read again" else "Start reading")
                    }
                    OutlinedButton(
                        onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = format?.mimeType ?: "application/octet-stream"
                                putExtra(Intent.EXTRA_STREAM, Uri.parse(d.uri))
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            runCatching { context.startActivity(Intent.createChooser(send, "Share file").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                .onFailure { scope.launch { snackbar.showSnackbar("Couldn't share this file") } }
                        },
                        modifier = Modifier.sizeIn(minHeight = minTouchTarget())
                    ) { Text("Share") }
                }
            }

            SectionHeader("Reading")
            LabeledRow("Status", status.label + if (status == ReadStatus.READING) " (${Formatters.percent(fraction)}%)" else "")
            LabeledRow("Last opened", d.lastOpenedAt?.let {
                DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
            } ?: "Never")
            Row(Modifier.padding(horizontal = 8.dp)) {
                TextButton(onClick = { onOpenAnnotations(d.id) }) { Text("$bookmarkCount bookmarks · $noteCount notes and highlights") }
            }

            SectionHeader("File")
            LabeledRow("File name", d.displayName)
            LabeledRow("Format", format?.label ?: d.format)
            LabeledRow("Size", Formatters.fileSize(d.sizeBytes))
            if (d.lastModified > 0) LabeledRow("Modified", DateUtils.formatDateTime(context, d.lastModified, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR))
            d.language?.let { LabeledRow("Language", it) }
            d.folderPath?.let { LabeledRow("Folder", it) }
            LabeledRow("Metadata", when (d.metadataStatus) {
                DocumentEntity.METADATA_OK -> "Read from the file"
                DocumentEntity.METADATA_EDITED -> "Edited by you"
                DocumentEntity.METADATA_FAILED -> "Couldn't be read; using the file name"
                DocumentEntity.METADATA_PASSWORD -> "Password protected"
                else -> "Reading…"
            })

            SectionHeader("Collections")
            if (collections.isEmpty()) {
                Text("No collections yet.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium)
            } else {
                collections.forEach { c ->
                    val inIt = c.collection.id in memberOf
                    Row(
                        Modifier.fillMaxWidth().sizeIn(minHeight = minTouchTarget()).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.Checkbox(
                            checked = inIt,
                            onCheckedChange = { checked ->
                                scope.launch {
                                    if (checked) container.collectionRepository.addDocuments(c.collection.id, listOf(d.id))
                                    else container.collectionRepository.removeDocuments(c.collection.id, listOf(d.id))
                                }
                            }
                        )
                        Text(c.collection.name, Modifier.padding(start = 8.dp))
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { dialog = DetailsDialog.EDIT }, modifier = Modifier.fillMaxWidth().sizeIn(minHeight = minTouchTarget())) {
                    Text("Edit title, author and series")
                }
                if (available) OutlinedButton(onClick = { dialog = DetailsDialog.RENAME }, modifier = Modifier.fillMaxWidth().sizeIn(minHeight = minTouchTarget())) {
                    Text("Rename file")
                }
                OutlinedButton(onClick = { dialog = DetailsDialog.REMOVE }, modifier = Modifier.fillMaxWidth().sizeIn(minHeight = minTouchTarget())) {
                    Text("Remove from library")
                }
                if (available) OutlinedButton(onClick = { dialog = DetailsDialog.DELETE }, modifier = Modifier.fillMaxWidth().sizeIn(minHeight = minTouchTarget())) {
                    Text("Delete file from device…", color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(32.dp))
        }

        when (dialog) {
            DetailsDialog.EDIT -> EditMetadataDialog(
                title = d.title, author = d.author.orEmpty(), series = d.series.orEmpty(),
                onSave = { t, a, s ->
                    dialog = null
                    scope.launch {
                        container.libraryRepository.updateMetadata(
                            d.id,
                            MetadataNormalizer.cleanTitle(t, d.displayName),
                            MetadataNormalizer.cleanField(a),
                            MetadataNormalizer.cleanField(s)
                        )
                    }
                },
                onDismiss = { dialog = null }
            )
            DetailsDialog.RENAME -> TextInputDialog(
                title = "Rename file",
                initial = d.displayName.substringBeforeLast('.', d.displayName),
                label = "File name (extension is kept)",
                confirmLabel = "Rename",
                validate = { if (it.isBlank()) "Enter a name" else if (it.any { c -> c == '/' || c == '\\' }) "Slashes aren't allowed" else null },
                onConfirm = { name ->
                    dialog = null
                    scope.launch {
                        when (val r = container.libraryManager.renameFile(d.id, name.trim())) {
                            is FileOpResult.Success -> snackbar.showSnackbar("File renamed")
                            is FileOpResult.Failure -> snackbar.showSnackbar(r.reason)
                        }
                    }
                },
                onDismiss = { dialog = null }
            )
            DetailsDialog.REMOVE -> ConfirmDialog(
                title = "Remove from library?",
                message = "Readle forgets this book along with its progress, bookmarks and notes. The file on your device is not deleted.",
                confirmLabel = "Remove",
                destructive = true,
                onConfirm = {
                    dialog = null
                    scope.launch { container.libraryManager.removeFromLibrary(listOf(d.id)); onBack() }
                },
                onDismiss = { dialog = null }
            )
            DetailsDialog.DELETE -> ConfirmDialog(
                title = "Delete file permanently?",
                message = "“${d.displayName}” will be deleted from your device and can't be recovered. Its progress, bookmarks and notes are removed too.",
                confirmLabel = "Delete file",
                destructive = true,
                onConfirm = {
                    dialog = null
                    runDelete(listOf(d.id))
                },
                onDismiss = { dialog = null }
            )
            null -> Unit
        }
    }
}

@Composable
private fun EditMetadataDialog(
    title: String,
    author: String,
    series: String,
    onSave: (String, String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var t by remember { mutableStateOf(title) }
    var a by remember { mutableStateOf(author) }
    var s by remember { mutableStateOf(series) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit details") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(t, { t = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(a, { a = it }, label = { Text("Author") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(s, { s = it }, label = { Text("Series") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Only Readle's library is changed, never the file itself.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(t, a, s) }, enabled = t.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
