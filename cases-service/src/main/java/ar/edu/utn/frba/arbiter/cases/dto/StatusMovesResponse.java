package ar.edu.utn.frba.arbiter.cases.dto;

import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;

import java.util.List;

/** One status and every status a case can move to from it, in enum order. */
public record StatusMovesResponse(CaseStatus from, List<CaseStatus> to) {
}
