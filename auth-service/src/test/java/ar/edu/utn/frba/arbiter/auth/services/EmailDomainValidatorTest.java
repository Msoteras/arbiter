package ar.edu.utn.frba.arbiter.auth.services;

import ar.edu.utn.frba.arbiter.auth.exceptions.InvalidEmailDomainException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Does real DNS lookups — depends on the network. Uses stable domains: gmail.com (real MX, it
 * shouldn't change) and the .invalid TLD (RFC 2606, reserved to never resolve).
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
