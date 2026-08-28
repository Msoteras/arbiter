/** Mirrors auth-service's PasswordCipher: an `ARB1.<base64>` envelope sealed with RSA-OAEP-256. */

const ENVELOPE_PREFIX = 'ARB1.';

/**
 * Seals the password with the backend's public key. The timestamp goes inside the ciphertext, not
 * next to it, so it can't be rewritten to revive an expired envelope.
 */
export async function sealPassword(password: string, publicKeyBase64: string): Promise<string> {
  const key = await crypto.subtle.importKey(
    'spki',
    base64ToBytes(publicKeyBase64),
    { name: 'RSA-OAEP', hash: 'SHA-256' },
    false,
    ['encrypt'],
  );
  const payload = new TextEncoder().encode(`${Date.now()}:${password}`);
  const sealed = await crypto.subtle.encrypt({ name: 'RSA-OAEP' }, key, payload);
  return ENVELOPE_PREFIX + bytesToBase64(new Uint8Array(sealed));
}

// The type is explicit because `Uint8Array.from` returns `Uint8Array<ArrayBufferLike>`, which since
// TS 5.7 no longer satisfies `BufferSource`.
function base64ToBytes(base64: string): Uint8Array<ArrayBuffer> {
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes;
}

function bytesToBase64(bytes: Uint8Array): string {
  let binary = '';
  for (const byte of bytes) {
    binary += String.fromCharCode(byte);
  }
  return btoa(binary);
}
