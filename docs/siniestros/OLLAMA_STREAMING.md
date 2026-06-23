# Ollama y respuestas en streaming

## El problema

Cuando Ollama procesa una solicitud `/api/chat`, devuelve la respuesta **línea por línea en formato JSONL** (JSON Lines), no como un único JSON:

```
{"message": {"content": "Primer"}, "done": false}
{"message": {"content": " fragmento"}, "done": false}
{"message": {"content": " del", "done": false}
{"message": {"content": " análisis"}, "done": false}
...
{"message": {"content": ""}, "done": true}
```

### ¿Qué pasaba antes?

El adapter intentaba parsear como un único `ChatResponse`:
```java
ChatResponse chatResponse = ollamaRestClient.post()
    .uri("/api/chat")
    .body(chatRequest)
    .retrieve()
    .body(ChatResponse.class);  // ❌ Espera un JSON completo
```

Pero recibía solo la primera línea del stream → error:
```
MismatchedInputException: No content to map due to end-of-input
```

---

## La solución

Leer el stream línea por línea, extrayendo `message.content` de cada chunk:

```java
String respuestaCompleta = ollamaRestClient.post()
    .uri("/api/chat")
    .body(chatRequest)
    .retrieve()
    .body(InputStream.class);  // ✅ Lee todo el stream

String contenidoFinal = leerRespuestaStreaming(respuestaCompleta);
```

Luego procesar línea por línea:
```java
private String leerRespuestaStreaming(Object inputStream) {
    StringBuilder contenidoCompleto = new StringBuilder();
    
    try (BufferedReader reader = new BufferedReader(
            new InputStreamReader((java.io.InputStream) inputStream, StandardCharsets.UTF_8))) {
        String linea;
        while ((linea = reader.readLine()) != null) {
            if (!linea.isEmpty()) {
                // Parsear cada línea como JSON
                Map<String, Object> chunk = objectMapper.readValue(linea, Map.class);
                Map<String, Object> message = (Map<String, Object>) chunk.get("message");
                if (message != null) {
                    String content = (String) message.get("content");
                    if (content != null) {
                        contenidoCompleto.append(content);
                    }
                }
            }
        }
    }
    return contenidoCompleto.toString().trim();
}
```

---

## Por qué funciona

1. **RestClient.body(InputStream.class)** permite acceder al stream HTTP completo sin intenta parsear como JSON
2. **BufferedReader.readLine()** lee línea por línea (separadas por `\n`)
3. **Acumular content** de cada chunk concatena los fragmentos en el orden correcto
4. **Luego parsear** el contenido completo como JSON

---

## Debugging: si vuelve a fallar

Agregar logs en `leerRespuestaStreaming`:
```java
log.debug("[Ollama] Chunk: {}", linea);  // Ver cada línea que llega
log.debug("[Ollama] Content acumulado hasta ahora: {}", contenidoCompleto.toString());
```

O modificar el log en `clasificar`:
```java
log.debug("[Ollama] Contenido final raw: {}", contenidoFinal);
```

---

## Notas

- **stream: false** en el request NO desactiva el streaming. Ollama devuelve en streaming de todas formas.
- Si querés evitar el streaming completamente, necesitarías hacer streaming en el cliente (leer chunks hasta `done: true`), que es lo que hace nuestra solución.
- El `num_ctx: 32768` en `options` es importante — fija el contexto a 32k tokens como requiere la arquitectura.

---

## Referencias

- API de Ollama: https://github.com/ollama/ollama/blob/main/docs/api.md
- RFC 7464 (JSON Text Sequences): https://tools.ietf.org/html/rfc7464
