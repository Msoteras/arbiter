package ar.edu.utn.frba.arbiter.auth.services;

import ar.edu.utn.frba.arbiter.auth.exceptions.InvalidEmailDomainException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Hace lookups DNS reales — depende de red. Usa dominios estables: gmail.com (MX real,
 * no debería cambiar) y el TLD .invalid (RFC 2606, reservado para nunca resolver).
 */
class EmailDomainValidatorTest {

    private final EmailDomainValidator validator = new EmailDomainValidator();

    @Test
    void validate_domainWithRealMxRecords_doesNotThrow() {
        assertThatCode(() -> validator.validate("alguien@gmail.com")).doesNotThrowAnyException();
    }

    @Test
    void validate_domainWithoutMxRecords_throwsInvalidEmailDomain() {
        assertThatThrownBy(() -> validator.validate("alguien@no-existe.invalid"))
                .isInstanceOf(InvalidEmailDomainException.class);
    }
}
