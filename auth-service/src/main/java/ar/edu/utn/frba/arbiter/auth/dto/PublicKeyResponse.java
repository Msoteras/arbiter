package ar.edu.utn.frba.arbiter.auth.dto;

/** @param publicKey RSA key in base64, SPKI format (what {@code crypto.subtle.importKey} takes) */
public record PublicKeyResponse(String publicKey, String algorithm) {
}
