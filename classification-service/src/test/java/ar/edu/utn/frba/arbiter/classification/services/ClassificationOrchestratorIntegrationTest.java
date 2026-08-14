package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.common.enums.Classification;
import ar.edu.utn.frba.arbiter.classification.adapters.ClaimClassifier;
import ar.edu.utn.frba.arbiter.classification.dto.ClassificationRequest;
import ar.edu.utn.frba.arbiter.classification.dto.ClassificationResponse;
import ar.edu.utn.frba.arbiter.classification.dto.RuleFinding;
import ar.edu.utn.frba.arbiter.common.dto.ClaimReport;
import ar.edu.utn.frba.arbiter.classification.support.AbstractPersistenceIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit test for the orchestration flow: claim → adapters → classifier.
 * Uses a mock ClaimClassifier so Ollama is not required.
 *
 * Run: mvn -pl classification-service test -Dtest=ClassificationOrchestratorIntegrationTest
 */
@SpringBootTest
@ActiveProfiles("test")
class ClassificationOrchestratorIntegrationTest extends AbstractPersistenceIT {

    @MockitoBean
    private ClaimClassifier classifierMock;

    @Autowired
    private ClassificationOrchestrator orchestrator;

    @Test
    void recidivistClaim_shouldNotRecommendApproval() {
        ClassificationResponse mockResponse = ClassificationResponse.builder()
                .classification(Classification.LLM_NO_RECOMIENDA_APROBAR)
                .confidence(0.85)
                .factors(List.of(
                        "4th claim in 18 months (recidivist)",
                        "Vague description: imprecise location, approximate time",
                        "High total amount claimed: $18.500"
                ))
                .build();
        when(classifierMock.classify(any(ClassificationRequest.class))).thenReturn(mockResponse);

        ClaimReport claim = ClaimReport.builder()
                .branch("Celulares")
                .product("Celular Protegido Premium")
                .claimCause("Robo en vía pública")
                .insuredItem("iPhone 16 Pro Max 256GB - IMEI 353000000000099")
                .insuredId("30.555.777")
                .policyNumber("POL-CEL-2025-099")
                .description(
                        "Me robaron el celular el martes a la noche, estaba en la calle creo que por " +
                        "Palermo o tal vez Belgrano, no me acuerdo bien la dirección exacta. Eran como " +
                        "las 11 o 12 de la noche. Vino un tipo y me lo sacó de la mano. No vi bien " +
                        "porque estaba oscuro. Hice la report al día siguiente."
                )
                .eventDate(LocalDateTime.of(2026, 6, 10, 23, 0))
                .eventLocation("Palermo, CABA (ubicación imprecisa)")
                .attachmentsOcr(List.of(
                        "DENUNCIA POLICIAL Nro 2026/78901 - Comisaría 14va CABA\n" +
                        "Fecha: 12/06/2026 09:15 hs\n" +
                        "Reportnte: Marcelo Gómez DNI 30.555.777\n" +
                        "Hecho: Robo de teléfono celular\n" +
                        "Lugar: Av. Santa Fe y Bulnes, CABA (Palermo)\n" +
                        "Fecha del hecho declarada: 10/06/2026 aprox. 23:00 hs\n" +
                        "Observaciones: El reportnte no puede precisar la hora exacta ni la ubicación."
                ))
                .build();

        ClassificationResponse response = orchestrator.classify(claim);

        printResult("RECIDIVIST — 4th claim, vague description", response, Classification.LLM_NO_RECOMIENDA_APROBAR);

        assertThat(response.classification()).isEqualTo(Classification.LLM_NO_RECOMIENDA_APROBAR);
        assertThat(response.factors()).isNotEmpty();
        assertThat(response.confidence()).isBetween(0.0, 1.0);
    }

