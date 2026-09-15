package ar.edu.utn.frba.arbiter.common.enums;

/**
 * How the ceiling of an indemnity is worked out for a coverage — the first step of determining
 * what gets paid, before any deduction.
 *
 * <p>Lives in common-lib because it's shared vocabulary: cases-service persists the literal on
 * {@code coverage.settlement_basis} and freezes it again on {@code case_settlement}, and the
 * referente's Coberturas tab reads it back. Both values come straight off the insurer's product
 * documents; there is no third option to invent.
 *
 * <p>Literals can't exceed 30 characters: {@code coverage.settlement_basis} and
 * {@code case_settlement.settlement_basis} are {@code VARCHAR(30)} in
 * {@code db/init-multitenant.sql}.
 */
public enum SettlementBasis {

    /**
     * The sum insured, flat. What the Celulares manual states: "La suma asegurada (menos) la
     * franquicia (menos) las cuotas pendientes de pago". The replacement value, if the analyst
     * recorded one, doesn't cap anything here — it's evidence for the file, not a term of the
     * contract.
     */
    SUM_INSURED,

    /**
     * The lesser of the sum insured and the accredited replacement value. Article 7 of clause 340
     * ("Bases de Indemnización", Tecnología Portátil): the insurer's liability "no excederá el
     * menor" of the amount assigned to the item and the cost of replacing it with an identical
     * one. Without a replacement value on file this falls back to the sum insured — the ceiling
     * can't be lowered by a number nobody produced.
     */
    LESSER_OF_SUM_AND_REPLACEMENT
}
