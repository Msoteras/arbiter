package ar.edu.utn.frba.arbiter.reports.dto;

import java.math.BigDecimal;

/**
 * Cuánto fraude se determinó en el período y, sobre todo, cuánta plata no se pagó por haberlo
 * detectado. Es el indicador que justifica el costo de investigar: sin él, derivar a un perito
 * figura sólo como demora.
 *
 * <p>Sobre los expedientes DECIDIDOS en el período, no sobre los denunciados: el fraude se
 * determina durante la gestión, y anclarlo a la denuncia dejaría afuera el de los expedientes que
 * entraron el mes pasado y se resolvieron en éste, que son justamente los que más se investigan.
 *
 * @param decided         decididos en el período, el universo contra el que se lee el resto
 * @param fraudDetermined cuántos de ésos terminaron con el fraude determinado. Es una decisión
 *                        humana, no la banda de riesgo del modelo: el puntaje sugiere, el analista
 *                        determina
 * @param backedByExpert  cuántos de ésos tienen además un peritaje que lo confirmó. La diferencia
 *                        con el anterior son las determinaciones que tomó el analista por su cuenta
 * @param amountNotPaid   lo que reclamaban los expedientes con fraude determinado que además se
 *                        rechazaron. Los aprobados no entran: ahí se determinó el fraude pero se
 *                        pagó igual, así que no hay nada ahorrado que contar
 */
public record FraudDetection(
        long decided,
        long fraudDetermined,
        long backedByExpert,
        BigDecimal amountNotPaid
) {}
