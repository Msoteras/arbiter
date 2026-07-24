-- Datos de ejemplo para el frontend: siniestros ya clasificados con análisis forense
-- persistido, listos para que GET /api/v1/claims/{caseId} los devuelva.
--
-- NO corre el pipeline: inserta directo en classification_log el resultado + el
-- ImageForensicReport (JSON), tal como lo dejaría el flujo real.
--
-- Requisito: la app tiene que haber arrancado al menos una vez (ddl-auto=update crea
-- la tabla y la columna forensic_report). Correr después de eso:
--   docker compose exec -T postgres psql -U arbiter -d arbiter < classification-service/src/main/resources/db/seed-forensic-examples.sql
--
-- Los case_id 9001-9003 son ficticios y no chocan con casos reales.

-- Limpieza idempotente: volver a correr el seed no duplica.
DELETE FROM classification_log WHERE case_id IN (9001, 9002, 9003);

-- ─── 9001 · Rotura de celular — imagen encontrada publicada en internet ──────
-- El asegurado denuncia la rotura de un iPhone, pero la foto adjunta es una imagen
-- que circula en redes (resultado real del PoC con Google Vision). Señal de fraude:
-- el LLM pide revisión manual.
INSERT INTO classification_log
    (case_id, source, model, prompt_version, classification, confidence, factors, forensic_report, latency_ms, created_at)
VALUES (
    9001, 'LLM', 'qwen3-vl', 'classification-v1',
    'LLM_SOLICITA_REVISION_MANUAL', 0.610,
    '["La descripción es coherente con una rotura de pantalla","⚠ Imagen ''telefono.jpg'': publicada en internet — 0 exacta(s), 3 parcial(es), 10 página(s). Identificada como ''iphone''"]',
    '{
      "imagesAnalyzed": 1,
      "webSearchesPerformed": 1,
      "findings": [
        {
          "label": "damage_photo-0",
          "filename": "telefono.jpg",
          "internalMatches": [],
          "webFinding": {
            "fullMatches": 0,
            "partialMatches": 3,
            "pages": [
              {"url": "https://www.instagram.com/reel/DVZU2IOkSgU/", "title": "El iPhone 17e es la opción accesible de Apple"},
              {"url": "https://www.tiktok.com/@patog7/video/7612761027637153042", "title": "iPhone 17e: La opción accesible de Apple - TikTok"},
              {"url": "https://www.instagram.com/reel/DGRNAyVvkzw/", "title": "El nuevo iPhone 16e es el más barato de la familia"},
              {"url": "https://www.facebook.com/100083092068755/posts/797853212994407/", "title": "Este es el nuevo iPhone 17 - Facebook"}
            ],
            "bestGuessLabel": "iphone 17 e que salio ahora"
          }
        }
      ]
    }',
    2840, NOW()
);

-- ─── 9002 · Imagen reutilizada de otro siniestro (coincidencia interna) ───────
-- La foto coincide con un adjunto de un siniestro previo (case 8734). El LLM no
-- recomienda aprobar.
INSERT INTO classification_log
    (case_id, source, model, prompt_version, classification, confidence, factors, forensic_report, latency_ms, created_at)
VALUES (
    9002, 'LLM', 'qwen3-vl', 'classification-v1',
    'LLM_NO_RECOMIENDA_APROBAR', 0.720,
    '["El monto reclamado está dentro de la cobertura","⚠ Imagen ''foto_dano.jpg'': 96% similar a un adjunto del siniestro #8734 (''evidencia.jpg'')"]',
    '{
      "imagesAnalyzed": 1,
      "webSearchesPerformed": 0,
      "findings": [
        {
          "label": "damage_photo-0",
          "filename": "foto_dano.jpg",
          "internalMatches": [
            {"matchedCaseId": 8734, "matchedFilename": "evidencia.jpg", "similarity": 0.962}
          ],
          "webFinding": null
        }
      ]
    }',
    2610, NOW()
);

-- ─── 9003 · Foto genuina — sin hallazgos (control limpio) ────────────────────
-- Se analizó, no hay coincidencias internas ni en internet. El LLM recomienda aprobar.
INSERT INTO classification_log
    (case_id, source, model, prompt_version, classification, confidence, factors, forensic_report, latency_ms, created_at)
VALUES (
    9003, 'LLM', 'qwen3-vl', 'classification-v1',
    'LLM_RECOMIENDA_APROBAR', 0.880,
    '["La denuncia es consistente con la documentación","Imagen ''foto_posta.jpg'': sin coincidencias internas ni en internet"]',
    '{
      "imagesAnalyzed": 1,
      "webSearchesPerformed": 1,
      "findings": [
        {
          "label": "damage_photo-0",
          "filename": "foto_posta.jpg",
          "internalMatches": [],
          "webFinding": {
            "fullMatches": 0,
            "partialMatches": 0,
            "pages": [],
            "bestGuessLabel": null
          }
        }
      ]
    }',
    2510, NOW()
);
