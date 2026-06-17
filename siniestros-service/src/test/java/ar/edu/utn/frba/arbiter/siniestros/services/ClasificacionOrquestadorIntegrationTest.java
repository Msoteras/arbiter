package ar.edu.utn.frba.arbiter.siniestros.services;

import ar.edu.utn.frba.arbiter.common.enums.Clasificacion;
import ar.edu.utn.frba.arbiter.siniestros.adapters.SiniestroClassifier;
import ar.edu.utn.frba.arbiter.siniestros.dto.ClasificacionRequest;
import ar.edu.utn.frba.arbiter.siniestros.dto.ClasificacionResponse;
import ar.edu.utn.frba.arbiter.siniestros.dto.DenunciaSiniestro;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Test unitario del flujo de orquestación: denuncia → adapters → classifier.
 * Usa mock del SiniestroClassifier para no requerir Ollama.
 *
 * Correr: mvn -pl siniestros-service test -Dtest=ClasificacionOrquestadorIntegrationTest
 */
@SpringBootTest
@ActiveProfiles("test")
class ClasificacionOrquestadorIntegrationTest {

    @MockBean
    private SiniestroClassifier classifierMock;

    @Autowired
    private ClasificacionOrquestador orquestador;

    @Test
    void denunciaReincidente_deberiaClasificarComoPotencialRiesgo() {
        // Setup: mock del classifier
        ClasificacionResponse mockRespuesta = ClasificacionResponse.builder()
                .clasificacion(Clasificacion.POTENCIAL_RIESGO)
                .confianza(0.85)
                .factores(List.of(
                        "4to siniestro en 18 meses (reincidente)",
                        "Descripción vaga: ubicación imprecisa, hora aproximada",
                        "Monto total reclamado alto: $18.500"
                ))
                .build();
        when(classifierMock.clasificar(any(ClasificacionRequest.class))).thenReturn(mockRespuesta);

        DenunciaSiniestro denuncia = DenunciaSiniestro.builder()
                .ramo("Celulares")
                .producto("Celular Protegido Premium")
                .hechoGenerador("Robo en vía pública")
                .bienAsegurado("iPhone 16 Pro Max 256GB - IMEI 353000000000099")
                .aseguradoDni("30.555.777")
                .polizaNumero("POL-CEL-2025-099")
                .descripcionLibre(
                        "Me robaron el celular el martes a la noche, estaba en la calle creo que por " +
                        "Palermo o tal vez Belgrano, no me acuerdo bien la dirección exacta. Eran como " +
                        "las 11 o 12 de la noche. Vino un tipo y me lo sacó de la mano. No vi bien " +
                        "porque estaba oscuro. Hice la denuncia al día siguiente."
                )
                .fechaHecho(LocalDateTime.of(2026, 6, 10, 23, 0))
                .lugarHecho("Palermo, CABA (ubicación imprecisa)")
                .adjuntosOCR(List.of(
                        "DENUNCIA POLICIAL Nro 2026/78901 - Comisaría 14va CABA\n" +
                        "Fecha: 12/06/2026 09:15 hs\n" +
                        "Denunciante: Marcelo Gómez DNI 30.555.777\n" +
                        "Hecho: Robo de teléfono celular\n" +
                        "Lugar: Av. Santa Fe y Bulnes, CABA (Palermo)\n" +
                        "Fecha del hecho declarada: 10/06/2026 aprox. 23:00 hs\n" +
                        "Observaciones: El denunciante no puede precisar la hora exacta ni la ubicación."
                ))
                .build();

        ClasificacionResponse respuesta = orquestador.clasificar(denuncia);

        imprimirResultado("REINCIDENTE — 4to siniestro, descripción vaga", respuesta, Clasificacion.POTENCIAL_RIESGO);

        assertThat(respuesta.clasificacion()).isEqualTo(Clasificacion.POTENCIAL_RIESGO);
        assertThat(respuesta.factores()).isNotEmpty();
        assertThat(respuesta.confianza()).isBetween(0.0, 1.0);
    }