    @Test
    void firstTimeClaim_shouldClassifyAsFastTrack() {
        ClassificationResponse mockResponse = ClassificationResponse.builder()
                .classification(Classification.FAST_TRACK)
                .confidence(0.92)
                .factors(List.of(
                        "First claim: no fraud history",
                        "Detailed report: date, time, location, witnesses",
                        "Complete documentation: invoice + police report",
                        "Low amount: $389.990 vs insured sum"
                ))
                .build();
        when(classifierMock.classify(any(ClassificationRequest.class))).thenReturn(mockResponse);

        ClaimReport claim = ClaimReport.builder()
                .branch("Celulares")
                .product("Celular Protegido Básico")
                .claimCause("Robo en vía pública")
                .insuredItem("Motorola Edge 50 Pro - IMEI 351000000000042")
                .insuredId("40.123.456")
                .policyNumber("POL-CEL-2024-001")
                .description(
                        "El viernes 13 de junio de 2026 a las 19:45 hs aproximadamente, salía de mi " +
                        "trabajo en Av. Rivadavia 4200 (Almagro, CABA) caminando hacia la estación de " +
                        "subte Castro Barros. En la esquina de Rivadavia y Colombres, dos personas en " +
                        "una moto Honda Wave roja se subieron a la vereda, el acompañante me arrancó el " +
                        "celular de la mano derecha y se fueron por Colombres hacia el sur. Un vecino " +
                        "del local de la esquina me prestó su teléfono para llamar al 911."
                )
                .eventDate(LocalDateTime.of(2026, 6, 13, 19, 45))
                .eventLocation("Av. Rivadavia y Colombres, Almagro, CABA")
                .attachmentsOcr(List.of(
                        "DENUNCIA POLICIAL Nro 2026/82341 - Comisaría 8va CABA\n" +
                        "Fecha: 13/06/2026 20:30 hs\n" +
                        "Reportnte: Laura Fernández DNI 40.123.456\n" +
                        "Hecho: Robo de teléfono celular (modalidad motochorro)\n" +
                        "Lugar: Av. Rivadavia y Colombres, Almagro, CABA\n" +
                        "Testigos: Comerciante del local lindero confirmó haber presenciado el hecho\n" +
                        "Vehículo: Moto tipo Honda Wave color roja, sin patente visible",
                        "FACTURA DE COMPRA — Motorola Store, Unicenter\n" +
                        "Fecha: 20/03/2026\n" +
                        "Producto: Motorola Edge 50 Pro 256GB\n" +
                        "IMEI: 351000000000042\n" +
                        "Monto: $389.990\n" +
                        "Cliente: Laura Fernández DNI 40.123.456"
                ))
                .build();

        ClassificationResponse response = orchestrator.classify(claim);

        printResult("FIRST CLAIM — detailed report with witnesses", response, Classification.FAST_TRACK);

        assertThat(response.classification()).isEqualTo(Classification.FAST_TRACK);
        assertThat(response.factors()).isNotEmpty();
        assertThat(response.confidence()).isBetween(0.0, 1.0);
    }

