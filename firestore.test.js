const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");
const ts = () => new Date(Date.now() - 2000);

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";
const CHARLIE_UID = "charlie_789";
const CHAT_ID = `${ALICE_UID}_${BOB_UID}`;

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

const SAMPLE_PUB_KEY_A = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEAlicePublicKeySampleBase64EncodedValueForTests0001";
const SAMPLE_PUB_KEY_B = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEBobPublicKeySampleBase64EncodedValueForTests00002";

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

function privateUserPayload(uid, email, name) {
  return {
    userId: uid,
    email,
    displayName: name,
    about: "Encrypted on Ding",
    handle: `@${name.toLowerCase()}`,
    readReceiptsEnabled: true,
    isDiscoverable: true,
    appLockEnabled: false,
    appLockPinHash: "",
    createdAt: ts(),
    updatedAt: ts(),
  };
}

function directoryPayload(uid, name, pubKey, discoverable = true) {
  return {
    userId: uid,
    displayName: name,
    handle: `@${name.toLowerCase()}`,
    about: "Encrypted on Ding",
    publicKeyBase64: pubKey,
    isDiscoverable: discoverable,
    isOnline: true,
    lastSeen: ts(),
    createdAt: ts(),
    updatedAt: ts(),
  };
}

function chatRoomPayload(createdBy) {
  return {
    participantIds: [ALICE_UID, BOB_UID],
    user1Id: ALICE_UID,
    user2Id: BOB_UID,
    user1Name: "Alice",
    user2Name: "Bob",
    user1PublicKey: SAMPLE_PUB_KEY_A,
    user2PublicKey: SAMPLE_PUB_KEY_B,
    user1UnreadCount: 0,
    user2UnreadCount: 0,
    user1Typing: false,
    user2Typing: false,
    lastMessageCiphertext: "",
    lastMessageIv: "",
    lastMessageSenderId: "",
    lastMessageStatus: "",
    disappearingSeconds: 0,
    createdBy,
    createdAt: ts(),
    updatedAt: ts(),
  };
}

function messagePayload(senderId, seq = 1) {
  return {
    senderId,
    participantIds: [ALICE_UID, BOB_UID],
    ciphertext: "c2VjcmV0X2NpcGhlcnRleHQ=",
    iv: "MTIzNDU2Nzg5MDEy",
    messageType: "text",
    status: "sent",
    reactionUser1: "",
    reactionUser2: "",
    starredBy: [],
    deletedForEveryone: false,
    clientSeq: seq,
    createdAt: ts(),
    updatedAt: ts(),
  };
}

test("Unauthenticated caller cannot read or write any collection", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("directory").where("isDiscoverable", "==", true).get());
  await assertFails(unauthDb.collection("chats").where("participantIds", "array-contains", ALICE_UID).get());
  await assertFails(unauthDb.collection("statusUpdates").where("isActive", "==", true).get());
});

test("Private user PII is strictly owner-only and rejects shadow fields", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set(privateUserPayload(ALICE_UID, "alice@ding.app", "Alice"))
  );

  // Cross-user read & write blocked
  await assertFails(bobDb.collection("users").doc(ALICE_UID).get());
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).update({ displayName: "Hacked", updatedAt: ts() })
  );

  // Ghost field blocked
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({ isAdmin: true, updatedAt: ts() })
  );
});

test("Directory enforces discoverability filter and prevents spoofing", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  await assertSucceeds(
    aliceDb.collection("directory").doc(ALICE_UID).set(directoryPayload(ALICE_UID, "Alice", SAMPLE_PUB_KEY_A, true))
  );
  await assertSucceeds(
    bobDb.collection("directory").doc(BOB_UID).set(directoryPayload(BOB_UID, "Bob", SAMPLE_PUB_KEY_B, false))
  );

  // Unfiltered list fails
  await assertFails(aliceDb.collection("directory").get());

  // Filtered list succeeds
  await assertSucceeds(aliceDb.collection("directory").where("isDiscoverable", "==", true).get());

  // Non-discoverable user cannot be read by another user
  await assertFails(aliceDb.collection("directory").doc(BOB_UID).get());

  // Cannot overwrite another user's directory key
  await assertFails(
    aliceDb.collection("directory").doc(BOB_UID).update({ publicKeyBase64: SAMPLE_PUB_KEY_A, updatedAt: ts() })
  );
});

test("Chat rooms and messages restrict access strictly to participants", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const charlieDb = testEnv.authenticatedContext(CHARLIE_UID).firestore();

  await assertSucceeds(
    aliceDb.collection("directory").doc(ALICE_UID).set(directoryPayload(ALICE_UID, "Alice", SAMPLE_PUB_KEY_A))
  );
  await assertSucceeds(
    bobDb.collection("directory").doc(BOB_UID).set(directoryPayload(BOB_UID, "Bob", SAMPLE_PUB_KEY_B))
  );

  await assertSucceeds(aliceDb.collection("chats").doc(CHAT_ID).set(chatRoomPayload(ALICE_UID)));

  // Unfiltered chats list fails
  await assertFails(aliceDb.collection("chats").get());
  // Filtered chats list succeeds for Alice and Bob, fails for Charlie on Alice's query
  await assertSucceeds(aliceDb.collection("chats").where("participantIds", "array-contains", ALICE_UID).get());
  await assertFails(charlieDb.collection("chats").doc(CHAT_ID).get());

  // Alice sends message
  await assertSucceeds(
    aliceDb.collection("chats").doc(CHAT_ID).collection("messages").doc("msg_1").set(messagePayload(ALICE_UID, 1))
  );

  // Charlie cannot read or inject message
  await assertFails(charlieDb.collection("chats").doc(CHAT_ID).collection("messages").doc("msg_1").get());
  await assertFails(
    charlieDb.collection("chats").doc(CHAT_ID).collection("messages").doc("msg_2").set(messagePayload(CHARLIE_UID, 2))
  );

  // Bob queries messages with participantIds filter
  await assertSucceeds(
    bobDb.collection("chats").doc(CHAT_ID).collection("messages").where("participantIds", "array-contains", BOB_UID).get()
  );

  // Bob marks message read (allowed action)
  await assertSucceeds(
    bobDb.collection("chats").doc(CHAT_ID).collection("messages").doc("msg_1").update({
      status: "read",
      updatedAt: ts(),
    })
  );

  // Bob cannot alter Alice's message ciphertext
  await assertFails(
    bobDb.collection("chats").doc(CHAT_ID).collection("messages").doc("msg_1").update({
      ciphertext: "tampered",
      updatedAt: ts(),
    })
  );
});

test("Status updates enforce active filter and author isolation", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  await assertSucceeds(
    aliceDb.collection("statusUpdates").doc("st_1").set({
      authorId: ALICE_UID,
      authorName: "Alice",
      content: "Ding E2E chat is live!",
      styleIndex: 0,
      isActive: true,
      viewerIds: [],
      createdAt: ts(),
      updatedAt: ts(),
    })
  );

  await assertFails(bobDb.collection("statusUpdates").get());
  await assertSucceeds(bobDb.collection("statusUpdates").where("isActive", "==", true).get());

  // Bob can record view, but cannot edit Alice's text
  await assertSucceeds(
    bobDb.collection("statusUpdates").doc("st_1").update({
      viewerIds: [BOB_UID],
      updatedAt: ts(),
    })
  );
  await assertFails(
    bobDb.collection("statusUpdates").doc("st_1").update({
      content: "Edited by Bob",
      updatedAt: ts(),
    })
  );
});