    @Test
    void denunciaPrimerSiniestroConsistente_deberiaClasificarComoFastTrack() {
        // Setup: mock del classifier
        ClasificacionResponse mockRespuesta = ClasificacionResponse.builder()
                .clasificacion(Clasificacion.FAST_TRACK)
                .confianza(0.92)
                .factores(List.of(
                        "Primer siniestro: sin historial de fraude",
                        "Denuncia detallada: fecha, hora, ubicación, testigos",
                        "Documentación completa: factura + denuncia policial",
                        "Monto bajo: $389.990 vs suma asegurada"
                ))
                .build();
        when(classifierMock.clasificar(any(ClasificacionRequest.class))).thenReturn(mockRespuesta);

        DenunciaSiniestro denuncia = DenunciaSiniestro.builder()
                .ramo("Celulares")
                .producto("Celular Protegido Básico")
                .hechoGenerador("Robo en vía pública")
                .bienAsegurado("Motorola Edge 50 Pro - IMEI 351000000000042")
                .aseguradoDni("40.123.456")
                .polizaNumero("POL-CEL-2024-001")
                .descripcionLibre(
                        "El viernes 13 de junio de 2026 a las 19:45 hs aproximadamente, salía de mi " +
                        "trabajo en Av. Rivadavia 4200 (Almagro, CABA) caminando hacia la estación de " +
                        "subte Castro Barros. En la esquina de Rivadavia y Colombres, dos personas en " +
                        "una moto Honda Wave roja se subieron a la vereda, el acompañante me arrancó el " +
                        "celular de la mano derecha y se fueron por Colombres hacia el sur. Un vecino " +
                        "del local de la esquina me prestó su teléfono para llamar al 911."
                )
                .fechaHecho(LocalDateTime.of(2026, 6, 13, 19, 45))
                .lugarHecho("Av. Rivadavia y Colombres, Almagro, CABA")
                .adjuntosOCR(List.of(
                        "DENUNCIA POLICIAL Nro 2026/82341 - Comisaría 8va CABA\n" +
                        "Fecha: 13/06/2026 20:30 hs\n" +
                        "Denunciante: Laura Fernández DNI 40.123.456\n" +
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

        ClasificacionResponse respuesta = orquestador.clasificar(denuncia);

        imprimirResultado("PRIMER SINIESTRO — denuncia detallada con testigos", respuesta, Clasificacion.FAST_TRACK);

        assertThat(respuesta.clasificacion()).isEqualTo(Clasificacion.FAST_TRACK);
        assertThat(respuesta.factores()).isNotEmpty();
        assertThat(respuesta.confianza()).isBetween(0.0, 1.0);
    }

    @Test
    void roturaPantallaConPresupuesto_deberiaClasificarComoFastTrack() {
        // Setup: mock del classifier
        ClasificacionResponse mockRespuesta = ClasificacionResponse.builder()
                .clasificacion(Clasificacion.FAST_TRACK)
                .confianza(0.88)
                .factores(List.of(
                        "Siniestro de naturaleza simple: accidente doméstico",
                        "Documentación completa: factura + presupuesto de reparación",
                        "Daño verificable: rotura de pantalla, sin intervención previa",
                        "Monto reparación: $285.000 dentro del rango esperado"
                ))
                .build();
        when(classifierMock.clasificar(any(ClasificacionRequest.class))).thenReturn(mockRespuesta);

        DenunciaSiniestro denuncia = DenunciaSiniestro.builder()
                .ramo("Celulares")
                .producto("Celular Protegido Premium")
                .hechoGenerador("Rotura accidental")
                .bienAsegurado("Samsung Galaxy S25 Ultra - IMEI 354000000000063")
                .aseguradoDni("42.987.654")
                .polizaNumero("POL-CEL-2026-042")
                .descripcionLibre(
                        "El sábado 14 de junio de 2026 a la mañana, se me cayó el celular al piso " +
                        "mientras lo sacaba del bolsillo en la cocina de mi casa. Se me resbaló de " +
                        "la mano y cayó boca abajo sobre las baldosas. Se rompió la pantalla en la " +
                        "parte inferior derecha, tiene una rajadura que va de la esquina hasta el centro."
                )
                .fechaHecho(LocalDateTime.of(2026, 6, 14, 10, 0))
                .lugarHecho("Domicilio del asegurado")
                .adjuntosOCR(List.of(
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

        ClasificacionResponse respuesta = orquestador.clasificar(denuncia);

        imprimirResultado("ROTURA PANTALLA — caso simple con presupuesto", respuesta, Clasificacion.FAST_TRACK);

        assertThat(respuesta.clasificacion()).isEqualTo(Clasificacion.FAST_TRACK);
        assertThat(respuesta.factores()).isNotEmpty();
        assertThat(respuesta.confianza()).isBetween(0.0, 1.0);
    }

    private void imprimirResultado(String titulo, ClasificacionResponse respuesta, Clasificacion esperada) {
        boolean coincide = respuesta.clasificacion() == esperada;
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.printf( "║ %s%n", titulo);
        System.out.println("╠══════════════════════════════════════════════════════════════════╣");
        System.out.printf( "║ Esperada:  %-53s║%n", esperada);
        System.out.printf( "║ Obtenida:  %-53s║%n", respuesta.clasificacion());
        System.out.printf( "║ Confianza: %-53s║%n", String.format("%.2f", respuesta.confianza()));
        System.out.printf( "║ Coincide:  %-53s║%n", coincide ? "SÍ ✓" : "NO ✗");
        System.out.println("╠══════════════════════════════════════════════════════════════════╣");
        System.out.println("║ Factores:");
        for (String factor : respuesta.factores()) {
            String linea = factor.length() > 60 ? factor.substring(0, 59) + "…" : factor;
            System.out.printf("║   • %-60s║%n", linea);
        }
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");
    }
}
