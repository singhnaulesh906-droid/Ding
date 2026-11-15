package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.DecryptedChatSummary
import com.example.data.model.DecryptedMessage
import com.example.data.model.UiState
import com.example.ui.components.DingAvatar
import com.example.ui.components.MessageStatusTicks
import com.example.ui.components.SafetyIdenticonGrid
import com.example.ui.theme.LocalDingColors
import com.example.ui.theme.MonoFontFamily
import com.google.firebase.Timestamp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Locale

private val QUICK_REACTIONS = listOf("❤️", "👍", "😂", "😮", "🙏", "🔥")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatDetailScreen(
    summary: DecryptedChatSummary,
    messagesState: UiState<List<DecryptedMessage>>,
    safetyNumberGroups: List<String>,
    showBackButton: Boolean,
    onBack: () -> Unit,
    onSendMessage: (String, DecryptedMessage?) -> Unit,
    onMarkMessagesRead: (List<DecryptedMessage>) -> Unit,
    onSetTyping: (Boolean) -> Unit,
    onToggleReaction: (DecryptedMessage, String) -> Unit,
    onToggleStar: (DecryptedMessage) -> Unit,
    onDeleteForEveryone: (String) -> Unit,
    onUpdateDisappearingTimer: (Long) -> Unit,
    onShowMessage: (String) -> Unit
) {
    val dingColors = LocalDingColors.current
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()

    var draft by remember { mutableStateOf("") }
    var replyTarget by remember { mutableStateOf<DecryptedMessage?>(null) }
    var selectedMessage by remember { mutableStateOf<DecryptedMessage?>(null) }
    var showSecuritySheet by remember { mutableStateOf(false) }
    var showDisappearingSheet by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }

    // Typing indicator debounced sync
    LaunchedEffect(draft) {
        if (draft.isNotBlank()) {
            onSetTyping(true)
            delay(2500L)
            onSetTyping(false)
        } else {
            onSetTyping(false)
        }
    }

    DisposableEffect(summary.chat.id) {
        onDispose {
            onSetTyping(false)
        }
    }

    // Mark incoming messages read + scroll to latest
    LaunchedEffect(messagesState) {
        if (messagesState is UiState.Success) {
            onMarkMessagesRead(messagesState.data)
            if (messagesState.data.isNotEmpty()) {
                listState.animateScrollToItem(messagesState.data.lastIndex)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(dingColors.wallpaperBackground)
            .imePadding()
    ) {
        // Chat Top Bar
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showBackButton) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("chat_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to chats"
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.width(8.dp))
                }

                DingAvatar(
                    name = summary.peerName,
                    photoUrl = summary.peerPhotoUrl,
                    size = 42.dp,
                    isOnline = summary.isPeerOnline,
                    isSelfChat = summary.isSelfChat
                )

                Spacer(modifier = Modifier.width(10.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { showSecuritySheet = true }
                ) {
                    Text(
                        text = summary.peerName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val subtitle = when {
                        summary.isSelfChat -> "Encrypted personal space"
                        summary.isPeerTyping -> "typing…"
                        summary.isPeerOnline -> "online"
                        summary.peerLastSeen != null -> "last seen ${formatTime(summary.peerLastSeen)}"
                        else -> "end-to-end encrypted"
                    }
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (summary.isPeerTyping || summary.isPeerOnline) dingColors.onlineGreen
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }

                IconButton(
                    onClick = { showSecuritySheet = true },
                    modifier = Modifier.testTag("verify_security_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.VerifiedUser,
                        contentDescription = "Verify security code",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.testTag("chat_overflow_menu")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "Chat options"
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Verify security code") },
                            leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                showSecuritySheet = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Disappearing messages") },
                            leadingIcon = { Icon(Icons.Filled.Timer, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                showDisappearingSheet = true
                            }
                        )
                    }
                }
            }
        }

        // Message list with subtle geometric wallpaper canvas
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            val patternColor = dingColors.wallpaperPattern
            Canvas(modifier = Modifier.fillMaxSize()) {
                val step = 52.dp.toPx()
                var y = 20f
                var row = 0
                while (y < size.height) {
                    var x = if (row % 2 == 0) 20f else 46f
                    while (x < size.width) {
                        drawCircle(
                            color = patternColor.copy(alpha = 0.45f),
                            radius = 2.dp.toPx(),
                            center = Offset(x, y)
                        )
                        x += step
                    }
                    y += step
                    row++
                }
            }

            when (messagesState) {
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
                        Text(text = messagesState.message, color = MaterialTheme.colorScheme.error)
                    }
                }

                is UiState.Success -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = dingColors.securityBannerBg,
                                    modifier = Modifier
                                        .widthIn(max = 360.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { showSecuritySheet = true }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Lock,
                                            contentDescription = null,
                                            tint = dingColors.securityBannerText,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = stringResource(R.string.e2e_banner),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = dingColors.securityBannerText
                                        )
                                    }
                                }
                            }
                        }

                        if (summary.chat.disappearingSeconds > 0L) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Timer,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "Disappearing messages active (${formatTimerLabel(summary.chat.disappearingSeconds)})",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        items(messagesState.data, key = { it.raw.id }) { msg ->
                            MessageBubbleRow(
                                message = msg,
                                onLongClick = { selectedMessage = msg },
                                onClick = { selectedMessage = msg }
                            )
                        }
                    }
                }
            }
        }

        // Reply Preview + Compose Bar
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
            tonalElevation = 4.dp,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                AnimatedVisibility(visible = replyTarget != null) {
                    replyTarget?.let { target ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .height(32.dp)
                                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (target.isMine) "You" else summary.peerName,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = target.text,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(
                                    onClick = { replyTarget = null },
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Icon(Icons.Filled.Close, contentDescription = "Cancel reply")
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom
                ) {
                    TextField(
                        value = draft,
                        onValueChange = { draft = it },
                        placeholder = { Text(stringResource(R.string.msg_placeholder)) },
                        maxLines = 5,
                        shape = RoundedCornerShape(24.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("message_input")
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Surface(
                        shape = CircleShape,
                        color = if (draft.isNotBlank()) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .clickable(enabled = draft.isNotBlank()) {
                                val textToSend = draft
                                val reply = replyTarget
                                draft = ""
                                replyTarget = null
                                onSendMessage(textToSend, reply)
                            }
                            .testTag("send_message_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send encrypted message",
                                tint = if (draft.isNotBlank()) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // Message Action Bottom Sheet
    selectedMessage?.let { msg ->
        ModalBottomSheet(onDismissRequest = { selectedMessage = null }) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    QUICK_REACTIONS.forEach { emoji ->
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .clickable {
                                    onToggleReaction(msg, emoji)
                                    selectedMessage = null
                                }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(text = emoji, style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    }
                }

                ListItem(
                    headlineContent = { Text("Reply") },
                    leadingContent = { Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null) },
                    modifier = Modifier.clickable {
                        replyTarget = msg
                        selectedMessage = null
                    }
                )
                ListItem(
                    headlineContent = { Text(if (msg.isStarred) "Unstar message" else "Star message") },
                    leadingContent = {
                        Icon(
                            if (msg.isStarred) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = null
                        )
                    },
                    modifier = Modifier.clickable {
                        onToggleStar(msg)
                        selectedMessage = null
                    }
                )
                ListItem(
                    headlineContent = { Text("Copy text") },
                    leadingContent = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                    modifier = Modifier.clickable {
                        clipboard.setText(AnnotatedString(msg.text))
                        onShowMessage("Message copied")
                        selectedMessage = null
                    }
                )
                if (msg.isMine && !msg.raw.deletedForEveryone) {
                    ListItem(
                        headlineContent = {
                            Text("Delete for everyone", color = MaterialTheme.colorScheme.error)
                        },
                        leadingContent = {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        modifier = Modifier.clickable {
                            onDeleteForEveryone(msg.raw.id)
                            selectedMessage = null
                        }
                    )
                }
            }
        }
    }

    // Verify Security Code Bottom Sheet
    if (showSecuritySheet) {
        ModalBottomSheet(onDismissRequest = { showSecuritySheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Verify Security Code",
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "End-to-end encryption with ${summary.peerName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(18.dp))
                SafetyIdenticonGrid(
                    safetyGroups = safetyNumberGroups,
                    modifier = Modifier.size(132.dp)
                )
                Spacer(modifier = Modifier.height(18.dp))
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        safetyNumberGroups.chunked(4).forEach { row ->
                            Text(
                                text = row.joinToString("   "),
                                fontFamily = MonoFontFamily,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "If these 60 digits match on ${summary.peerName}'s device, your ECDH P-256 & AES-256-GCM channel is verified.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // Disappearing Messages Timer Sheet
    if (showDisappearingSheet) {
        val options = listOf(
            0L to "Off",
            86400L to "24 hours",
            604800L to "7 days",
            7776000L to "90 days"
        )
        ModalBottomSheet(onDismissRequest = { showDisappearingSheet = false }) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                Text(
                    text = "Disappearing Messages",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                )
                options.forEach { (seconds, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onUpdateDisappearingTimer(seconds)
                                showDisappearingSheet = false
                            }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = summary.chat.disappearingSeconds == seconds,
                            onClick = {
                                onUpdateDisappearingTimer(seconds)
                                showDisappearingSheet = false
                            }
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(text = label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubbleRow(
    message: DecryptedMessage,
    onLongClick: () -> Unit,
    onClick: () -> Unit
) {
    val dingColors = LocalDingColors.current
    val bubbleColor = if (message.isMine) dingColors.outgoingBubble else dingColors.incomingBubble
    val textColor = if (message.isMine) dingColors.outgoingBubbleText else dingColors.incomingBubbleText
    val bubbleShape = if (message.isMine) {
        RoundedCornerShape(topStart = 18.dp, topEnd = 4.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
    } else {
        RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.isMine) Alignment.End else Alignment.Start
    ) {
        Surface(
            color = bubbleColor,
            shape = bubbleShape,
            shadowElevation = 1.dp,
            modifier = Modifier
                .widthIn(min = 84.dp, max = 300.dp)
                .clip(bubbleShape)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick
                )
                .testTag("message_bubble_${message.raw.id}")
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (!message.replyPreviewText.isNullOrBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                    ) {
                        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(20.dp)
                                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = message.replyPreviewText,
                                style = MaterialTheme.typography.bodySmall,
                                color = textColor.copy(alpha = 0.8f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyLarge,
                    fontStyle = if (message.raw.deletedForEveryone) FontStyle.Italic else FontStyle.Normal,
                    color = if (message.raw.deletedForEveryone) textColor.copy(alpha = 0.65f) else textColor
                )

                Spacer(modifier = Modifier.height(3.dp))

                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (message.raw.expiresAt != null) {
                        Icon(
                            imageVector = Icons.Filled.Timer,
                            contentDescription = "Disappearing message",
                            tint = textColor.copy(alpha = 0.6f),
                            modifier = Modifier.size(12.dp)
                        )
                    }
                    if (message.isStarred) {
                        Icon(
                            imageVector = Icons.Filled.Star,
                            contentDescription = "Starred",
                            tint = textColor.copy(alpha = 0.65f),
                            modifier = Modifier.size(12.dp)
                        )
                    }
                    Text(
                        text = formatTime(message.raw.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor.copy(alpha = 0.65f)
                    )
                    if (message.isMine) {
                        MessageStatusTicks(status = message.raw.status, size = 15.dp)
                    }
                }
            }
        }

        if (message.reactions.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 1.dp,
                modifier = Modifier.padding(top = 2.dp, start = 6.dp, end = 6.dp)
            ) {
                Text(
                    text = message.reactions.joinToString(" "),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
    }
}

private fun formatTime(ts: Timestamp?): String {
    if (ts == null) return ""
    return SimpleDateFormat("h:mm a", Locale.getDefault()).format(ts.toDate())
}

private fun formatTimerLabel(seconds: Long): String = when (seconds) {
    86400L -> "24h"
    604800L -> "7d"
    7776000L -> "90d"
    else -> "Off"
}
