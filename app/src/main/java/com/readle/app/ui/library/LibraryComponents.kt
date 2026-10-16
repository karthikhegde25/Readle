package com.readle.app.ui.library

import android.graphics.Bitmap
import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readle.app.data.db.DocumentEntity
import com.readle.app.domain.DocFormat
import com.readle.app.domain.Formatters
import com.readle.app.domain.LibraryItem
import com.readle.app.domain.ReadStatus
import com.readle.app.ui.LocalAppContainer
import kotlin.math.abs

// Muted, dark hues so white title text always has strong contrast on a placeholder cover.
private val coverHues = listOf(
    Color(0xFF2F4B5C), Color(0xFF4A3F6B), Color(0xFF5B3A3A), Color(0xFF2E5339),
    Color(0xFF6B4B2A), Color(0xFF3B4A6B), Color(0xFF5A3D5A), Color(0xFF3A5A5A)
)

/** Real cover when one was extracted, otherwise an original typographic placeholder. */
@Composable
fun CoverImage(doc: DocumentEntity, showCovers: Boolean, modifier: Modifier = Modifier, compact: Boolean = false) {
    val covers = LocalAppContainer.current.covers
    val key = doc.coverCacheKey
    val bitmap by produceState<Bitmap?>(null, key, showCovers) {
        value = if (showCovers && key != null) covers.load(key) else null
    }
    val shape = RoundedCornerShape(6.dp)
    Box(modifier = modifier.clip(shape), contentAlignment = Alignment.Center) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            val base = coverHues[abs(doc.title.hashCode()) % coverHues.size]
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(base, base.copy(alpha = 0.82f)))),
                contentAlignment = Alignment.Center
            ) {
                if (!compact) {
                    Text(
                        text = doc.title,
                        color = Color.White,
                        fontFamily = FontFamily.Serif,
                        fontSize = 13.sp,
                        lineHeight = 16.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(10.dp)
                    )
                } else {
                    Text(
                        text = doc.title.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                        color = Color.White,
                        fontFamily = FontFamily.Serif,
                        fontSize = 20.sp
                    )
                }
            }
        }
    }
}

@Composable
fun FormatBadge(format: DocFormat?, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = Color(0xCC000000)
    ) {
        Text(
            text = format?.label ?: "?",
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun StatusMarks(item: LibraryItem) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (item.doc.isFavorite) Icon(Icons.Filled.Star, contentDescription = null, tint = Color(0xFFE0A100), modifier = Modifier.size(14.dp))
        if (!item.isAvailable) Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
        if (item.status == ReadStatus.FINISHED) Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun SelectionRing(selected: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier.then(
            if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)) else Modifier
        )
    ) {
        content()
        if (selected) {
            Icon(
                Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).background(Color.White, RoundedCornerShape(12.dp))
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DocGridItem(
    item: LibraryItem,
    selected: Boolean,
    showCovers: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val description = Formatters.describe(item)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "Select")
            .semantics(mergeDescendants = true) {
                contentDescription = description
                this.selected = selected
            }
            .padding(4.dp)
    ) {
        SelectionRing(selected) {
            Box(Modifier.fillMaxWidth().aspectRatio(0.68f)) {
                CoverImage(item.doc, showCovers, Modifier.fillMaxSize())
                FormatBadge(item.format, Modifier.align(Alignment.TopStart).padding(6.dp))
                if (item.hasProgress && item.progressFraction > 0.001f && item.status != ReadStatus.FINISHED) {
                    LinearProgressIndicator(
                        progress = { item.progressFraction },
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp),
                        trackColor = Color(0x66000000),
                        color = Color(0xFFFFC857)
                    )
                }
                Box(Modifier.align(Alignment.BottomEnd).padding(6.dp)) { StatusMarks(item) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            item.doc.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            item.doc.author ?: "Unknown author",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DocCompactRow(
    item: LibraryItem,
    selected: Boolean,
    showCovers: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val description = Formatters.describe(item)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "Select")
            .semantics(mergeDescendants = true) {
                contentDescription = description
                this.selected = selected
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SelectionRing(selected) {
            CoverImage(item.doc, showCovers, Modifier.width(40.dp).height(56.dp), compact = true)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.doc.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                Formatters.subtitle(item).ifEmpty { "Unknown author" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        StatusMarks(item)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DocDetailedRow(
    item: LibraryItem,
    selected: Boolean,
    showCovers: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val description = Formatters.describe(item)
    val context = androidx.compose.ui.platform.LocalContext.current
    val modified = remember(item.doc.lastModified) {
        if (item.doc.lastModified > 0) DateUtils.formatDateTime(context, item.doc.lastModified, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_YEAR) else null
    }
    val opened = remember(item.doc.lastOpenedAt) {
        item.doc.lastOpenedAt?.let { DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString() }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "Select")
            .semantics(mergeDescendants = true) {
                contentDescription = description
                this.selected = selected
            }
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        SelectionRing(selected) {
            Box {
                CoverImage(item.doc, showCovers, Modifier.width(64.dp).height(92.dp))
                FormatBadge(item.format, Modifier.align(Alignment.TopStart).padding(4.dp))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.doc.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(6.dp))
                StatusMarks(item)
            }
            Text(item.doc.author ?: "Unknown author", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            item.doc.series?.let {
                Text("Series: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                listOfNotNull(Formatters.fileSize(item.doc.sizeBytes).takeIf { item.doc.sizeBytes > 0 }, modified).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            when {
                !item.isAvailable -> Text("File unavailable", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                item.status == ReadStatus.FINISHED -> Text("Finished", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                item.status == ReadStatus.READING -> {
                    LinearProgressIndicator(progress = { item.progressFraction }, modifier = Modifier.fillMaxWidth().height(4.dp))
                    Text(
                        "${Formatters.percent(item.progressFraction)}% read" + (opened?.let { " · opened $it" } ?: ""),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> Text(
                    opened?.let { "Opened $it" } ?: "Not started",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
