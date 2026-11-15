package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.example.data.model.DirectoryUser
import com.example.security.CryptoEngine
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class DingRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun ensureUserProvisioned_and_encryptedEndToEndChatFlow_succeeds() = runBlocking {
        val crypto = CryptoEngine()
        val aliceUid = signInTestUser("alice_${UUID.randomUUID()}@ding.test")
        val aliceKeys = crypto.generateEcKeyPair()
        val aliceRepo = DingRepository(firestore, crypto, auth)

        val provAlice = withTimeout(TIMEOUT_MS) {
            aliceRepo.ensureUserProvisioned(
                email = "alice@ding.test",
                displayName = "Alice",
                photoUrl = null,
                publicKeyBase64 = aliceKeys.publicKeyBase64
            )
        }
        assertTrue("Alice provision failed: ${provAlice.exceptionOrNull()}", provAlice.isSuccess)

        val bobUid = signInTestUser("bob_${UUID.randomUUID()}@ding.test")
        val bobKeys = crypto.generateEcKeyPair()
        val bobRepo = DingRepository(firestore, crypto, auth)

        val provBob = withTimeout(TIMEOUT_MS) {
            bobRepo.ensureUserProvisioned(
                email = "bob@ding.test",
                displayName = "Bob",
                photoUrl = null,
                publicKeyBase64 = bobKeys.publicKeyBase64
            )
        }
        assertTrue("Bob provision failed: ${provBob.exceptionOrNull()}", provBob.isSuccess)

        // Alice creates encrypted chat with Bob
        signInTestUserForUid(aliceUid, bobUid, aliceRepo, bobRepo, aliceKeys, bobKeys, crypto)
    }

    private suspend fun signInTestUserForUid(
        aliceUid: String,
        bobUid: String,
        aliceRepo: DingRepository,
        bobRepo: DingRepository,
        aliceKeys: CryptoEngine.EcKeyPair,
        bobKeys: CryptoEngine.EcKeyPair,
        crypto: CryptoEngine
    ) {
        // Currently signed in as Bob; test Bob creating chat with Alice
        val bobDir = DirectoryUser(
            userId = bobUid,
            displayName = "Bob",
            handle = "@bob",
            publicKeyBase64 = bobKeys.publicKeyBase64
        )
        val aliceDir = DirectoryUser(
            userId = aliceUid,
            displayName = "Alice",
            handle = "@alice",
            publicKeyBase64 = aliceKeys.publicKeyBase64
        )

        val chatId = withTimeout(TIMEOUT_MS) {
            bobRepo.getOrCreateChat(bobDir, aliceDir).getOrThrow()
        }
        assertTrue(chatId.isNotBlank())

        val chatRoom = withTimeout(TIMEOUT_MS) {
            bobRepo.getChatById(chatId).getOrThrow()
        }
        assertNotNull(chatRoom)

        val bobSharedKey = crypto.deriveSharedAesKey(bobKeys.privateKeyBase64, aliceKeys.publicKeyBase64)
        val aliceSharedKey = crypto.deriveSharedAesKey(aliceKeys.privateKeyBase64, bobKeys.publicKeyBase64)

        val sendResult = withTimeout(TIMEOUT_MS) {
            bobRepo.sendMessage(
                chat = chatRoom!!,
                plaintext = "Salam Alice, this is Ding E2E!",
                sharedAesKey = bobSharedKey
            )
        }
        assertTrue("Send message failed: ${sendResult.exceptionOrNull()}", sendResult.isSuccess)

        val observed = withTimeout(FLOW_TIMEOUT_MS) {
            bobRepo.observeMessages(chatId, bobUid).first { it.isNotEmpty() }
        }
        assertEquals(1, observed.size)
        val decryptedByAlice = crypto.decrypt(observed[0].ciphertext, observed[0].iv, aliceSharedKey)
        assertEquals("Salam Alice, this is Ding E2E!", decryptedByAlice)

        // Verify safety number symmetry
        val safetyA = crypto.computeSafetyNumber(aliceKeys.publicKeyBase64, bobKeys.publicKeyBase64)
        val safetyB = crypto.computeSafetyNumber(bobKeys.publicKeyBase64, aliceKeys.publicKeyBase64)
        assertEquals(safetyA, safetyB)
        assertEquals(12, safetyA.size)
    }

    @Test
    fun crossUserPrivateProfile_access_failsWithPermissionDenied() = runBlocking {
        val crypto = CryptoEngine()
        val aliceUid = signInTestUser("alice_priv_${UUID.randomUUID()}@ding.test")
        val aliceKeys = crypto.generateEcKeyPair()
        val aliceRepo = DingRepository(firestore, crypto, auth)
        withTimeout(TIMEOUT_MS) {
            aliceRepo.ensureUserProvisioned("alice@ding.test", "Alice", null, aliceKeys.publicKeyBase64).getOrThrow()
        }

        // Sign in as Bob
        signInTestUser("bob_priv_${UUID.randomUUID()}@ding.test")
        val exception = assertFailsWithType<FirebaseFirestoreException> {
            runBlocking {
                withTimeout(TIMEOUT_MS) {
                    firestore.collection("users").document(aliceUid).get().await()
                }
            }
        }
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, exception.code)
    }

    @Test
    fun observeMyChats_unauthenticated_failsWithPermissionDenied() = runBlocking {
        auth.signOut()
        val repo = DingRepository(firestore, CryptoEngine(), auth)
        val exception = assertFailsWithType<Exception> {
            runBlocking {
                withTimeout(FLOW_TIMEOUT_MS) {
                    repo.observeMyChats("some_uid").first()
                }
            }
        }
        val firestoreException = checkNotNull(exception.unwrapFirestoreException()) {
            "Expected nested FirebaseFirestoreException, got $exception"
        }
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreException.code)
    }

    @Test
    fun statusUpdate_createAndObserve_succeedsForAuthenticatedUser() = runBlocking {
        val crypto = CryptoEngine()
        val uid = signInTestUser("status_${UUID.randomUUID()}@ding.test")
        val repo = DingRepository(firestore, crypto, auth)

        val statusId = withTimeout(TIMEOUT_MS) {
            repo.postStatusUpdate("StatusUser", null, "Secured with Ding!", 2L).getOrThrow()
        }
        assertFalse(statusId.isBlank())

        val statuses = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeStatusUpdates(uid).first { list -> list.any { it.id == statusId } }
        }
        assertTrue(statuses.any { it.id == statusId && it.content == "Secured with Ding!" })
    }

    private companion object {
        const val TIMEOUT_MS = 6000L
        const val FLOW_TIMEOUT_MS = 4000L
    }
}
