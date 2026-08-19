# Evidencia — caso 28: clasificación válida, expediente marcado como fallido

Capturado el 19/8/2026 contra Railway, antes de destrabarlo a mano.

```
cases         id=28  estado=CLASSIFICATION_FAILED  risk_band=NULL  attempts=120
llm_analysis  id=23  case_id=28  LLM_NO_RECOMIENDA_APROBAR  0.950  17:18:16
```

16:29 el referente lo destraba · 16:50 alguien lo marca fallido a los 120 intentos ·
17:18 la clasificación termina bien (49 min de latencia) y se persiste.

**El número no cierra.** Nuestro cases-service arranca con `interval-ms=20000 x max-attempts=540
= 180 min`, que es lo que dice el `application.yml`. Murió a los **120**, el default viejo del
código. Con 540 no habría fallado.

Mismo patrón que las transiciones duplicadas del 19/8: casos 17, 18 y 32 con dos filas idénticas
separadas por 4-5 s; el 19, con un solo stack corriendo, no se duplicó.
