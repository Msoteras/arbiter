package ar.edu.utn.frba.arbiter.rules.dto;

/**
 * Shape of the {@code FRAUD_RECORD} rule's {@code configuration} (JSONB). The window has no
 * {@code coverage} column to live in — it's a property of how long the insurer remembers a person,
 * not a term of any one contract — so unlike the other hard rules it travels on the row itself.
 */
public record FraudRecordRuleConfig(Integer windowMonths) {

    public static FraudRecordRuleConfig empty() {
        return new FraudRecordRuleConfig(FraudRecordRuleDto.DEFAULT_WINDOW_MONTHS);
    }
}
