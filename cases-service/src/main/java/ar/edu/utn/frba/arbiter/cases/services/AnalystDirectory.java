package ar.edu.utn.frba.arbiter.cases.services;

/**
 * Lookup of the analysts a case can be assigned to. cases-service doesn't own the users table
 * (auth-service does) and must not read it directly — this is the seam that keeps that boundary,
 * so the assignment logic depends on the abstraction and not on how the other module is reached.
 */
public interface AnalystDirectory {

    /**
     * Full display name of the analyst, for caching on the case. Throws when the id doesn't
     * belong to an analyst, or when the directory can't be reached — assigning a case to an
     * id we can't verify would silently create an orphan owner.
     */
    String analystName(Long analystId);
}
