package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.DonutLarge
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.DonutLarge
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.data.model.UiState
import com.example.data.repository.DingRepository
import com.example.security.CryptoEngine
import com.example.ui.auth.AuthScreen
import com.example.ui.auth.attemptAutoSignIn
import com.example.ui.auth.signOutUser
import com.example.ui.screens.AppLockScreen
import com.example.ui.screens.ChatDetailScreen
import com.example.ui.screens.ChatsTab
import com.example.ui.screens.NewChatScreen
import com.example.ui.screens.SecurityTab
import com.example.ui.screens.SettingsTab
import com.example.ui.screens.UpdatesTab
import com.example.ui.theme.DingTheme
import com.example.ui.viewmodel.DingViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DingTheme {
                DingRootApp()
            }
        }
    }
}

private fun FirebaseAuth.authStateFlow(): Flow<FirebaseUser?> = callbackFlow {
    val listener = FirebaseAuth.AuthStateListener { auth ->
        trySend(auth.currentUser)
    }
    addAuthStateListener(listener)
    awaitClose { removeAuthStateListener(listener) }
}

private enum class HomeTab(
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    CHATS(R.string.tab_chats, Icons.AutoMirrored.Filled.Chat, Icons.AutoMirrored.Outlined.Chat),
    UPDATES(R.string.tab_updates, Icons.Filled.DonutLarge, Icons.Outlined.DonutLarge),
    SECURITY(R.string.tab_security, Icons.Filled.Shield, Icons.Outlined.Shield),
    SETTINGS(R.string.tab_settings, Icons.Filled.Settings, Icons.Outlined.Settings)
}

@Composable
fun DingRootApp(auth: FirebaseAuth = Firebase.auth) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    val currentUser by auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)

    LaunchedEffect(Unit) {
        if (auth.currentUser == null) {
            attemptAutoSignIn(
                context = context,
                credentialManager = credentialManager,
                onAuthSuccess = {},
                onUnauthenticated = {},
                scope = scope
            )
        }
    }

    val user = currentUser
    if (user == null) {
        AuthScreen(onAuthSuccess = {})
    } else {
        androidx.compose.runtime.key(user.uid) {
            DingAuthenticatedShell(
                user = user,
                onSignOut = {
                    signOutUser(
                        credentialManager = credentialManager,
                        scope = scope
                    )
                }
            )
        }
    }
}

