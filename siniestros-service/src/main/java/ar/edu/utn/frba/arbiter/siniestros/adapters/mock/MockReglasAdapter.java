package ar.edu.utn.frba.arbiter.siniestros.adapters.mock;

import ar.edu.utn.frba.arbiter.siniestros.adapters.ReglasAdapter;
import ar.edu.utn.frba.arbiter.siniestros.dto.ReglasNegocio;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@Profile({"dev", "test", "default"})
public class MockReglasAdapter implements ReglasAdapter {

    private static final Map<String, ReglasNegocio> REGLAS_POR_RAMO = Map.of(
            "Celulares|Robo en vía pública", ReglasNegocio.builder()
                    .ramoId("Celulares")
                    .hechoGeneradorId("Robo en vía pública")
                    .reglas(List.of(
                            "El bien debe estar dentro del campo visual del asegurado al momento del robo",
                            "Se requiere denuncia policial dentro de las 48 hs del hecho",
                            "El asegurado debe acreditar titularidad del equipo (factura o documentación)",
                            "Si el asegurado tiene más de 2 siniestros en los últimos 12 meses, derivar a investigación"
                    ))
                    .exclusiones(List.of(
                            "Robo en domicilio declarado en póliza (se cubre por ramo Hogar)",
                            "Equipo fuera de la República Argentina",
                            "Robo con participación del asegurado o grupo conviviente"
                    ))
                    .criteriosFastTrack(List.of(
                            "Primer siniestro del asegurado",
                            "Denuncia policial presentada dentro de las 24 hs",
                            "Monto reclamado inferior al 50% de la suma asegurada",
                            "Existencia de testigos o cámaras de seguridad"
                    ))
                    .build(),

            "Celulares|Hurto", ReglasNegocio.builder()
                    .ramoId("Celulares")
                    .hechoGeneradorId("Hurto")
                    .reglas(List.of(
                            "Se requiere denuncia policial dentro de las 48 hs del hecho",
                            "El asegurado debe describir las circunstancias del hurto con detalle",
                            "Se requiere bloqueo del IMEI ante la empresa de telefonía"
                    ))
                    .exclusiones(List.of(
                            "Hurto por descuido en transporte público sin denuncia",
                            "Equipo dejado sin supervisión en lugar público"
                    ))
                    .criteriosFastTrack(List.of(
                            "Primer siniestro del asegurado",
                            "Monto reclamado inferior al 30% de la suma asegurada"
                    ))
                    .build(),

            "Celulares|Rotura accidental", ReglasNegocio.builder()
                    .ramoId("Celulares")
                    .hechoGeneradorId("Rotura accidental")
                    .reglas(List.of(
                            "Rotura accidental cubierta con franquicia del 20% del costo de reparación",
                            "Se requiere presupuesto de servicio técnico autorizado por la marca",
                            "Si el costo de reparación supera el 70% del valor del bien, se considera pérdida total"
                    ))
                    .exclusiones(List.of(
                            "Daño por líquido",
                            "Rotura intencional",
                            "Equipo con intervención previa no autorizada (root, jailbreak, reparación informal)"
                    ))
                    .criteriosFastTrack(List.of(
                            "Presupuesto de servicio técnico autorizado adjunto",
                            "Costo de reparación menor al 50% del valor asegurado",
                            "Sin siniestros previos del mismo tipo en los últimos 6 meses"
                    ))
                    .build()
    );

    @Override
    public ReglasNegocio obtenerReglas(String ramoId, String hechoGeneradorId) {
        String clave = ramoId + "|" + hechoGeneradorId;
        return REGLAS_POR_RAMO.getOrDefault(clave, reglasGenericasPorDefecto(ramoId, hechoGeneradorId));
    }

    private ReglasNegocio reglasGenericasPorDefecto(String ramoId, String hechoGeneradorId) {
        return ReglasNegocio.builder()
                .ramoId(ramoId)
                .hechoGeneradorId(hechoGeneradorId)
                .reglas(List.of(
                        "Se requiere documentación respaldatoria del hecho",
                        "Se requiere denuncia policial si corresponde al tipo de hecho"
                ))
                .exclusiones(List.of())
                .criteriosFastTrack(List.of())
                .build();
    }
}