    @Test
    void screenBreakWithQuote_shouldClassifyAsFastTrack() {
        ClassificationResponse mockResponse = ClassificationResponse.builder()
                .classification(Classification.FAST_TRACK)
                .confidence(0.88)
                .factors(List.of(
                        "Simple claim type: domestic accident",
                        "Complete documentation: invoice + repair quote",
                        "Verifiable damage: screen break, no prior intervention",
                        "Repair cost: $285.000 within expected range"
                ))
                .build();
        when(classifierMock.classify(any(ClassificationRequest.class))).thenReturn(mockResponse);

        ClaimReport claim = ClaimReport.builder()
                .branch("Celulares")
                .product("Celular Protegido Premium")
                .claimCause("Rotura accidental")
                .insuredItem("Samsung Galaxy S25 Ultra - IMEI 354000000000063")
                .insuredId("42.987.654")
                .policyNumber("POL-CEL-2026-042")
                .description(
                        "El sábado 14 de junio de 2026 a la mañana, se me cayó el celular al piso " +
                        "mientras lo sacaba del bolsillo en la cocina de mi casa. Se me resbaló de " +
                        "la mano y cayó boca abajo sobre las baldosas. Se rompió la pantalla en la " +
                        "parte inferior derecha, tiene una rajadura que va de la esquina hasta el centro."
                )
                .eventDate(LocalDateTime.of(2026, 6, 14, 10, 0))
                .eventLocation("Domicilio del insured")
                .attachmentsOcr(List.of(
                        "FACTURA DE COMPRA — Samsung Store, Alto Palermo\n" +
                        "Fecha: 10/01/2026\n" +
                        "Producto: Samsung Galaxy S25 Ultra 512GB\n" +
                        "IMEI: 354000000000063\n" +
                        "Monto: $1.299.990\n" +
                        "Cliente: Sofía Martínez DNI 42.987.654",
                        "PRESUPUESTO — Samsung Service Center, Av. Cabildo 2050\n" +
                        "Fecha: 14/06/2026\n" +
                        "Dispositivo: Samsung Galaxy S25 Ultra — IMEI 354000000000063\n" +
                        "Daño: Rotura de display AMOLED + digitalizador (zona inferior derecha)\n" +
                        "Costo estimado: $285.000 + IVA\n" +
                        "Nota: El equipo no presenta daños por líquido ni intervención previa."
                ))
                .build();

        ClassificationResponse response = orchestrator.classify(claim);

        printResult("SCREEN BREAK — simple case with repair quote", response, Classification.FAST_TRACK);

        assertThat(response.classification()).isEqualTo(Classification.FAST_TRACK);
        assertThat(response.factors()).isNotEmpty();
        assertThat(response.confidence()).isBetween(0.0, 1.0);
    }

    @Test
    void lowAmountFirstClaimUpToDate_shouldFastTrackWithoutCallingLLM() {
        ClaimReport claim = ClaimReport.builder()
                .branch("Celulares")
                .product("Celular Protegido Básico")
                .claimCause("Robo en vía pública")
                // Sin esto no hay Fast Track que probar: las reglas se scopean por cobertura
                // (MockRulesAdapter.RULES_BY_COVERAGE), así que un claim sin coverageId cae a las
                // genéricas, que no traen thresholds — el caso terminaba en el LLM y el mock sin
                // stub devolvía null (D18). Cobertura 1 = "Robo de celular", la del hecho generador
                // de este claim.
                .coverageId(1L)
                .insuredItem("Motorola Edge 50 Pro - IMEI 351000000000042")
                .insuredId("40.123.456")
                .policyNumber("POL-CEL-2024-001")
                .description("Robo en vía pública, report policial presentada el mismo día.")
                .eventDate(LocalDateTime.of(2026, 6, 13, 19, 45))
                .eventLocation("Av. Rivadavia y Colombres, Almagro, CABA")
                .claimedAmount(new BigDecimal("150000")) // 37.5% de la suma asegurada (400.000)
                .attachmentsOcr(List.of())
                .build();

        ClassificationResponse response = orchestrator.classify(claim);

        assertThat(response.classification()).isEqualTo(Classification.FAST_TRACK);
        assertThat(response.deterministicFastTrack()).isTrue();
        assertThat(response.factors()).isNotEmpty();
        verifyNoInteractions(classifierMock);
    }

