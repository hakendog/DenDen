import { resolve } from "node:path";

export const TEST_FIREBASE_PUBLIC = Object.freeze({
  projectId: "denden-demo-123",
  firebaseAppId: "1:123456789012:android:0123456789abcdef",
  apiKey: "AIzaSyDendenProtocolTestOnly000000000",
  gcmSenderId: "123456789012",
  androidPackageName: "com.tensal.denden",
});

export const TEST_SENDER_CONFIG = Object.freeze({
  schemaVersion: 2,
  ...TEST_FIREBASE_PUBLIC,
  pairingId: "AAECAwQFBgcICQoLDA0ODw",
  topic: "ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8",
  eventKeyId: "event-key-000001",
  eventKey: "QEFCQ0RFRkdISUpLTE1OT1BRUlNUVVZXWFlaW1xdXl8",
  senderCredentialsDirectory: resolve(".test-denden-adc"),
});

export const TEST_BRAND_CONFIG = Object.freeze({
  schemaVersion: 2,
  ...TEST_FIREBASE_PUBLIC,
  pairingId: TEST_SENDER_CONFIG.pairingId,
  topic: TEST_SENDER_CONFIG.topic,
  brandKeyId: "brand-key-000001",
  brandKey: "YGFiY2RlZmdoaWprbG1ub3BxcnN0dXZ3eHl6e3x9fn8",
  senderCredentialsDirectory: TEST_SENDER_CONFIG.senderCredentialsDirectory,
});

export function testServiceAccountKey(accountId = "denden-012345abcdef", projectId = TEST_FIREBASE_PUBLIC.projectId) {
  return {
    type: "service_account",
    project_id: projectId,
    private_key_id: "a".repeat(40),
    private_key: "-----BEGIN PRIVATE KEY-----\ntest-only\n-----END PRIVATE KEY-----\n",
    client_email: `${accountId}@${projectId}.iam.gserviceaccount.com`,
    token_uri: "https://oauth2.googleapis.com/token",
  };
}
