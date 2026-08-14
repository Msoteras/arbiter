package ar.edu.utn.frba.arbiter.auth.services;

import ar.edu.utn.frba.arbiter.auth.exceptions.InvalidEncryptedPasswordException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * Opens the password the browser sends encrypted on login. It doesn't replace TLS — it keeps the
 * password out of devtools, HARs and access logs. The auth-service → Auth0 leg still goes in the
 * clear inside the tunnel, since Auth0's API takes the real password (decision #8).
 */
@Service
@Slf4j
public class PasswordCipher {

    /** Envelope version, so the algorithm can change without guessing what an old frontend sent. */
    private static final String ENVELOPE_PREFIX = "ARB1.";

    private static final String TRANSFORMATION = "RSA/ECB/OAEPPadding";
    private static final Duration MAX_AGE = Duration.ofMinutes(5);
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(2);

    @Value("${arbiter.auth.password-encryption.private-key:}")
    private String configuredPrivateKey;

    private PrivateKey privateKey;
    private String publicKeyBase64;

    @PostConstruct
    void init() {
        if (configuredPrivateKey.isBlank()) {
            KeyPair generated = generateKeyPair();
            privateKey = generated.getPrivate();
            publicKeyBase64 = Base64.getEncoder().encodeToString(generated.getPublic().getEncoded());
            log.warn("No arbiter.auth.password-encryption.private-key set, generated an ephemeral RSA "
                    + "pair. With more than one instance it has to be pinned: the browser encrypts "
                    + "against one instance's key and the login may land on another, failing on and off.");
            return;
        }
        privateKey = readPrivateKey(configuredPrivateKey);
        publicKeyBase64 = Base64.getEncoder().encodeToString(derivePublicKey(privateKey).getEncoded());
    }

    /** SPKI in base64, which is what {@code crypto.subtle.importKey} takes. */
    public String publicKeyBase64() {
        return publicKeyBase64;
    }

    /**
     * Every failure mode throws the same exception: telling them apart would confirm to whoever is
     * probing blindly which part of the envelope they got right.
     */
    public String decrypt(String envelope) {
        if (envelope == null || !envelope.startsWith(ENVELOPE_PREFIX)) {
            throw new InvalidEncryptedPasswordException();
        }
        String payload = decryptToPayload(envelope.substring(ENVELOPE_PREFIX.length()));

        // The timestamp lives inside the ciphertext: outside, anyone could rewrite it to revive an
        // expired envelope.
        int separator = payload.indexOf(':');
        if (separator < 0) {
            throw new InvalidEncryptedPasswordException();
        }
        Instant issuedAt = parseTimestamp(payload.substring(0, separator));
        Instant now = Instant.now();
        if (issuedAt.isBefore(now.minus(MAX_AGE)) || issuedAt.isAfter(now.plus(CLOCK_SKEW))) {
            throw new InvalidEncryptedPasswordException();
        }
        return payload.substring(separator + 1);
    }

    private String decryptToPayload(String base64Ciphertext) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            // Spelled out because Java's default uses SHA-1 for MGF1 while WebCrypto uses SHA-256:
            // with the default this fails without saying why.
            cipher.init(Cipher.DECRYPT_MODE, privateKey, new OAEPParameterSpec(
                    "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
            byte[] decrypted = cipher.doFinal(Base64.getDecoder().decode(base64Ciphertext));
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.debug("Could not decrypt the password envelope", e);
            throw new InvalidEncryptedPasswordException();
        }
    }

    private Instant parseTimestamp(String raw) {
        try {
            return Instant.ofEpochMilli(Long.parseLong(raw));
        } catch (NumberFormatException e) {
            throw new InvalidEncryptedPasswordException();
        }
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("Could not generate the login key pair", e);
        }
    }

    private static PrivateKey readPrivateKey(String base64Pkcs8) {
        try {
            byte[] der = Base64.getDecoder().decode(base64Pkcs8.replaceAll("\\s", ""));
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException(
                    "arbiter.auth.password-encryption.private-key is not a base64 PKCS#8 RSA key", e);
        }
    }

    /** Derived from the private one so there's a single value to configure and they can't drift. */
    private static PublicKey derivePublicKey(PrivateKey privateKey) {
        try {
            RSAPrivateCrtKey crt = (RSAPrivateCrtKey) privateKey;
            return KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
        } catch (ClassCastException e) {
            throw new IllegalStateException("The private key carries no CRT parameters, so the public "
                    + "one can't be derived from it. Generate it with `openssl genpkey -algorithm RSA`.", e);
        } catch (Exception e) {
            throw new IllegalStateException("Could not derive the login public key", e);
        }
    }
}
