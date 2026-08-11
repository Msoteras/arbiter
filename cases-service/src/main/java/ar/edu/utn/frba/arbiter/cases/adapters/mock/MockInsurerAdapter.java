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
 * Fallback en memoria cuando el perfil {@code insurer-db} NO está activo (tests / dev sin la
 * BD aseguradora seedeada). Refleja una porción del seed real ({@code db/seed-demo.sql}, esquema
 * {@code aseguradora_bbva}) para el asegurado de prueba (42.987.654). La fuente de verdad es
 * {@link ar.edu.utn.frba.arbiter.cases.adapters.db.InsurerDatabaseAdapter} cuando el perfil está
 * prendido.
 *
 * <p>Devuelve las pólizas de una sola compañía aunque el asegurado real tenga en dos: el recorte
 * multi-aseguradora depende del esquema por tenant, que acá no existe. Alcanza para el wizard.
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
                    .effectiveFrom(LocalDate.of(2026, 1, 1)).effectiveTo(LocalDate.of(2027, 1, 1))
                    .upToDate(true)
                    .insuredAmount(new BigDecimal("1300000")).deductible(new BigDecimal("130000.00"))
                    .coverages(List.of(
                            Coverage.builder().code("COB-1").description("Robo de celular")
                                    .insuredAmount(new BigDecimal("1300000")).deductible(new BigDecimal("130000.00")).build(),
                            Coverage.builder().code("COB-2").description("Hurto")
                                    .insuredAmount(new BigDecimal("650000")).deductible(new BigDecimal("65000.00")).build()
                    ))
                    .build(),
            PolicyResponse.builder()
                    .policyNumber("POL-TEC-2026-050")
                    .insurerId("1").insurerName("BBVA Seguros Argentina S.A.")
                    .insuredName("Martina Soteras").insuredId("42.987.654")
                    .contactEmail("martina.soteras@example.com").contactPhone("11-5555-0001")
                    .branch("Tecnología Portátil").insuredItem("Lenovo ThinkPad T14s Gen 5").product("Seguro de Tecnología Portátil")
                    .effectiveFrom(LocalDate.of(2026, 1, 1)).effectiveTo(LocalDate.of(2027, 1, 1))
                    .upToDate(true)
                    .insuredAmount(new BigDecimal("900000")).deductible(new BigDecimal("90000.00"))
                    .coverages(List.of(
                            Coverage.builder().code("COB-1").description("Robo de celular")
                                    .insuredAmount(new BigDecimal("900000")).deductible(new BigDecimal("90000.00")).build(),
                            Coverage.builder().code("COB-2").description("Daño accidental")
                                    .insuredAmount(new BigDecimal("120000")).deductible(new BigDecimal("12000.00")).build()
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
