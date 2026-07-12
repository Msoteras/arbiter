package ar.edu.utn.frba.arbiter.classification.adapters.mock;

import ar.edu.utn.frba.arbiter.classification.adapters.InsurerAdapter;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredHistory;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredHistory.ClaimRecord;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredPolicy;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredPolicy.PolicyCoverage;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Component
@Profile({"dev", "test", "default"})
public class MockInsurerAdapter implements InsurerAdapter {

    private static final Map<String, InsuredPolicy> POLICIES = Map.of(
            "POL-001", InsuredPolicy.builder()
                    .policyNumber("POL-001")
                    .insuredName("Asegurado de prueba")
                    .insuredId("12345678")
                    .branch("SUCURSAL-1")
                    .product("CELULAR")
                    .effectiveFrom(LocalDate.of(2024, 1, 1))
                    .effectiveTo(LocalDate.of(2027, 12, 31))
                    .upToDate(true)
                    .insuredAmount(new BigDecimal("500000"))
                    .deductible(new BigDecimal("50000"))
                    .coverages(List.of(
                            PolicyCoverage.builder()
                                    .code("COB-ROB-TEST")
                                    .description("Cobertura de robo (prueba)")
                                    .insuredAmount(new BigDecimal("500000"))
                                    .deductible(new BigDecimal("50000"))
                                    .build()
                    ))
                    .applicableClauses(List.of("100 — Exclusión robo en domicilio"))
                    .build(),
            "POL-CEL-2024-001", InsuredPolicy.builder()
                    .policyNumber("POL-CEL-2024-001")
                    .insuredName("Laura Fernández")
                    .insuredId("40.123.456")
                    .branch("Celulares")
                    .product("Celular Protegido Básico")
                    .effectiveFrom(LocalDate.of(2024, 3, 1))
                    .effectiveTo(LocalDate.of(2027, 3, 1))
                    .upToDate(true)
                    .insuredAmount(new BigDecimal("400000"))
                    .deductible(new BigDecimal("50000"))
                    .coverages(List.of(
                            PolicyCoverage.builder()
                                    .code("COB-ROB-01")
                                    .description("Robo y/o hurto del equipo")
                                    .insuredAmount(new BigDecimal("400000"))
                                    .deductible(new BigDecimal("50000"))
                                    .build(),
                            PolicyCoverage.builder()
                                    .code("COB-ROT-01")
                                    .description("Rotura accidental de pantalla")
                                    .insuredAmount(new BigDecimal("200000"))
                                    .deductible(new BigDecimal("30000"))
                                    .build()
                    ))
                    .applicableClauses(List.of("100 — Exclusión robo en domicilio", "105 — Franquicia fija"))
                    .build(),
            "POL-CEL-2025-099", InsuredPolicy.builder()
                    .policyNumber("POL-CEL-2025-099")
                    .insuredName("Marcelo Gómez")
                    .insuredId("30.555.777")
                    .branch("Celulares")
                    .product("Celular Protegido Premium")
                    .effectiveFrom(LocalDate.of(2025, 6, 1))
                    .effectiveTo(LocalDate.of(2026, 6, 1))
                    .upToDate(true)
                    .insuredAmount(new BigDecimal("1200000"))
                    .deductible(new BigDecimal("80000"))
                    .coverages(List.of(
                            PolicyCoverage.builder()
                                    .code("COB-ROB-02")
                                    .description("Robo y/o hurto — cobertura premium")
                                    .insuredAmount(new BigDecimal("1200000"))
                                    .deductible(new BigDecimal("80000"))
                                    .build(),
                            PolicyCoverage.builder()
                                    .code("COB-ROT-02")
                                    .description("Rotura accidental — cobertura total")
                                    .insuredAmount(new BigDecimal("1200000"))
                                    .deductible(new BigDecimal("60000"))
                                    .build()
                    ))
                    .applicableClauses(List.of("100 — Exclusión robo en domicilio", "102 — Reposición a nuevo", "344 — Cobertura mundial"))
                    .build(),
            "POL-CEL-2026-042", InsuredPolicy.builder()
                    .policyNumber("POL-CEL-2026-042")
                    .insuredName("Sofía Martínez")
                    .insuredId("42.987.654")
                    .branch("Celulares")
                    .product("Celular Protegido Premium")
                    .effectiveFrom(LocalDate.of(2026, 1, 10))
                    .effectiveTo(LocalDate.of(2027, 1, 10))
                    .upToDate(true)
                    .insuredAmount(new BigDecimal("1300000"))
                    .deductible(new BigDecimal("57000"))
                    .coverages(List.of(
                            PolicyCoverage.builder()
                                    .code("COB-ROB-02")
                                    .description("Robo y/o hurto — cobertura premium")
                                    .insuredAmount(new BigDecimal("1300000"))
                                    .deductible(new BigDecimal("80000"))
                                    .build(),
                            PolicyCoverage.builder()
                                    .code("COB-ROT-03")
                                    .description("Rotura accidental — sin límite de eventos")
                                    .insuredAmount(new BigDecimal("1300000"))
                                    .deductible(new BigDecimal("57000"))
                                    .build()
                    ))
                    .applicableClauses(List.of("100 — Exclusión robo en domicilio", "102 — Reposición a nuevo"))
                    .build()
    );