    @Test
    void aboveThresholdAmount_shouldNotFastTrack_andDelegateToLLM() {
        ClassificationResponse mockResponse = ClassificationResponse.builder()
                .classification(Classification.LLM_SOLICITA_REVISION_MANUAL)
                .confidence(0.6)
                .factors(List.of("Monto reclamado elevado respecto de la suma asegurada"))
                .build();
        when(classifierMock.classify(any(ClassificationRequest.class))).thenReturn(mockResponse);

        ClaimReport claim = ClaimReport.builder()
                .branch("Celulares")
                .product("Celular Protegido Básico")
                .claimCause("Robo en vía pública")
                .insuredItem("Motorola Edge 50 Pro - IMEI 351000000000042")
                .insuredId("40.123.456")
                .policyNumber("POL-CEL-2024-001")
                .description("Robo en vía pública, monto reclamado cercano al total de la suma asegurada.")
                .eventDate(LocalDateTime.of(2026, 6, 13, 19, 45))
                .eventLocation("Av. Rivadavia y Colombres, Almagro, CABA")
                .claimedAmount(new BigDecimal("390000")) // 97.5% de la suma asegurada (400.000)
                .attachmentsOcr(List.of())
                .build();

        ClassificationResponse response = orchestrator.classify(claim);

        assertThat(response.classification()).isEqualTo(Classification.LLM_SOLICITA_REVISION_MANUAL);
        assertThat(response.deterministicFastTrack()).isFalse();
        verify(classifierMock).classify(any(ClassificationRequest.class));
    }

    @Test
    void hurtoOnRobberyCoverage_isNotCoveredByRule_withoutCallingLLM() {
        // Caso 6 del handoff ("Hurto no cubierto"): cobertura 1 = "Robo de celular", que solo cubre
        // el hecho generador Robo en vía pública (claim_cause 2) vía la regla COVERAGE_INCLUSION del
        // baseline — Hurto (claim_cause 3) no está en la lista. El hecho generador no cubierto corta
        // antes del Fast Track y del LLM, y deja el hallazgo para rule_result.
        ClaimReport claim = ClaimReport.builder()
                .branch("Celulares")
                .product("Celular Protegido Básico")
                .claimCause("Hurto")
                .coverageId(1L)
                .claimCauseId(3L)
                .insuredItem("Motorola Edge 50 Pro - IMEI 351000000000042")
                .insuredId("40.123.456")
                .policyNumber("POL-CEL-2024-001")
                .description("Denuncia de hurto sobre una cobertura de robo, que no lo cubre.")
                .eventDate(LocalDateTime.of(2026, 6, 13, 19, 45))
                .eventLocation("Av. Rivadavia y Colombres, Almagro, CABA")
                .claimedAmount(new BigDecimal("100000")) // bajo: fast-trackearía si estuviera cubierto
                .attachmentsOcr(List.of())
                .build();

        ClassificationResponse response = orchestrator.classify(claim);

        assertThat(response.classification()).isEqualTo(Classification.LLM_NO_RECOMIENDA_APROBAR);
        assertThat(response.deterministicFastTrack()).isFalse();
        assertThat(response.factors()).isNotEmpty();
        assertThat(response.ruleFindings())
                .anyMatch(f -> f.ruleId().equals(3L) && "FAIL".equals(f.result()));
        assertThat(response.ruleFindings()).extracting(RuleFinding::ruleType)
                .contains("COVERAGE_INCLUSION");
        verifyNoInteractions(classifierMock);
    }

    private void printResult(String title, ClassificationResponse response, Classification expected) {
        boolean match = response.classification() == expected;
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.printf( "║ %s%n", title);
        System.out.println("╠══════════════════════════════════════════════════════════════════╣");
        System.out.printf( "║ Expected:   %-53s║%n", expected);
        System.out.printf( "║ Obtained:   %-53s║%n", response.classification());
        System.out.printf( "║ Confidence: %-53s║%n", String.format("%.2f", response.confidence()));
        System.out.printf( "║ Match:      %-53s║%n", match ? "YES ✓" : "NO ✗");
        System.out.println("╠══════════════════════════════════════════════════════════════════╣");
        System.out.println("║ Factors:");
        for (String factor : response.factors()) {
            String line = factor.length() > 60 ? factor.substring(0, 59) + "…" : factor;
            System.out.printf("║   • %-60s║%n", line);
        }
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");
    }
}
