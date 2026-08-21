package ar.edu.utn.frba.arbiter.auth.services;

import ar.edu.utn.frba.arbiter.auth.exceptions.InvalidEncryptedPasswordException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Seals the envelope with the same OAEP parameters {@code crypto.subtle} uses in the browser, so
 * these cover the agreement between both ends and not just the class understanding itself.
 */
class PasswordCipherTest {

    private static final String PASSWORD = "asegurado.arbiter123";

    private PasswordCipher cipher;

    @BeforeEach
    void setUp() {
        cipher = newCipher();
    }

    @Test
    void decrypt_envelopeSealedLikeTheBrowserDoes_returnsPassword() {
        String envelope = seal(Instant.now(), PASSWORD);

        assertThat(cipher.decrypt(envelope)).isEqualTo(PASSWORD);
    }

    @Test
    void decrypt_passwordContainingColons_keepsThemAll() {
        String withColons = "not:trivial:to:split";

        assertThat(cipher.decrypt(seal(Instant.now(), withColons))).isEqualTo(withColons);
    }

    @Test
    void decrypt_staleEnvelope_throwsInvalidEncryptedPassword() {
        String stale = seal(Instant.now().minus(Duration.ofMinutes(6)), PASSWORD);

        assertThatThrownBy(() -> cipher.decrypt(stale))
                .isInstanceOf(InvalidEncryptedPasswordException.class);
    }

    /** A slightly fast client clock shouldn't knock the login over. */
    @Test
    void decrypt_slightlyAheadClock_returnsPassword() {
        String skewed = seal(Instant.now().plus(Duration.ofMinutes(1)), PASSWORD);

        assertThat(cipher.decrypt(skewed)).isEqualTo(PASSWORD);
    }

    @Test
    void decrypt_timestampFarInTheFuture_throwsInvalidEncryptedPassword() {
        String fromTheFuture = seal(Instant.now().plus(Duration.ofHours(1)), PASSWORD);

        assertThatThrownBy(() -> cipher.decrypt(fromTheFuture))
                .isInstanceOf(InvalidEncryptedPasswordException.class);
    }

    /** The one that matters: there is no way to send the password in the clear. */
    @Test
    void decrypt_plaintextPassword_throwsInvalidEncryptedPassword() {
        assertThatThrownBy(() -> cipher.decrypt(PASSWORD))
                .isInstanceOf(InvalidEncryptedPasswordException.class);
    }

    @Test
    void decrypt_envelopeSealedForAnotherKey_throwsInvalidEncryptedPassword() {
        String sealedForSomeoneElse = seal(newCipher().publicKeyBase64(), Instant.now(), PASSWORD);

        assertThatThrownBy(() -> cipher.decrypt(sealedForSomeoneElse))
                .isInstanceOf(InvalidEncryptedPasswordException.class);
    }

    @Test
    void decrypt_garbageOrNull_throwsInvalidEncryptedPassword() {
        assertThatThrownBy(() -> cipher.decrypt("ARB1.not-valid-base64"))
                .isInstanceOf(InvalidEncryptedPasswordException.class);
        assertThatThrownBy(() -> cipher.decrypt(null))
                .isInstanceOf(InvalidEncryptedPasswordException.class);
    }

    @Test
    void decrypt_payloadWithoutTimestamp_throwsInvalidEncryptedPassword() {
        String noTimestamp = "ARB1." + encrypt(cipher.publicKeyBase64(), PASSWORD);

        assertThatThrownBy(() -> cipher.decrypt(noTimestamp))
                .isInstanceOf(InvalidEncryptedPasswordException.class);
    }

    /** Blank config makes it generate its own key pair, which is all these tests need. */
    private static PasswordCipher newCipher() {
        PasswordCipher created = new PasswordCipher();
        ReflectionTestUtils.setField(created, "configuredPrivateKey", "");
        created.init();
        return created;
    }

    private String seal(Instant issuedAt, String password) {
        return seal(cipher.publicKeyBase64(), issuedAt, password);
    }

    private static String seal(String publicKeyBase64, Instant issuedAt, String password) {
        return "ARB1." + encrypt(publicKeyBase64, issuedAt.toEpochMilli() + ":" + password);
    }

    private static String encrypt(String publicKeyBase64, String payload) {
        try {
            PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64)));
            Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPPadding");
            rsa.init(Cipher.ENCRYPT_MODE, publicKey, new OAEPParameterSpec(
                    "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
            return Base64.getEncoder()
                    .encodeToString(rsa.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
