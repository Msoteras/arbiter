package ar.edu.utn.frba.arbiter.cases.models.entities;

public enum ExtractedField {
    DOCUMENT_DATE,
    AMOUNT,
    ITEM_DESCRIPTION,
    ITEM_ATTRIBUTE,
    DETAIL;

    public boolean isNamed() {
        return this == ITEM_ATTRIBUTE || this == DETAIL;
    }
}
