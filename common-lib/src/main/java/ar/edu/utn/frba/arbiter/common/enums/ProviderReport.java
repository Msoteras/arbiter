package ar.edu.utn.frba.arbiter.common.enums;

/**
 * A provider's answer to a referral, stored as a case document under its {@code code}. The analyst
 * attaches it, not the insured: cases-service hides it from the insured, and classification-service
 * keeps it out of the insured's documentation in the fraud score. Shared so both read the same codes.
 */
public enum ProviderReport {
    EXPERT_REPORT("expert_report"),
    REPAIR_REPORT("repair_report");

    private final String code;

    ProviderReport(String code) {
        this.code = code;
    }

    /** The {@code case_documents.type}, unique per case: each provider kind needs its own. */
    public String code() {
        return code;
    }

    public static boolean isProviderReport(String documentType) {
        for (ProviderReport report : values()) {
            if (report.code.equals(documentType)) {
                return true;
            }
        }
        return false;
    }
}
