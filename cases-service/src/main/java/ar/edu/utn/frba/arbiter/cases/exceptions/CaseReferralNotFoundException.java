package ar.edu.utn.frba.arbiter.cases.exceptions;

public class CaseReferralNotFoundException extends RuntimeException {

    public CaseReferralNotFoundException(Long caseId) {
        super("Case " + caseId + " has no referral of that provider type");
    }
}
