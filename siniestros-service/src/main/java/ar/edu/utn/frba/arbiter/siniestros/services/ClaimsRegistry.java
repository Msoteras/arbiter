package ar.edu.utn.frba.arbiter.siniestros.services;

import ar.edu.utn.frba.arbiter.siniestros.dto.DenunciaSiniestro;
import ar.edu.utn.frba.arbiter.siniestros.exceptions.ClasificacionInvalidaException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Almacén en memoria de claims y los adjuntos analizados acumulados para cada uno.
 * No hay persistencia todavía (el modelo de datos del módulo no está implementado);
 * cuando exista, esto se reemplaza por la entidad Siniestro/Denuncia en BD.
 *
 * Un claim puede recibir adjuntos en más de una ronda (ej. el modelo devuelve
 * FALTA_DOCUMENTACION y el asegurado sube lo que faltaba más adelante) — por eso
 * el registro no se borra al clasificar, queda disponible para reclasificar.
 */
@Service
public class ClaimsRegistry {

    private final Map<Long, DenunciaSiniestro> claims = new ConcurrentHashMap<>();
    private final Map<Long, List<String>> attachmentsOcrByClaim = new ConcurrentHashMap<>();

    public void register(Long claimId, DenunciaSiniestro claim) {
        claims.put(claimId, claim);
        attachmentsOcrByClaim.put(claimId, new ArrayList<>());
    }

    public void addExtractedText(Long claimId, String extractedText) {
        List<String> texts = attachmentsOcrByClaim.get(claimId);
        if (texts == null) {
            throw new ClasificacionInvalidaException("Claim " + claimId + " does not exist");
        }
        synchronized (texts) {
            texts.add(extractedText);
        }
    }

    /**
     * Devuelve una foto del claim con todos los adjuntos analizados hasta el momento,
     * lista para mandar a clasificar. No elimina el registro: puede volver a pedirse
     * después de subir más adjuntos.
     */
    public DenunciaSiniestro snapshot(Long claimId) {
        DenunciaSiniestro claim = claims.get(claimId);
        if (claim == null) {
            throw new ClasificacionInvalidaException("Claim " + claimId + " does not exist");
        }
        List<String> extractedTexts;
        List<String> texts = attachmentsOcrByClaim.get(claimId);
        synchronized (texts) {
            extractedTexts = List.copyOf(texts);
        }

        return DenunciaSiniestro.builder()
                .branch(claim.branch())
                .product(claim.product())
                .claimCause(claim.claimCause())
                .insuredItem(claim.insuredItem())
                .insuredId(claim.insuredId())
                .policyNumber(claim.policyNumber())
                .description(claim.description())
                .eventDate(claim.eventDate())
                .eventLocation(claim.eventLocation())
                .attachmentsOcr(extractedTexts)
                .build();
    }
}
