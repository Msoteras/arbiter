package ar.edu.utn.frba.arbiter.auth.controllers;

import ar.edu.utn.frba.arbiter.auth.dto.InsurerLogoResponse;
import ar.edu.utn.frba.arbiter.auth.models.entities.InsurerLogo;
import ar.edu.utn.frba.arbiter.auth.services.InsurerLogoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;

@RestController
@RequestMapping("/api/v1/auth/insurer/logo")
@RequiredArgsConstructor
@Tag(name = "Insurer logo", description = "Logo de la aseguradora que se ve en la barra superior")
public class InsurerLogoController {

    /**
     * An SVG is a document that can carry script. As an <img> it never runs, but opened directly it
     * would, on our origin: the sandbox keeps it inert there too.
     */
    private static final String IMAGE_CSP = "sandbox; default-src 'none'; style-src 'unsafe-inline'";

    private final InsurerLogoService service;

    @GetMapping
    @Operation(summary = "Leer el logo de la aseguradora",
            description = "Devuelve la imagen tal como se subió. 404 si la aseguradora no tiene logo.")
    public ResponseEntity<byte[]> get() {
        InsurerLogo logo = service.get();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(logo.getContentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePrivate())
                .header("Content-Security-Policy", IMAGE_CSP)
                .header("X-Content-Type-Options", "nosniff")
                .body(logo.getContent());
    }

    @PutMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Subir o reemplazar el logo",
            description = "PNG, JPG, WebP o SVG de hasta 256 KB. Reemplaza el anterior.")
    public InsurerLogoResponse put(@RequestParam("file") MultipartFile file) throws IOException {
        return new InsurerLogoResponse(service.replace(file.getBytes(), file.getContentType()));
    }

    @DeleteMapping
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Quitar el logo",
            description = "La barra superior vuelve a mostrar el monograma de la aseguradora.")
    public ResponseEntity<Void> delete() {
        service.remove();
        return ResponseEntity.noContent().build();
    }
}
