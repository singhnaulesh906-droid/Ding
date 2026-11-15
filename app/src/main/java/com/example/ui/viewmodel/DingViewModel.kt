package com.example.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.ChatMessage
import com.example.data.model.ChatPref
import com.example.data.model.ChatRoom
import com.example.data.model.DecryptedChatSummary
import com.example.data.model.DecryptedMessage
import com.example.data.model.DirectoryUser
import com.example.data.model.PrivateUser
import com.example.data.model.StatusUpdate
import com.example.data.model.UiState
import com.example.data.repository.DingRepository
import com.example.security.CryptoEngine
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.spec.SecretKeySpec

@OptIn(ExperimentalCoroutinesApi::class)
class DingViewModel(
    private val repository: DingRepository,
    private val cryptoEngine: CryptoEngine,
    val currentUserId: String,
    private val currentUserEmail: String,
    private val currentUserDisplayName: String,
    private val currentUserPhotoUrl: String?
) : ViewModel() {

    private val myKeyPair: CryptoEngine.EcKeyPair = cryptoEngine.getOrCreateKeyPair(currentUserId)
    private val sharedKeyCache = ConcurrentHashMap<String, SecretKeySpec>()

    private val _selectedChatId = MutableStateFlow<String?>(null)
    val selectedChatId: StateFlow<String?> = _selectedChatId.asStateFlow()

    private val _isAppUnlocked = MutableStateFlow(false)
    val isAppUnlocked: StateFlow<Boolean> = _isAppUnlocked.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    init {
        viewModelScope.launch {
            repository.ensureUserProvisioned(
                email = currentUserEmail,
                displayName = currentUserDisplayName,
                photoUrl = currentUserPhotoUrl,
                publicKeyBase64 = myKeyPair.publicKeyBase64
            )
        }
    }

    val privateUser: StateFlow<PrivateUser?> = repository.observePrivateUser(currentUserId)
        .catch { error ->
            unwrapFirestoreErrorOrRethrow(error, "observePrivateUser")
            emit(null)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    val directoryUsers: StateFlow<List<DirectoryUser>> = repository.observeDiscoverableDirectory(currentUserId)
        .catch { error ->
            unwrapFirestoreErrorOrRethrow(error, "observeDirectory")
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    private val chatPrefs: StateFlow<Map<String, ChatPref>> = repository.observeChatPrefs(currentUserId)
        .catch { error ->
            unwrapFirestoreErrorOrRethrow(error, "observeChatPrefs")
            emit(emptyMap())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyMap())

    private val rawChats: StateFlow<UiState<List<ChatRoom>>> = repository.observeMyChats(currentUserId)
        .map<List<ChatRoom>, UiState<List<ChatRoom>>> { UiState.Success(it) }
        .catch { error ->
            val root = unwrapFirestoreErrorOrRethrow(error, "observeMyChats")
            emit(UiState.Error(root.message ?: "Failed to load chats"))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), UiState.Loading)

    val chatSummaries: StateFlow<UiState<List<DecryptedChatSummary>>> = combine(
        rawChats,
        chatPrefs,
        directoryUsers
    ) { chatsState, prefsMap, dirList ->
        when (chatsState) {
            is UiState.Loading -> UiState.Loading
            is UiState.Error -> UiState.Error(chatsState.message)
            is UiState.Success -> {
                val dirById = dirList.associateBy { it.userId }
                val summaries = chatsState.data.map { chat ->
                    val peerId = chat.peerId(currentUserId)
                    val peerDir = dirById[peerId]
                    val peerPubKey = peerDir?.publicKeyBase64?.takeIf { it.isNotBlank() }
                        ?: chat.peerPublicKey(currentUserId)
                    val preview = if (chat.lastMessageCiphertext.isNotBlank() && chat.lastMessageIv.isNotBlank()) {
                        val aes = getSharedAesKey(peerPubKey)
                        cryptoEngine.decrypt(chat.lastMessageCiphertext, chat.lastMessageIv, aes)
                    } else {
                        "End-to-end encrypted chat ready"
                    }
                    val pref = prefsMap[chat.id]
                    DecryptedChatSummary(
                        chat = chat,
                        peerId = peerId,
                        peerName = if (chat.isSelfChat()) {
                            "${peerDir?.displayName ?: chat.user1Name} (You)"
                        } else {
                            peerDir?.displayName ?: chat.peerName(currentUserId)
                        },
                        peerPhotoUrl = peerDir?.photoUrl ?: chat.peerPhotoUrl(currentUserId),
                        peerPublicKey = peerPubKey,
                        lastMessagePreview = preview,
                        unreadCount = chat.myUnreadCount(currentUserId).toInt(),
                        isPeerTyping = chat.isPeerTyping(currentUserId),
                        isPeerOnline = peerDir?.isOnline ?: false,
                        peerLastSeen = peerDir?.lastSeen,
                        isPinned = pref?.isPinned ?: false,
                        isMuted = pref?.isMuted ?: false,
                        isArchived = pref?.isArchived ?: false,
                        isSelfChat = chat.isSelfChat()
                    )
                }.sortedWith(
                    compareByDescending<DecryptedChatSummary> { it.isPinned }
                        .thenByDescending { it.chat.updatedAt ?: it.chat.createdAt ?: Timestamp(0, 0) }
                )
                UiState.Success(summaries)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), UiState.Loading)

    val activeChatSummary: StateFlow<DecryptedChatSummary?> = combine(
        chatSummaries,
        _selectedChatId
    ) { state, chatId ->
        if (chatId == null || state !is UiState.Success) null
        else state.data.firstOrNull { it.chat.id == chatId }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    val activeMessages: StateFlow<UiState<List<DecryptedMessage>>> = _selectedChatId
        .flatMapLatest { chatId ->
            if (chatId.isNullOrBlank()) {
                flowOf(UiState.Success(emptyList()))
            } else {
                combine(
                    repository.observeMessages(chatId, currentUserId),
                    activeChatSummary
                ) { msgs, summary ->
                    val peerPubKey = summary?.peerPublicKey.orEmpty()
                    val aesKey = if (peerPubKey.isNotBlank()) getSharedAesKey(peerPubKey) else null
                    msgs.map { msg ->
                        val text = if (msg.deletedForEveryone) {
                            "This message was deleted"
                        } else if (aesKey != null) {
                            cryptoEngine.decrypt(msg.ciphertext, msg.iv, aesKey)
                        } else {
                            "Decrypting..."
                        }
                        val replyText = if (
                            !msg.replyToCiphertext.isNullOrBlank() &&
                            !msg.replyToIv.isNullOrBlank() &&
                            aesKey != null
                        ) {
                            cryptoEngine.decrypt(msg.replyToCiphertext, msg.replyToIv, aesKey)
                        } else {
                            null
                        }
                        val reactions = listOf(msg.reactionUser1, msg.reactionUser2)
                            .filter { it.isNotBlank() }
                        DecryptedMessage(
                            raw = msg,
                            text = text,
                            replyPreviewText = replyText,
                            isMine = msg.senderId == currentUserId,
                            isStarred = currentUserId in msg.starredBy,
                            reactions = reactions
                        )
                    }
                }.map<List<DecryptedMessage>, UiState<List<DecryptedMessage>>> {
                    UiState.Success(it)
                }.catch { error ->
                    val root = unwrapFirestoreErrorOrRethrow(error, "observeMessages")
                    emit(UiState.Error(root.message ?: "Failed to load messages"))
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), UiState.Loading)

    val statusUpdates: StateFlow<UiState<List<StatusUpdate>>> = repository.observeStatusUpdates(currentUserId)
        .map<List<StatusUpdate>, UiState<List<StatusUpdate>>> { UiState.Success(it) }
        .catch { error ->
            val root = unwrapFirestoreErrorOrRethrow(error, "observeStatusUpdates")
            emit(UiState.Error(root.message ?: "Failed to load status updates"))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), UiState.Loading)

    fun selectChat(chatId: String?) {
        _selectedChatId.value = chatId
    }

    fun clearToast() {
        _toastMessage.value = null
    }

    fun showToast(message: String) {
        _toastMessage.value = message
    }

    fun setOnlineStatus(isOnline: Boolean) {
        viewModelScope.launch {
            repository.setOnlineStatus(isOnline)
        }
    }

    fun openChatWithPeer(peer: DirectoryUser, onReady: (String) -> Unit) {
        viewModelScope.launch {
            val me = DirectoryUser(
                userId = currentUserId,
                displayName = privateUser.value?.displayName ?: currentUserDisplayName,
                handle = privateUser.value?.handle ?: "@ding",
                about = privateUser.value?.about ?: "",
                photoUrl = privateUser.value?.photoUrl ?: currentUserPhotoUrl,
                publicKeyBase64 = myKeyPair.publicKeyBase64
            )
            repository.getOrCreateChat(me, peer)
                .onSuccess { chatId ->
                    _selectedChatId.value = chatId
                    onReady(chatId)
                }
                .onFailure { e ->
                    _toastMessage.value = e.localizedMessage ?: "Could not open chat"
                }
        }
    }

    fun openSavedMessages(onReady: (String) -> Unit) {
        val me = DirectoryUser(
            userId = currentUserId,
            displayName = privateUser.value?.displayName ?: currentUserDisplayName,
            handle = privateUser.value?.handle ?: "@ding",
            about = privateUser.value?.about ?: "",
            photoUrl = privateUser.value?.photoUrl ?: currentUserPhotoUrl,
            publicKeyBase64 = myKeyPair.publicKeyBase64
        )
        openChatWithPeer(me, onReady)
    }

    fun sendMessage(
        summary: DecryptedChatSummary,
        text: String,
        replyTo: DecryptedMessage?
    ) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val aes = getSharedAesKey(summary.peerPublicKey)
            repository.sendMessage(
                chat = summary.chat,
                plaintext = trimmed,
                sharedAesKey = aes,
                replyTo = replyTo
            ).onFailure { e ->
                _toastMessage.value = e.localizedMessage ?: "Failed to send message"
            }
        }
    }

    fun markMessagesRead(summary: DecryptedChatSummary, messages: List<DecryptedMessage>) {
        val readReceipts = privateUser.value?.readReceiptsEnabled ?: true
        viewModelScope.launch {
            repository.markMessagesRead(
                chat = summary.chat,
                messages = messages.map { it.raw },
                readReceiptsEnabled = readReceipts
            )
        }
    }

    fun setTyping(chat: ChatRoom, isTyping: Boolean) {
        viewModelScope.launch {
            repository.setTyping(chat, isTyping)
        }
    }

    fun toggleReaction(chat: ChatRoom, message: ChatMessage, emoji: String) {
        viewModelScope.launch {
            repository.toggleReaction(chat, message, emoji)
        }
    }

    fun toggleStarMessage(chatId: String, message: ChatMessage) {
        viewModelScope.launch {
            repository.toggleStarMessage(chatId, message)
        }
    }

    fun deleteMessageForEveryone(summary: DecryptedChatSummary, messageId: String) {
        viewModelScope.launch {
            val aes = getSharedAesKey(summary.peerPublicKey)
            repository.deleteMessageForEveryone(summary.chat, messageId, aes)
        }
    }

    fun updateDisappearingTimer(chatId: String, seconds: Long) {
        viewModelScope.launch {
            repository.updateDisappearingTimer(chatId, seconds)
                .onSuccess {
                    val label = when (seconds) {
                        86400L -> "24 hours"
                        604800L -> "7 days"
                        7776000L -> "90 days"
                        else -> "Off"
                    }
                    _toastMessage.value = "Disappearing messages: $label"
                }
        }
    }

    fun togglePinChat(summary: DecryptedChatSummary) {
        viewModelScope.launch {
            repository.updateChatPref(
                chatId = summary.chat.id,
                isPinned = !summary.isPinned,
                isMuted = summary.isMuted,
                isArchived = summary.isArchived
            )
        }
    }

    fun toggleMuteChat(summary: DecryptedChatSummary) {
        viewModelScope.launch {
            repository.updateChatPref(
                chatId = summary.chat.id,
                isPinned = summary.isPinned,
                isMuted = !summary.isMuted,
                isArchived = summary.isArchived
            )
        }
    }

    fun toggleArchiveChat(summary: DecryptedChatSummary) {
        viewModelScope.launch {
            repository.updateChatPref(
                chatId = summary.chat.id,
                isPinned = summary.isPinned,
                isMuted = summary.isMuted,
                isArchived = !summary.isArchived
            )
        }
    }

    fun updateProfileAndPrivacy(
        displayName: String,
        about: String,
        handle: String,
        readReceiptsEnabled: Boolean,
        isDiscoverable: Boolean,
        appLockEnabled: Boolean,
        newPin: String? = null
    ) {
        val currentHash = privateUser.value?.appLockPinHash.orEmpty()
        val nextHash = when {
            !appLockEnabled -> ""
            !newPin.isNullOrBlank() -> cryptoEngine.hashPin(newPin)
            else -> currentHash
        }
        viewModelScope.launch {
            repository.updateProfileAndPrivacy(
                displayName = displayName,
                about = about,
                handle = handle,
                readReceiptsEnabled = readReceiptsEnabled,
                isDiscoverable = isDiscoverable,
                appLockEnabled = appLockEnabled,
                appLockPinHash = nextHash
            ).onSuccess {
                _toastMessage.value = "Privacy & profile updated"
            }.onFailure { e ->
                _toastMessage.value = e.localizedMessage ?: "Failed to update settings"
            }
        }
    }

    fun postStatusUpdate(content: String, styleIndex: Long) {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return
        val name = privateUser.value?.displayName ?: currentUserDisplayName
        val photo = privateUser.value?.photoUrl ?: currentUserPhotoUrl
        viewModelScope.launch {
            repository.postStatusUpdate(name, photo, trimmed, styleIndex)
                .onSuccess { _toastMessage.value = "Status posted" }
                .onFailure { e -> _toastMessage.value = e.localizedMessage ?: "Failed to post status" }
        }
    }

    fun markStatusViewed(status: StatusUpdate) {
        viewModelScope.launch {
            repository.markStatusViewed(status)
        }
    }

    fun deleteStatus(statusId: String) {
        viewModelScope.launch {
            repository.deleteStatusUpdate(statusId)
                .onSuccess { _toastMessage.value = "Status deleted" }
        }
    }

    fun verifyPinAndUnlock(pin: String): Boolean {
        val expected = privateUser.value?.appLockPinHash.orEmpty()
        if (expected.isBlank()) {
            _isAppUnlocked.value = true
            return true
        }
        val matches = cryptoEngine.hashPin(pin) == expected
        if (matches) {
            _isAppUnlocked.value = true
        }
        return matches
    }

    fun computeSafetyNumber(peerPublicKey: String): List<String> {
        if (peerPublicKey.isBlank()) return emptyList()
        return cryptoEngine.computeSafetyNumber(myKeyPair.publicKeyBase64, peerPublicKey)
    }

    fun myKeyFingerprint(): String = cryptoEngine.shortFingerprint(myKeyPair.publicKeyBase64)

    private fun getSharedAesKey(peerPublicKey: String): SecretKeySpec {
        return sharedKeyCache.getOrPut(peerPublicKey) {
            cryptoEngine.deriveSharedAesKey(myKeyPair.privateKeyBase64, peerPublicKey)
        }
    }

    private fun unwrapFirestoreErrorOrRethrow(error: Throwable, opName: String): Throwable {
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        val root = firestoreError ?: error
        Log.w(TAG, "Error in $opName", root)
        return root
    }

    private companion object {
        const val TAG = "DingViewModel"
        const val STOP_TIMEOUT_MS = 5000L
    }
}
