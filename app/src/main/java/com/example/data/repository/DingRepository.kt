package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.model.ChatMessage
import com.example.data.model.ChatPref
import com.example.data.model.ChatRoom
import com.example.data.model.DecryptedMessage
import com.example.data.model.DirectoryUser
import com.example.data.model.PrivateUser
import com.example.data.model.StatusUpdate
import com.example.data.remote.OperationType
import com.example.data.remote.handleFirestoreError
import com.example.security.CryptoEngine
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.Firebase
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.Date
import javax.crypto.spec.SecretKeySpec

class DingRepository(
    private val db: FirebaseFirestore,
    private val cryptoEngine: CryptoEngine,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        db = FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        ),
        cryptoEngine = CryptoEngine(context.applicationContext),
        auth = Firebase.auth
    )

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Ding.")
    }

    private fun <T> Flow<T>.handleStreamError(uid: String, op: OperationType, path: String): Flow<T> =
        this.catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            val exceptionToLog = firestoreError ?: (error as? Exception)
            if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                handleFirestoreError(exceptionToLog, op, path, auth)
            }
            throw error
        }

    suspend fun ensureUserProvisioned(
        email: String,
        displayName: String,
        photoUrl: String?,
        publicKeyBase64: String
    ): Result<Unit> {
        val uid = requireUserId()
        val safeName = displayName.trim().ifBlank { "Ding User" }.take(60)
        val safeHandle = "@" + safeName.lowercase()
            .replace(Regex("[^a-z0-9]"), "")
            .ifBlank { "dinguser" }
            .take(24) + "_" + uid.take(4).lowercase()

        val userRef = db.collection("users").document(uid)
        val dirRef = db.collection("directory").document(uid)

        return try {
            val userSnap = userRef.get().await()
            if (!userSnap.exists()) {
                val privatePayload = mutableMapOf<String, Any>(
                    "userId" to uid,
                    "email" to email.take(120),
                    "displayName" to safeName,
                    "about" to "Hey there! I am using Ding.",
                    "handle" to safeHandle,
                    "readReceiptsEnabled" to true,
                    "isDiscoverable" to true,
                    "appLockEnabled" to false,
                    "appLockPinHash" to "",
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (!photoUrl.isNullOrBlank()) {
                    privatePayload["photoUrl"] = photoUrl.take(500)
                }
                userRef.set(privatePayload).await()
            } else {
                val updates = mutableMapOf<String, Any>(
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (!photoUrl.isNullOrBlank()) {
                    updates["photoUrl"] = photoUrl.take(500)
                }
                userRef.update(updates).await()
            }

            val dirSnap = dirRef.get().await()
            if (!dirSnap.exists()) {
                val dirPayload = mutableMapOf<String, Any>(
                    "userId" to uid,
                    "displayName" to safeName,
                    "handle" to safeHandle,
                    "about" to "Hey there! I am using Ding.",
                    "publicKeyBase64" to publicKeyBase64,
                    "isDiscoverable" to true,
                    "isOnline" to true,
                    "lastSeen" to FieldValue.serverTimestamp(),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (!photoUrl.isNullOrBlank()) {
                    dirPayload["photoUrl"] = photoUrl.take(500)
                }
                dirRef.set(dirPayload).await()
            } else {
                val dirUpdates = mutableMapOf<String, Any>(
                    "publicKeyBase64" to publicKeyBase64,
                    "isOnline" to true,
                    "lastSeen" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (!photoUrl.isNullOrBlank()) {
                    dirUpdates["photoUrl"] = photoUrl.take(500)
                }
                dirRef.update(dirUpdates).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, "users/$uid", auth)
            Result.failure(e)
        }
    }

    suspend fun setOnlineStatus(isOnline: Boolean) {
        val uid = auth.currentUser?.uid ?: return
        val dirRef = db.collection("directory").document(uid)
        try {
            dirRef.update(
                mapOf(
                    "isOnline" to isOnline,
                    "lastSeen" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, dirRef.path, auth)
        }
    }

    fun observePrivateUser(uid: String): Flow<PrivateUser?> = flow {
        val path = "users/$uid"
        emitAll(
            db.collection("users").document(uid)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObject(
                        PrivateUser::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .handleStreamError(uid, OperationType.GET, path)
        )
    }

    fun observeDiscoverableDirectory(uid: String): Flow<List<DirectoryUser>> = flow {
        val path = "directory"
        emitAll(
            db.collection("directory")
                .whereEqualTo("isDiscoverable", true)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        DirectoryUser::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .handleStreamError(uid, OperationType.LIST, path)
        )
    }

    fun observeDirectoryUser(uid: String, targetUserId: String): Flow<DirectoryUser?> = flow {
        val path = "directory/$targetUserId"
        emitAll(
            db.collection("directory").document(targetUserId)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObject(
                        DirectoryUser::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .handleStreamError(uid, OperationType.GET, path)
        )
    }

    fun observeMyChats(uid: String): Flow<List<ChatRoom>> = flow {
        val path = "chats"
        emitAll(
            db.collection("chats")
                .whereArrayContains("participantIds", uid)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        ChatRoom::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.updatedAt ?: it.createdAt ?: Timestamp(0, 0) }
                }
                .handleStreamError(uid, OperationType.LIST, path)
        )
    }

    fun observeChatPrefs(uid: String): Flow<Map<String, ChatPref>> = flow {
        val path = "users/$uid/chatPrefs"
        emitAll(
            db.collection("users").document(uid).collection("chatPrefs")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        ChatPref::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).associateBy { it.chatId }
                }
                .handleStreamError(uid, OperationType.LIST, path)
        )
    }

    fun observeMessages(chatId: String, uid: String): Flow<List<ChatMessage>> = flow {
        val path = "chats/$chatId/messages"
        emitAll(
            db.collection("chats").document(chatId).collection("messages")
                .whereArrayContains("participantIds", uid)
                .snapshots()
                .map { snapshot ->
                    val now = Timestamp.now()
                    snapshot.toObjects(
                        ChatMessage::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                        .filter { msg -> msg.expiresAt == null || msg.expiresAt.seconds > now.seconds }
                        .sortedWith(
                            compareBy<ChatMessage> { it.createdAt ?: Timestamp(0, 0) }
                                .thenBy { it.clientSeq }
                        )
                }
                .handleStreamError(uid, OperationType.LIST, path)
        )
    }

    fun observeStatusUpdates(uid: String): Flow<List<StatusUpdate>> = flow {
        val path = "statusUpdates"
        emitAll(
            db.collection("statusUpdates")
                .whereEqualTo("isActive", true)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        StatusUpdate::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
                }
                .handleStreamError(uid, OperationType.LIST, path)
        )
    }

    suspend fun getChatById(chatId: String): Result<ChatRoom?> {
        requireUserId()
        val ref = db.collection("chats").document(chatId)
        return try {
            val snap = ref.get().await()
            val room = snap.toObject(
                ChatRoom::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            )
            Result.success(room)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, ref.path, auth)
            Result.failure(e)
        }
    }

    suspend fun getOrCreateChat(
        me: DirectoryUser,
        peer: DirectoryUser
    ): Result<String> {
        val uid = requireUserId()
        val isSelf = me.userId == peer.userId
        val sorted = if (isSelf) listOf(me, me) else listOf(me, peer).sortedBy { it.userId }
        val u1 = sorted[0]
        val u2 = sorted[1]
        val chatId = if (isSelf) "self_${u1.userId}" else "${u1.userId}_${u2.userId}"
        val chatRef = db.collection("chats").document(chatId)

        return try {
            val existing = db.collection("chats")
                .whereArrayContains("participantIds", uid)
                .get()
                .await()
                .documents
                .any { it.id == chatId }
            if (existing) {
                // Keep participant public keys & names fresh
                chatRef.update(
                    mapOf(
                        "user1Name" to u1.displayName.take(60),
                        "user2Name" to u2.displayName.take(60),
                        "user1PublicKey" to u1.publicKeyBase64,
                        "user2PublicKey" to u2.publicKeyBase64,
                        "updatedAt" to FieldValue.serverTimestamp()
                    ).let { base ->
                        val m = base.toMutableMap()
                        u1.photoUrl?.takeIf { it.isNotBlank() }?.let { m["user1PhotoUrl"] = it.take(500) }
                        u2.photoUrl?.takeIf { it.isNotBlank() }?.let { m["user2PhotoUrl"] = it.take(500) }
                        m
                    }
                ).await()
                return Result.success(chatId)
            }

            val payload = mutableMapOf<String, Any>(
                "participantIds" to listOf(u1.userId, u2.userId),
                "user1Id" to u1.userId,
                "user2Id" to u2.userId,
                "user1Name" to u1.displayName.take(60),
                "user2Name" to u2.displayName.take(60),
                "user1PublicKey" to u1.publicKeyBase64,
                "user2PublicKey" to u2.publicKeyBase64,
                "user1UnreadCount" to 0L,
                "user2UnreadCount" to 0L,
                "user1Typing" to false,
                "user2Typing" to false,
                "lastMessageCiphertext" to "",
                "lastMessageIv" to "",
                "lastMessageSenderId" to "",
                "lastMessageStatus" to "",
                "disappearingSeconds" to 0L,
                "createdBy" to uid,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            u1.photoUrl?.takeIf { it.isNotBlank() }?.let { payload["user1PhotoUrl"] = it.take(500) }
            u2.photoUrl?.takeIf { it.isNotBlank() }?.let { payload["user2PhotoUrl"] = it.take(500) }

            chatRef.set(payload).await()
            Result.success(chatId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, chatRef.path, auth)
            Result.failure(e)
        }
    }

    suspend fun sendMessage(
        chat: ChatRoom,
        plaintext: String,
        sharedAesKey: SecretKeySpec,
        replyTo: DecryptedMessage? = null,
        clientSeq: Long = System.currentTimeMillis()
    ): Result<String> {
        val uid = requireUserId()
        val trimmed = plaintext.trim()
        require(trimmed.isNotEmpty()) { "Message cannot be blank" }

        val chatRef = db.collection("chats").document(chat.id)
        val msgRef = chatRef.collection("messages").document()

        return try {
            val encrypted = cryptoEngine.encrypt(trimmed, sharedAesKey)
            val msgPayload = mutableMapOf<String, Any>(
                "senderId" to uid,
                "participantIds" to chat.participantIds,
                "ciphertext" to encrypted.ciphertextBase64,
                "iv" to encrypted.ivBase64,
                "messageType" to "text",
                "status" to if (chat.isSelfChat()) "read" else "sent",
                "reactionUser1" to "",
                "reactionUser2" to "",
                "starredBy" to emptyList<String>(),
                "deletedForEveryone" to false,
                "clientSeq" to clientSeq,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )

            if (replyTo != null) {
                val encReply = cryptoEngine.encrypt(replyTo.text.take(160), sharedAesKey)
                msgPayload["replyToMessageId"] = replyTo.raw.id
                msgPayload["replyToCiphertext"] = encReply.ciphertextBase64
                msgPayload["replyToIv"] = encReply.ivBase64
                msgPayload["replyToSenderId"] = replyTo.raw.senderId
            }

            if (chat.disappearingSeconds > 0L) {
                val expireDate = Date(System.currentTimeMillis() + chat.disappearingSeconds * 1000L)
                msgPayload["expiresAt"] = Timestamp(expireDate)
            }

            val chatUpdate = mutableMapOf<String, Any>(
                "lastMessageCiphertext" to encrypted.ciphertextBase64.take(4096),
                "lastMessageIv" to encrypted.ivBase64,
                "lastMessageSenderId" to uid,
                "lastMessageStatus" to if (chat.isSelfChat()) "read" else "sent",
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (!chat.isSelfChat()) {
                if (uid == chat.user1Id) {
                    chatUpdate["user1Typing"] = false
                    chatUpdate["user2UnreadCount"] = chat.user2UnreadCount + 1L
                } else {
                    chatUpdate["user2Typing"] = false
                    chatUpdate["user1UnreadCount"] = chat.user1UnreadCount + 1L
                }
            }

            val batch = db.batch()
            batch.set(msgRef, msgPayload)
            batch.update(chatRef, chatUpdate)
            batch.commit().await()
            Result.success(msgRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, msgRef.path, auth)
            Result.failure(e)
        }
    }

    suspend fun markMessagesRead(
        chat: ChatRoom,
        messages: List<ChatMessage>,
        readReceiptsEnabled: Boolean
    ) {
        val uid = auth.currentUser?.uid ?: return
        val targetStatus = if (readReceiptsEnabled) "read" else "delivered"
        val unreadIncoming = messages.filter {
            it.senderId != uid && it.status != "read" && !(targetStatus == "delivered" && it.status == "delivered")
        }
        val needsChatReset = chat.myUnreadCount(uid) > 0L ||
            (chat.lastMessageSenderId != uid && chat.lastMessageStatus != "read" && chat.lastMessageStatus.isNotEmpty())

        if (unreadIncoming.isEmpty() && !needsChatReset) return

        try {
            val batch = db.batch()
            unreadIncoming.take(40).forEach { msg ->
                val ref = db.collection("chats").document(chat.id)
                    .collection("messages").document(msg.id)
                batch.update(
                    ref,
                    mapOf(
                        "status" to targetStatus,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }
            val chatRef = db.collection("chats").document(chat.id)
            val chatUpdates = mutableMapOf<String, Any>(
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (uid == chat.user1Id) {
                chatUpdates["user1UnreadCount"] = 0L
            } else {
                chatUpdates["user2UnreadCount"] = 0L
            }
            if (chat.lastMessageSenderId != uid && chat.lastMessageStatus.isNotEmpty()) {
                chatUpdates["lastMessageStatus"] = targetStatus
            }
            batch.update(chatRef, chatUpdates)
            batch.commit().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "chats/${chat.id}", auth)
        }
    }

    suspend fun setTyping(chat: ChatRoom, isTyping: Boolean) {
        val uid = auth.currentUser?.uid ?: return
        if (chat.isSelfChat()) return
        val field = if (uid == chat.user1Id) "user1Typing" else "user2Typing"
        val currentTyping = if (uid == chat.user1Id) chat.user1Typing else chat.user2Typing
        if (currentTyping == isTyping) return

        val chatRef = db.collection("chats").document(chat.id)
        try {
            chatRef.update(
                mapOf(
                    field to isTyping,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, chatRef.path, auth)
        }
    }

    suspend fun toggleReaction(chat: ChatRoom, message: ChatMessage, emoji: String): Result<Unit> {
        val uid = requireUserId()
        val field = if (uid == chat.user1Id) "reactionUser1" else "reactionUser2"
        val existing = if (uid == chat.user1Id) message.reactionUser1 else message.reactionUser2
        val nextValue = if (existing == emoji) "" else emoji
        val msgRef = db.collection("chats").document(chat.id).collection("messages").document(message.id)
        return try {
            msgRef.update(
                mapOf(
                    field to nextValue,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, msgRef.path, auth)
            Result.failure(e)
        }
    }

    suspend fun toggleStarMessage(chatId: String, message: ChatMessage): Result<Unit> {
        val uid = requireUserId()
        val nextStarred = if (uid in message.starredBy) {
            message.starredBy - uid
        } else {
            (message.starredBy + uid).distinct().take(2)
        }
        val msgRef = db.collection("chats").document(chatId).collection("messages").document(message.id)
        return try {
            msgRef.update(
                mapOf(
                    "starredBy" to nextStarred,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, msgRef.path, auth)
            Result.failure(e)
        }
    }

    suspend fun deleteMessageForEveryone(
        chat: ChatRoom,
        messageId: String,
        sharedAesKey: SecretKeySpec
    ): Result<Unit> {
        requireUserId()
        val msgRef = db.collection("chats").document(chat.id).collection("messages").document(messageId)
        return try {
            val encPlaceholder = cryptoEngine.encrypt("This message was deleted", sharedAesKey)
            msgRef.update(
                mapOf(
                    "deletedForEveryone" to true,
                    "ciphertext" to encPlaceholder.ciphertextBase64,
                    "iv" to encPlaceholder.ivBase64,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, msgRef.path, auth)
            Result.failure(e)
        }
    }

    suspend fun updateDisappearingTimer(chatId: String, seconds: Long): Result<Unit> {
        requireUserId()
        val chatRef = db.collection("chats").document(chatId)
        return try {
            chatRef.update(
                mapOf(
                    "disappearingSeconds" to seconds,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, chatRef.path, auth)
            Result.failure(e)
        }
    }

    suspend fun updateChatPref(
        chatId: String,
        isPinned: Boolean,
        isMuted: Boolean,
        isArchived: Boolean
    ): Result<Unit> {
        val uid = requireUserId()
        val prefRef = db.collection("users").document(uid).collection("chatPrefs").document(chatId)
        return try {
            val payload = mapOf(
                "userId" to uid,
                "chatId" to chatId,
                "isPinned" to isPinned,
                "isMuted" to isMuted,
                "isArchived" to isArchived,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            prefRef.set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, prefRef.path, auth)
            Result.failure(e)
        }
    }

    suspend fun updateProfileAndPrivacy(
        displayName: String,
        about: String,
        handle: String,
        readReceiptsEnabled: Boolean,
        isDiscoverable: Boolean,
        appLockEnabled: Boolean,
        appLockPinHash: String
    ): Result<Unit> {
        val uid = requireUserId()
        val safeName = displayName.trim().ifBlank { "Ding User" }.take(60)
        val safeAbout = about.trim().take(140)
        val safeHandle = handle.trim().ifBlank { "@dinguser" }.take(40)
        val userRef = db.collection("users").document(uid)
        val dirRef = db.collection("directory").document(uid)
        return try {
            val batch = db.batch()
            batch.update(
                userRef,
                mapOf(
                    "displayName" to safeName,
                    "about" to safeAbout,
                    "handle" to safeHandle,
                    "readReceiptsEnabled" to readReceiptsEnabled,
                    "isDiscoverable" to isDiscoverable,
                    "appLockEnabled" to appLockEnabled,
                    "appLockPinHash" to appLockPinHash,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            batch.update(
                dirRef,
                mapOf(
                    "displayName" to safeName,
                    "about" to safeAbout,
                    "handle" to safeHandle,
                    "isDiscoverable" to isDiscoverable,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, userRef.path, auth)
            Result.failure(e)
        }
    }

    suspend fun postStatusUpdate(
        authorName: String,
        authorPhotoUrl: String?,
        content: String,
        styleIndex: Long
    ): Result<String> {
        val uid = requireUserId()
        val ref = db.collection("statusUpdates").document()
        return try {
            val payload = mutableMapOf<String, Any>(
                "authorId" to uid,
                "authorName" to authorName.trim().ifBlank { "Ding User" }.take(60),
                "content" to content.trim().take(300),
                "styleIndex" to styleIndex.coerceIn(0L, 5L),
                "isActive" to true,
                "viewerIds" to emptyList<String>(),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (!authorPhotoUrl.isNullOrBlank()) {
                payload["authorPhotoUrl"] = authorPhotoUrl.take(500)
            }
            ref.set(payload).await()
            Result.success(ref.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, ref.path, auth)
            Result.failure(e)
        }
    }

    suspend fun markStatusViewed(status: StatusUpdate) {
        val uid = auth.currentUser?.uid ?: return
        if (status.authorId == uid || uid in status.viewerIds || status.viewerIds.size >= 100) return
        val ref = db.collection("statusUpdates").document(status.id)
        try {
            ref.update(
                mapOf(
                    "viewerIds" to (status.viewerIds + uid).distinct(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, ref.path, auth)
        }
    }

    suspend fun deleteStatusUpdate(statusId: String): Result<Unit> {
        requireUserId()
        val ref = db.collection("statusUpdates").document(statusId)
        return try {
            ref.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, ref.path, auth)
            Result.failure(e)
        }
    }
}
