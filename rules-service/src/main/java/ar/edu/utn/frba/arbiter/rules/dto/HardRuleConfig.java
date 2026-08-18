package ar.edu.utn.frba.arbiter.rules.dto;

/**
 * Shape of a hard temporal rule's {@code configuration} (JSONB). Most of them carry no threshold
 * here: the number is a term of the contract and lives on the {@code coverage} column the
 * referente edits from the Coverages tab (waiting period, report deadline, events cap). The
 * {@code insurer_rule} row is the <b>switch</b> — if the insurer has the rule active it gets
 * evaluated, otherwise it doesn't — and the target of {@code rule_result}'s FK.
 *
 * <p>The exception is {@code POLICE_DEADLINE}: the police-report deadline has no column of its own
 * ({@code coverage.report_deadline_hours} is already the deadline for the report <b>to the
 * insurer</b>, and one number can't govern two different rules), so its threshold lives here.
 */
public record HardRuleConfig(Long deadlineHours) {

    public static HardRuleConfig empty() {
        return new HardRuleConfig(null);
    }
}