    private static final Map<String, InsuredHistory> HISTORIES = Map.of(
            "40.123.456", InsuredHistory.builder()
                    .insuredId("40.123.456")
                    .previousClaimsCount(0)
                    .totalAmountClaimed(BigDecimal.ZERO)
                    .customerSince(LocalDate.of(2024, 3, 1))
                    .claims(List.of())
                    .build(),

            "30.555.777", InsuredHistory.builder()
                    .insuredId("30.555.777")
                    .previousClaimsCount(3)
                    .totalAmountClaimed(new BigDecimal("2440000"))
                    .customerSince(LocalDate.of(2025, 6, 1))
                    .claims(List.of(
                            ClaimRecord.builder()
                                    .claimId("2025-4401")
                                    .date(LocalDate.of(2025, 11, 15))
                                    .branch("Celulares")
                                    .claimCause("Robo en vía pública")
                                    .affectedItem("Samsung Galaxy S24 Ultra - IMEI 353000000000055")
                                    .status("Aprobado")
                                    .amountClaimed(new BigDecimal("450000"))
                                    .amountSettled(new BigDecimal("400000"))
                                    .notes("Denuncia consistente. Sin señales de alerta.")
                                    .build(),
                            ClaimRecord.builder()
                                    .claimId("2026-1892")
                                    .date(LocalDate.of(2026, 2, 3))
                                    .branch("Celulares")
                                    .claimCause("Hurto")
                                    .affectedItem("iPhone 15 Pro - IMEI 353000000000077")
                                    .status("Aprobado")
                                    .amountClaimed(new BigDecimal("890000"))
                                    .amountSettled(new BigDecimal("810000"))
                                    .notes("Demora en denuncia policial (72 hs). Aprobado por antecedente limpio previo.")
                                    .build(),
                            ClaimRecord.builder()
                                    .claimId("2026-3310")
                                    .date(LocalDate.of(2026, 4, 28))
                                    .branch("Celulares")
                                    .claimCause("Robo en vía pública")
                                    .affectedItem("iPhone 16 Pro - IMEI 353000000000088")
                                    .status("En investigación")
                                    .amountClaimed(new BigDecimal("1100000"))
                                    .amountSettled(null)
                                    .notes("Tercer siniestro en 6 meses. Derivado a investigación por frecuencia.")
                                    .build()
                    ))
                    .build(),

            "42.987.654", InsuredHistory.builder()
                    .insuredId("42.987.654")
                    .previousClaimsCount(0)
                    .totalAmountClaimed(BigDecimal.ZERO)
                    .customerSince(LocalDate.of(2026, 1, 10))
                    .claims(List.of())
                    .build()
    );

    @Override
    public InsuredPolicy getPolicy(String policyNumber) {
        var policy = POLICIES.get(policyNumber);
        if (policy == null) {
            return InsuredPolicy.builder()
                    .policyNumber(policyNumber)
                    .insuredName("Asegurado genérico")
                    .insuredId("00.000.000")
                    .branch("General")
                    .product("Producto genérico")
                    .effectiveFrom(LocalDate.now().minusYears(1))
                    .effectiveTo(LocalDate.now().plusYears(1))
                    .upToDate(true)
                    .insuredAmount(new BigDecimal("100000"))
                    .deductible(new BigDecimal("10000"))
                    .coverages(List.of())
                    .applicableClauses(List.of())
                    .build();
        }
        return policy;
    }

    @Override
    public InsuredHistory getHistory(String insuredId) {
        return HISTORIES.getOrDefault(insuredId,
                InsuredHistory.builder()
                        .insuredId(insuredId)
                        .previousClaimsCount(0)
                        .totalAmountClaimed(BigDecimal.ZERO)
                        .customerSince(LocalDate.now())
                        .claims(List.of())
                        .build()
        );
    }
}
