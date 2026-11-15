package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@IgnoreExtraProperties
data class PrivateUser(
    @DocumentId val id: String = "",
    val userId: String = "",
    val email: String = "",
    val displayName: String = "",
    val photoUrl: String? = null,
    val about: String = "Hey there! I am using Ding.",
    val handle: String = "",
    val readReceiptsEnabled: Boolean = true,
    val isDiscoverable: Boolean = true,
    val appLockEnabled: Boolean = false,
    val appLockPinHash: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class ChatPref(
    @DocumentId val id: String = "",
    val userId: String = "",
    val chatId: String = "",
    val isPinned: Boolean = false,
    val isMuted: Boolean = false,
    val isArchived: Boolean = false,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class DirectoryUser(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val handle: String = "",
    val about: String = "Hey there! I am using Ding.",
    val photoUrl: String? = null,
    val publicKeyBase64: String = "",
    val isDiscoverable: Boolean = true,
    val isOnline: Boolean = false,
    @ServerTimestamp val lastSeen: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class ChatRoom(
    @DocumentId val id: String = "",
    val participantIds: List<String> = emptyList(),
    val user1Id: String = "",
    val user2Id: String = "",
    val user1Name: String = "",
    val user2Name: String = "",
    val user1PhotoUrl: String? = null,
    val user2PhotoUrl: String? = null,
    val user1PublicKey: String = "",
    val user2PublicKey: String = "",
    val user1UnreadCount: Long = 0L,
    val user2UnreadCount: Long = 0L,
    val user1Typing: Boolean = false,
    val user2Typing: Boolean = false,
    val lastMessageCiphertext: String = "",
    val lastMessageIv: String = "",
    val lastMessageSenderId: String = "",
    val lastMessageStatus: String = "",
    val disappearingSeconds: Long = 0L,
    val createdBy: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun isSelfChat(): Boolean = user1Id == user2Id

    fun peerId(currentUserId: String): String =
        if (currentUserId == user1Id) user2Id else user1Id

    fun peerName(currentUserId: String): String =
        if (isSelfChat()) "${user1Name} (You)"
        else if (currentUserId == user1Id) user2Name else user1Name

    fun peerPhotoUrl(currentUserId: String): String? =
        if (currentUserId == user1Id) user2PhotoUrl else user1PhotoUrl

    fun peerPublicKey(currentUserId: String): String =
        if (currentUserId == user1Id) user2PublicKey else user1PublicKey

    fun myUnreadCount(currentUserId: String): Long =
        if (currentUserId == user1Id) user1UnreadCount else user2UnreadCount

    fun isPeerTyping(currentUserId: String): Boolean =
        if (isSelfChat()) false
        else if (currentUserId == user1Id) user2Typing else user1Typing
}

@IgnoreExtraProperties
data class ChatMessage(
    @DocumentId val id: String = "",
    val senderId: String = "",
    val participantIds: List<String> = emptyList(),
    val ciphertext: String = "",
    val iv: String = "",
    val messageType: String = "text",
    val replyToMessageId: String? = null,
    val replyToCiphertext: String? = null,
    val replyToIv: String? = null,
    val replyToSenderId: String? = null,
    val status: String = "sent",
    val reactionUser1: String = "",
    val reactionUser2: String = "",
    val starredBy: List<String> = emptyList(),
    val deletedForEveryone: Boolean = false,
    val clientSeq: Long = 0L,
    val expiresAt: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class StatusUpdate(
    @DocumentId val id: String = "",
    val authorId: String = "",
    val authorName: String = "",
    val authorPhotoUrl: String? = null,
    val content: String = "",
    val styleIndex: Long = 0L,
    val isActive: Boolean = true,
    val viewerIds: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

data class DecryptedChatSummary(
    val chat: ChatRoom,
    val peerId: String,
    val peerName: String,
    val peerPhotoUrl: String?,
    val peerPublicKey: String,
    val lastMessagePreview: String,
    val unreadCount: Int,
    val isPeerTyping: Boolean,
    val isPeerOnline: Boolean,
    val peerLastSeen: Timestamp?,
    val isPinned: Boolean,
    val isMuted: Boolean,
    val isArchived: Boolean,
    val isSelfChat: Boolean
)

data class DecryptedMessage(
    val raw: ChatMessage,
    val text: String,
    val replyPreviewText: String?,
    val isMine: Boolean,
    val isStarred: Boolean,
    val reactions: List<String>
)

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}
