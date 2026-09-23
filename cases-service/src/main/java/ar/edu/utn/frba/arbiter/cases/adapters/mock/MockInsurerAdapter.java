package ar.edu.utn.frba.arbiter.cases.adapters.mock;

import ar.edu.utn.frba.arbiter.cases.adapters.InsurerAdapter;
import ar.edu.utn.frba.arbiter.cases.dto.PolicyResponse;
import ar.edu.utn.frba.arbiter.cases.dto.PolicyResponse.Coverage;
import ar.edu.utn.frba.arbiter.cases.dto.PolicyResponse.Validity;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * In-memory fallback when the {@code insurer-db} profile is off. Mirrors part of
 * {@code db/seed-demo.sql} for the test insured, from a single insurer only.
 */
@Component
public class MockInsurerAdapter implements InsurerAdapter {

    private static final List<PolicyResponse> POLICIES = List.of(
            PolicyResponse.builder()
                    .policyNumber("POL-CEL-2026-042")
                    .insurerId("1").insurerName("BBVA Seguros Argentina S.A.")
                    .insuredName("Martina Soteras").insuredId("42.987.654")
                    .contactEmail("martina.soteras@example.com").contactPhone("11-5555-0001")
                    .branch("Celulares").insuredItem("Samsung Galaxy A56").product("Celular Protegido Premium")
                    .effectiveFrom(LocalDateTime.of(2026, 1, 1, 0, 0)).effectiveTo(LocalDateTime.of(2027, 1, 1, 23, 59, 59))
                    .upToDate(true)
                    .insuredAmount(new BigDecimal("1300000")).deductible(new BigDecimal("130000.00"))
                    .coverages(List.of(
                            Coverage.builder().code("COB-1").description("Robo de celular")
                                    .insuredAmount(new BigDecimal("1300000")).deductible(new BigDecimal("130000.00")).deductiblePct(new BigDecimal("10.00")).build(),
                            Coverage.builder().code("COB-2").description("Hurto")
                                    .insuredAmount(new BigDecimal("650000")).deductible(new BigDecimal("65000.00")).deductiblePct(new BigDecimal("10.00")).build()
                    ))
                    .build(),
            PolicyResponse.builder()
                    .policyNumber("POL-TEC-2026-050")
                    .insurerId("1").insurerName("BBVA Seguros Argentina S.A.")
                    .insuredName("Martina Soteras").insuredId("42.987.654")
                    .contactEmail("martina.soteras@example.com").contactPhone("11-5555-0001")
                    .branch("Tecnología Portátil").insuredItem("Lenovo ThinkPad T14s Gen 5").product("Seguro de Tecnología Portátil")
                    .effectiveFrom(LocalDateTime.of(2026, 1, 1, 0, 0)).effectiveTo(LocalDateTime.of(2027, 1, 1, 23, 59, 59))
                    .upToDate(true)
                    .insuredAmount(new BigDecimal("900000")).deductible(new BigDecimal("90000.00"))
                    .coverages(List.of(
                            Coverage.builder().code("COB-1").description("Robo de celular")
                                    .insuredAmount(new BigDecimal("900000")).deductible(new BigDecimal("90000.00")).deductiblePct(new BigDecimal("10.00")).build(),
                            Coverage.builder().code("COB-2").description("Daño accidental")
                                    .insuredAmount(new BigDecimal("120000")).deductible(new BigDecimal("12000.00")).deductiblePct(new BigDecimal("10.00")).build()
                    ))
                    .build(),
            // Expired and in arrears on purpose: the profile must show it and the wizard must hide it.
            PolicyResponse.builder()
                    .policyNumber("POL-CEL-2025-011")
                    .insurerId("1").insurerName("BBVA Seguros Argentina S.A.")
                    .insuredName("Martina Soteras").insuredId("42.987.654")
                    .contactEmail("martina.soteras@example.com").contactPhone("11-5555-0001")
                    .branch("Celulares").insuredItem("Motorola Moto G54").product("Celular Protegido Básico")
                    .effectiveFrom(LocalDateTime.of(2025, 1, 1, 0, 0)).effectiveTo(LocalDateTime.of(2026, 1, 1, 23, 59, 59))
                    .upToDate(false)
                    .insuredAmount(new BigDecimal("400000")).deductible(new BigDecimal("60000.00"))
                    .coverages(List.of(
                            Coverage.builder().code("COB-1").description("Robo de celular")
                                    .insuredAmount(new BigDecimal("400000")).deductible(new BigDecimal("60000.00")).deductiblePct(new BigDecimal("15.00")).build()
                    ))
                    .build()
    );

    @Override
    public Optional<PolicyResponse> findPolicy(String policyNumber) {
        return POLICIES.stream()
                .filter(p -> p.policyNumber().equalsIgnoreCase(policyNumber))
                .map(p -> withValidity(p, LocalDateTime.now()))
                .findFirst();
    }

    @Override
    public List<PolicyResponse> findPoliciesByInsured(String insuredId, boolean includeExpired) {
        LocalDateTime now = LocalDateTime.now();
        return POLICIES.stream()
                .filter(p -> p.insuredId().equals(insuredId))
                .map(p -> withValidity(p, now))
                .filter(p -> includeExpired || p.validity() != Validity.EXPIRED)
                .sorted(Comparator
                        .comparing((PolicyResponse p) -> p.validity() == Validity.EXPIRED)
                        .thenComparing(PolicyResponse::policyNumber))
                .toList();
    }

    /** Validity is resolved per call so the filter and the label come from the same instant. */
    private static PolicyResponse withValidity(PolicyResponse policy, LocalDateTime now) {
        return PolicyResponse.builder()
                .policyNumber(policy.policyNumber())
                .insurerId(policy.insurerId()).insurerName(policy.insurerName())
                .insuredName(policy.insuredName()).insuredId(policy.insuredId())
                .contactEmail(policy.contactEmail()).contactPhone(policy.contactPhone())
                .branch(policy.branch()).insuredItem(policy.insuredItem()).product(policy.product())
                .effectiveFrom(policy.effectiveFrom()).effectiveTo(policy.effectiveTo())
                .validity(Validity.at(policy.effectiveFrom(), policy.effectiveTo(), now))
                .upToDate(policy.upToDate())
                .insuredAmount(policy.insuredAmount()).deductible(policy.deductible())
                .coverages(policy.coverages())
                .build();
    }
}