@Composable
private fun DingAuthenticatedShell(
    user: FirebaseUser,
    onSignOut: () -> Unit
) {
    val uid = user.uid
    val viewModel: DingViewModel = viewModel(
        key = uid,
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY])
                val databaseId = app.getString(R.string.firestore_database_id)
                val db = FirebaseFirestore.getInstance(databaseId)
                val crypto = CryptoEngine(app.applicationContext)
                val repo = DingRepository(db, crypto, Firebase.auth)
                DingViewModel(
                    repository = repo,
                    cryptoEngine = crypto,
                    currentUserId = uid,
                    currentUserEmail = user.email.orEmpty(),
                    currentUserDisplayName = user.displayName ?: "Ding User",
                    currentUserPhotoUrl = user.photoUrl?.toString()
                )
            }
        }
    )

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, uid) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.setOnlineStatus(true)
                Lifecycle.Event.ON_PAUSE -> viewModel.setOnlineStatus(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            viewModel.setOnlineStatus(false)
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val privateUser by viewModel.privateUser.collectAsStateWithLifecycle()
    val isAppUnlocked by viewModel.isAppUnlocked.collectAsStateWithLifecycle()
    val chatsState by viewModel.chatSummaries.collectAsStateWithLifecycle()
    val directoryUsers by viewModel.directoryUsers.collectAsStateWithLifecycle()
    val selectedChatId by viewModel.selectedChatId.collectAsStateWithLifecycle()
    val activeChatSummary by viewModel.activeChatSummary.collectAsStateWithLifecycle()
    val activeMessages by viewModel.activeMessages.collectAsStateWithLifecycle()
    val statusUpdates by viewModel.statusUpdates.collectAsStateWithLifecycle()
    val toastMessage by viewModel.toastMessage.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(toastMessage) {
        val msg = toastMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearToast()
        }
    }

    val requiresLock = privateUser?.appLockEnabled == true && !isAppUnlocked
    if (requiresLock) {
        AppLockScreen(
            onVerifyPin = { pin -> viewModel.verifyPinAndUnlock(pin) },
            onSignOutRequested = onSignOut
        )
        return
    }

    var currentTab by remember { mutableStateOf(HomeTab.CHATS) }
    var showNewChat by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isExpanded = maxWidth >= 700.dp

        if (showNewChat) {
            BackHandler { showNewChat = false }
            NewChatScreen(
                currentUserId = uid,
                directoryUsers = directoryUsers,
                onBack = { showNewChat = false },
                onSelectPeer = { peer ->
                    viewModel.openChatWithPeer(peer) {
                        showNewChat = false
                        currentTab = HomeTab.CHATS
                    }
                },
                onOpenSavedMessages = {
                    viewModel.openSavedMessages {
                        showNewChat = false
                        currentTab = HomeTab.CHATS
                    }
                },
                onShowMessage = { viewModel.showToast(it) }
            )
            return@BoxWithConstraints
        }

        if (!isExpanded && activeChatSummary != null) {
            val summary = activeChatSummary!!
            BackHandler { viewModel.selectChat(null) }
            ChatDetailScreen(
                summary = summary,
                messagesState = activeMessages,
                safetyNumberGroups = viewModel.computeSafetyNumber(summary.peerPublicKey),
                showBackButton = true,
                onBack = { viewModel.selectChat(null) },
                onSendMessage = { text, reply -> viewModel.sendMessage(summary, text, reply) },
                onMarkMessagesRead = { msgs -> viewModel.markMessagesRead(summary, msgs) },
                onSetTyping = { typing -> viewModel.setTyping(summary.chat, typing) },
                onToggleReaction = { msg, emoji -> viewModel.toggleReaction(summary.chat, msg.raw, emoji) },
                onToggleStar = { msg -> viewModel.toggleStarMessage(summary.chat.id, msg.raw) },
                onDeleteForEveryone = { msgId -> viewModel.deleteMessageForEveryone(summary, msgId) },
                onUpdateDisappearingTimer = { sec -> viewModel.updateDisappearingTimer(summary.chat.id, sec) },
                onShowMessage = { viewModel.showToast(it) }
            )
            return@BoxWithConstraints
        }

        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (!isExpanded) {
                    NavigationBar(
                        modifier = Modifier.navigationBarsPadding(),
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        HomeTab.entries.forEach { tab ->
                            val selected = currentTab == tab
                            NavigationBarItem(
                                selected = selected,
                                onClick = { currentTab = tab },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = stringResource(tab.labelRes)
                                    )
                                },
                                label = { Text(stringResource(tab.labelRes)) },
                                modifier = Modifier.testTag("nav_${tab.name.lowercase()}")
                            )
                        }
                    }
                }
            },
            floatingActionButton = {
                if (currentTab == HomeTab.CHATS) {
                    FloatingActionButton(
                        onClick = { showNewChat = true },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.testTag("new_chat_fab")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AddComment,
                            contentDescription = stringResource(R.string.action_new_chat)
                        )
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (isExpanded) {
                    NavigationRail(
                        modifier = Modifier
                            .fillMaxHeight()
                            .statusBarsPadding()
                            .navigationBarsPadding(),
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        HomeTab.entries.forEach { tab ->
                            val selected = currentTab == tab
                            NavigationRailItem(
                                selected = selected,
                                onClick = { currentTab = tab },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = stringResource(tab.labelRes)
                                    )
                                },
                                label = { Text(stringResource(tab.labelRes)) },
                                modifier = Modifier.testTag("rail_${tab.name.lowercase()}")
                            )
                        }
                    }
                    VerticalDivider()
                }

                val listWeight = if (isExpanded && currentTab == HomeTab.CHATS) 0.42f else 1f
                Box(
                    modifier = Modifier
                        .weight(listWeight)
                        .fillMaxHeight()
                        .statusBarsPadding()
                ) {
                    when (currentTab) {
                        HomeTab.CHATS -> ChatsTab(
                            chatsState = chatsState,
                            currentUserId = uid,
                            selectedChatId = selectedChatId,
                            onOpenChat = { chatId -> viewModel.selectChat(chatId) },
                            onOpenNewChat = { showNewChat = true },
                            onOpenSavedMessages = { viewModel.openSavedMessages {} },
                            onTogglePin = { viewModel.togglePinChat(it) },
                            onToggleMute = { viewModel.toggleMuteChat(it) },
                            onToggleArchive = { viewModel.toggleArchiveChat(it) }
                        )

                        HomeTab.UPDATES -> UpdatesTab(
                            statusesState = statusUpdates,
                            currentUserId = uid,
                            currentUserName = privateUser?.displayName ?: user.displayName ?: "Ding User",
                            currentUserPhoto = privateUser?.photoUrl ?: user.photoUrl?.toString(),
                            onPostStatus = { text, style -> viewModel.postStatusUpdate(text, style) },
                            onMarkViewed = { viewModel.markStatusViewed(it) },
                            onDeleteStatus = { viewModel.deleteStatus(it) }
                        )

                        HomeTab.SECURITY -> SecurityTab(
                            privateUser = privateUser,
                            keyFingerprint = viewModel.myKeyFingerprint(),
                            activeChatsCount = (chatsState as? UiState.Success)?.data?.size ?: 0,
                            onOpenSavedMessages = {
                                viewModel.openSavedMessages {
                                    currentTab = HomeTab.CHATS
                                }
                            },
                            onGoToSettings = { currentTab = HomeTab.SETTINGS }
                        )

                        HomeTab.SETTINGS -> SettingsTab(
                            privateUser = privateUser,
                            fallbackName = user.displayName ?: "Ding User",
                            fallbackEmail = user.email.orEmpty(),
                            fallbackPhoto = user.photoUrl?.toString(),
                            onSaveSettings = { name, about, handle, receipts, discoverable, lock, pin ->
                                viewModel.updateProfileAndPrivacy(
                                    name, about, handle, receipts, discoverable, lock, pin
                                )
                            },
                            onSignOut = onSignOut
                        )
                    }
                }

                if (isExpanded && currentTab == HomeTab.CHATS) {
                    VerticalDivider()
                    Box(
                        modifier = Modifier
                            .weight(0.58f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.Center
                    ) {
                        val summary = activeChatSummary
                        if (summary != null) {
                            ChatDetailScreen(
                                summary = summary,
                                messagesState = activeMessages,
                                safetyNumberGroups = viewModel.computeSafetyNumber(summary.peerPublicKey),
                                showBackButton = false,
                                onBack = { viewModel.selectChat(null) },
                                onSendMessage = { text, reply -> viewModel.sendMessage(summary, text, reply) },
                                onMarkMessagesRead = { msgs -> viewModel.markMessagesRead(summary, msgs) },
                                onSetTyping = { typing -> viewModel.setTyping(summary.chat, typing) },
                                onToggleReaction = { msg, emoji -> viewModel.toggleReaction(summary.chat, msg.raw, emoji) },
                                onToggleStar = { msg -> viewModel.toggleStarMessage(summary.chat.id, msg.raw) },
                                onDeleteForEveryone = { msgId -> viewModel.deleteMessageForEveryone(summary, msgId) },
                                onUpdateDisappearingTimer = { sec -> viewModel.updateDisappearingTimer(summary.chat.id, sec) },
                                onShowMessage = { viewModel.showToast(it) }
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Select an encrypted conversation to view messages",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
