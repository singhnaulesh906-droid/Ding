package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.StatusUpdate
import com.example.data.model.UiState
import com.example.ui.components.DingAvatar
import com.example.ui.theme.StatusBackgroundGradients
import com.google.firebase.Timestamp
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun UpdatesTab(
    statusesState: UiState<List<StatusUpdate>>,
    currentUserId: String,
    currentUserName: String,
    currentUserPhoto: String?,
    onPostStatus: (String, Long) -> Unit,
    onMarkViewed: (StatusUpdate) -> Unit,
    onDeleteStatus: (String) -> Unit
) {
    var showComposer by remember { mutableStateOf(false) }
    var viewingStatus by remember { mutableStateOf<StatusUpdate?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Updates",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Button(
                    onClick = { showComposer = true },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.testTag("post_status_button")
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("My Status")
                }
            }
        }

        when (statusesState) {
            is UiState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            is UiState.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = statusesState.message, color = MaterialTheme.colorScheme.error)
                }
            }

            is UiState.Success -> {
                val myStatuses = statusesState.data.filter { it.authorId == currentUserId }
                val peerStatuses = statusesState.data.filter { it.authorId != currentUserId }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        MyStatusHeaderCard(
                            currentUserName = currentUserName,
                            currentUserPhoto = currentUserPhoto,
                            latestStatus = myStatuses.firstOrNull(),
                            onTap = {
                                val latest = myStatuses.firstOrNull()
                                if (latest != null) viewingStatus = latest else showComposer = true
                            },
                            onAddClick = { showComposer = true }
                        )
                    }

                    if (myStatuses.isNotEmpty()) {
                        item {
                            Text(
                                text = "Your Updates (${myStatuses.size})",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                        items(myStatuses, key = { it.id }) { status ->
                            StatusRowItem(
                                status = status,
                                isSeen = true,
                                onClick = { viewingStatus = status }
                            )
                        }
                    }

                    item {
                        Text(
                            text = "Recent Updates",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }

                    if (peerStatuses.isEmpty()) {
                        item {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "No recent friend updates yet. Tap \"My Status\" to share a 24-hour update with Ding contacts.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                        }
                    } else {
                        items(peerStatuses, key = { it.id }) { status ->
                            StatusRowItem(
                                status = status,
                                isSeen = currentUserId in status.viewerIds,
                                onClick = { viewingStatus = status }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showComposer) {
        StatusComposerDialog(
            onDismiss = { showComposer = false },
            onPost = { text, styleIndex ->
                onPostStatus(text, styleIndex)
                showComposer = false
            }
        )
    }

    viewingStatus?.let { status ->
        LaunchedEffect(status.id) {
            onMarkViewed(status)
        }
        StatusViewerDialog(
            status = status,
            isMine = status.authorId == currentUserId,
            onDismiss = { viewingStatus = null },
            onDelete = {
                onDeleteStatus(status.id)
                viewingStatus = null
            }
        )
    }
}

@Composable
private fun MyStatusHeaderCard(
    currentUserName: String,
    currentUserPhoto: String?,
    latestStatus: StatusUpdate?,
    onTap: () -> Unit,
    onAddClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onTap)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                DingAvatar(name = currentUserName, photoUrl = currentUserPhoto, size = 54.dp)
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(22.dp)
                        .align(Alignment.BottomEnd)
                        .clickable(onClick = onAddClick)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "Add status",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "My Status",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = latestStatus?.content ?: "Tap to add a text status update",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun StatusRowItem(
    status: StatusUpdate,
    isSeen: Boolean,
    onClick: () -> Unit
) {
    val ringColor = if (isSeen) MaterialTheme.colorScheme.outline
    else MaterialTheme.colorScheme.primary

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .testTag("status_item_${status.id}")
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .border(2.5.dp, ringColor, CircleShape)
                    .padding(4.dp)
            ) {
                DingAvatar(
                    name = status.authorName,
                    photoUrl = status.authorPhotoUrl,
                    size = 48.dp
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = status.authorName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = formatStatusTime(status.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StatusComposerDialog(
    onDismiss: () -> Unit,
    onPost: (String, Long) -> Unit
) {
    var text by remember { mutableStateOf("") }
    var selectedStyle by remember { mutableLongStateOf(0L) }
    val gradient = StatusBackgroundGradients[selectedStyle.toInt() % StatusBackgroundGradients.size]

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Status Update") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Brush.linearGradient(gradient))
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = text.ifBlank { "Type your update…" },
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatusBackgroundGradients.forEachIndexed { idx, colors ->
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Brush.linearGradient(colors))
                                .border(
                                    width = if (selectedStyle == idx.toLong()) 3.dp else 0.dp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    shape = CircleShape
                                )
                                .clickable { selectedStyle = idx.toLong() }
                        )
                    }
                }

                OutlinedTextField(
                    value = text,
                    onValueChange = { if (it.length <= 300) text = it },
                    label = { Text("Status message") },
                    maxLines = 4,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("status_composer_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onPost(text, selectedStyle) },
                enabled = text.isNotBlank(),
                modifier = Modifier.testTag("status_publish_button")
            ) {
                Text("Post")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun StatusViewerDialog(
    status: StatusUpdate,
    isMine: Boolean,
    onDismiss: () -> Unit,
    onDelete: () -> Unit
) {
    val gradient = StatusBackgroundGradients[
        (status.styleIndex.toInt()).coerceIn(0, StatusBackgroundGradients.lastIndex)
    ]

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(gradient))
                .safeDrawingPadding()
                .padding(20.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                LinearProgressIndicator(
                    progress = { 1f },
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.3f),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DingAvatar(
                            name = status.authorName,
                            photoUrl = status.authorPhotoUrl,
                            size = 42.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = status.authorName,
                                color = Color.White,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = formatStatusTime(status.createdAt),
                                color = Color.White.copy(alpha = 0.8f),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }

                    Row {
                        if (isMine) {
                            IconButton(onClick = onDelete) {
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = "Delete status",
                                    tint = Color.White
                                )
                            }
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Close status",
                                tint = Color.White
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = status.content,
                        color = Color.White,
                        style = MaterialTheme.typography.headlineMedium,
                        textAlign = TextAlign.Center
                    )
                }

                if (isMine) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.RemoveRedEye,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${status.viewerIds.size} views",
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
    }
}

private fun formatStatusTime(ts: Timestamp?): String {
    if (ts == null) return "Just now"
    return SimpleDateFormat("h:mm a", Locale.getDefault()).format(ts.toDate())
}
