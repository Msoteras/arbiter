package ar.edu.utn.frba.arbiter.auth.controllers;

import ar.edu.utn.frba.arbiter.auth.dto.InsurerLogoResponse;
import ar.edu.utn.frba.arbiter.common.models.entities.InsurerLogo;
import ar.edu.utn.frba.arbiter.auth.services.InsurerLogoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Insurer logo", description = "Logo de la aseguradora que se ve en la barra superior")
public class InsurerLogoController {

    /** Only PNG and JPG are stored; the sandbox keeps any served file inert all the same. */
    private static final String IMAGE_CSP = "sandbox; default-src 'none'; style-src 'unsafe-inline'";

    private final InsurerLogoService service;

    @GetMapping("/insurer/logo")
    @Operation(summary = "Leer el logo de la aseguradora",
            description = "Devuelve la imagen tal como se subió. 404 si la aseguradora no tiene logo.")
    public ResponseEntity<byte[]> get() {
        return image(service.get());
    }

    @GetMapping("/insurers/{insurerSlug}/logo")
    @Operation(summary = "Leer el logo de una de mis aseguradoras",
            description = "Para el portal del asegurado: el logo de la aseguradora de cada siniestro. "
                    + "404 si no tiene logo o si el usuario no es de esa aseguradora.")
    public ResponseEntity<byte[]> getForInsurer(@PathVariable String insurerSlug,
                                                Authentication authentication) {
        return image(service.forInsurer(insurerSlug, authentication.getName()));
    }

    private static ResponseEntity<byte[]> image(InsurerLogo logo) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(logo.getContentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePrivate())
                .header("Content-Security-Policy", IMAGE_CSP)
                .header("X-Content-Type-Options", "nosniff")
                .body(logo.getContent());
    }

    @PutMapping(path = "/insurer/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Subir o reemplazar el logo",
            description = "PNG o JPG de hasta 256 KB. Reemplaza el anterior.")
    public InsurerLogoResponse put(@RequestParam("file") MultipartFile file) throws IOException {
        return new InsurerLogoResponse(service.replace(file.getBytes(), file.getContentType()));
    }

    @DeleteMapping("/insurer/logo")
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Quitar el logo",
            description = "La barra superior vuelve a mostrar el monograma de la aseguradora.")
    public ResponseEntity<Void> delete() {
        service.remove();
        return ResponseEntity.noContent().build();
    }
}
