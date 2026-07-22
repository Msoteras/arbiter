package ar.edu.utn.frba.arbiter.cases.adapters.mock;

import ar.edu.utn.frba.arbiter.cases.adapters.InsurerAdapter;
import ar.edu.utn.frba.arbiter.cases.dto.PolicyResponse;
import ar.edu.utn.frba.arbiter.cases.dto.PolicyResponse.Coverage;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Mock de la BD Aseguradora. El asegurado 42.987.654 tiene pólizas de DOS aseguradoras
 * distintas para ejercitar la vista centralizada multi-compañía. La implementación real
 * (API de cada aseguradora o BD compartida) reemplaza este bean sin tocar el resto.
 */
@Component
public class MockInsurerAdapter implements InsurerAdapter {

    private static final List<PolicyResponse> POLICIES = List.of(
            PolicyResponse.builder()
                    .policyNumber("POL-CEL-2026-042")
                    .insurerId("bbva").insurerName("BBVA Seguros")
                    .insuredName("Martina Fernández").insuredId("42.987.654")
                    .branch("Celulares").product("Celular Protegido Premium")
                    .effectiveFrom(LocalDate.of(2026, 1, 10)).effectiveTo(LocalDate.of(2027, 1, 10))
                    .upToDate(true)
                    .insuredAmount(new BigDecimal("1300000")).deductible(new BigDecimal("57000"))
                    .coverages(List.of(
                            Coverage.builder().code("COB-ROB-02").description("Robo y/o hurto — cobertura premium")
                                    .insuredAmount(new BigDecimal("1300000")).deductible(new BigDecimal("80000")).build(),
                            Coverage.builder().code("COB-ROT-03").description("Rotura accidental — sin límite de eventos")
                                    .insuredAmount(new BigDecimal("1300000")).deductible(new BigDecimal("57000")).build()
                    ))
                    .build(),
            PolicyResponse.builder()
                    .policyNumber("POL-HOG-2026-777")
                    .insurerId("zurich").insurerName("Zurich Argentina")
                    .insuredName("Martina Fernández").insuredId("42.987.654")
                    .branch("Hogar").product("Combinado Familiar")
                    .effectiveFrom(LocalDate.of(2026, 3, 1)).effectiveTo(LocalDate.of(2027, 3, 1))
                    .upToDate(true)
                    .insuredAmount(new BigDecimal("8000000")).deductible(new BigDecimal("120000"))
                    .coverages(List.of(
                            Coverage.builder().code("COB-INC-01").description("Incendio de edificio y contenido")
                                    .insuredAmount(new BigDecimal("8000000")).deductible(new BigDecimal("120000")).build(),
                            Coverage.builder().code("COB-ROB-HOG").description("Robo de contenido")
                                    .insuredAmount(new BigDecimal("2000000")).deductible(new BigDecimal("90000")).build()
                    ))
                    .build(),
            PolicyResponse.builder()
                    .policyNumber("POL-CEL-2024-001")
                    .insurerId("bbva").insurerName("BBVA Seguros")
                    .insuredName("Laura Fernández").insuredId("40.123.456")
                    .branch("Celulares").product("Celular Protegido Básico")
                    .effectiveFrom(LocalDate.of(2024, 3, 1)).effectiveTo(LocalDate.of(2027, 3, 1))
                    .upToDate(true)
                    .insuredAmount(new BigDecimal("400000")).deductible(new BigDecimal("50000"))
                    .coverages(List.of(
                            Coverage.builder().code("COB-ROB-01").description("Robo y/o hurto del equipo")
                                    .insuredAmount(new BigDecimal("400000")).deductible(new BigDecimal("50000")).build()
                    ))
                    .build(),
            PolicyResponse.builder()
                    .policyNumber("POL-CEL-2025-099")
                    .insurerId("bbva").insurerName("BBVA Seguros")
                    .insuredName("Marcelo Gómez").insuredId("30.555.777")
                    .branch("Celulares").product("Celular Protegido Premium")
                    .effectiveFrom(LocalDate.of(2025, 6, 1)).effectiveTo(LocalDate.of(2026, 6, 1))
                    .upToDate(true)
                    .insuredAmount(new BigDecimal("1200000")).deductible(new BigDecimal("80000"))
                    .coverages(List.of(
                            Coverage.builder().code("COB-ROB-02").description("Robo y/o hurto — cobertura premium")
                                    .insuredAmount(new BigDecimal("1200000")).deductible(new BigDecimal("80000")).build()
                    ))
                    .build()
    );

    @Override
    public Optional<PolicyResponse> findPolicy(String policyNumber) {
        return POLICIES.stream()
                .filter(p -> p.policyNumber().equalsIgnoreCase(policyNumber))
                .findFirst();
    }

    @Override
    public List<PolicyResponse> findPoliciesByInsured(String insuredId) {
        return POLICIES.stream()
                .filter(p -> p.insuredId().equals(insuredId))
                .toList();
    }
}
