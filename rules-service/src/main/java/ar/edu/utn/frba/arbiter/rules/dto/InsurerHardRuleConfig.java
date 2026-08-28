package ar.edu.utn.frba.arbiter.rules.dto;

/**
 * Shape of an insurer-scoped hard rule's {@code configuration} (JSONB). Only
 * {@code POLICY_STANDING} carries anything here — see {@link InsurerHardRuleDto#onArrears()}.
 * {@code POLICY_IN_FORCE} always serializes {@link #empty()}: coverage window needs no choice
 * beyond being on or off.
 */
public record InsurerHardRuleConfig(String onArrears) {

    public static InsurerHardRuleConfig empty() {
        return new InsurerHardRuleConfig(InsurerHardRuleDto.ON_ARREARS_STANDBY);
    }
}
