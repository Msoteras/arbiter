package ar.edu.utn.frba.arbiter.auth.services;

import ar.edu.utn.frba.arbiter.auth.exceptions.InvalidLogoException;
import ar.edu.utn.frba.arbiter.auth.exceptions.LogoNotFoundException;
import ar.edu.utn.frba.arbiter.auth.models.entities.InsurerLogo;
import ar.edu.utn.frba.arbiter.auth.models.repositories.InsurerLogoRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InsurerLogoServiceTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0};

    @Mock
    private InsurerLogoRepository repository;

    @InjectMocks
    private InsurerLogoService service;

    @Test
    void storesAValidPngAsTheSingleRow() {
        service.replace(PNG, "image/png");

        ArgumentCaptor<InsurerLogo> saved = ArgumentCaptor.forClass(InsurerLogo.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(InsurerLogo.SINGLETON_ID);
        assertThat(saved.getValue().getContentType()).isEqualTo("image/png");
        assertThat(saved.getValue().getUpdatedAt()).isNotNull();
    }

    @Test
    void acceptsAnSvg() {
        byte[] svg = "<?xml version=\"1.0\"?><svg xmlns=\"http://www.w3.org/2000/svg\"/>"
                .getBytes(StandardCharsets.UTF_8);

        service.replace(svg, "image/svg+xml");

        verify(repository).save(any());
    }

    @Test
    void rejectsAFileWhoseBytesDontMatchItsDeclaredType() {
        byte[] notAPng = "hola".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.replace(notAPng, "image/png"))
                .isInstanceOf(InvalidLogoException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsAnUnsupportedType() {
        assertThatThrownBy(() -> service.replace(PNG, "image/gif"))
                .isInstanceOf(InvalidLogoException.class)
                .hasMessageContaining("PNG, JPG, WebP o SVG");
    }

    @Test
    void rejectsAFileOverTheLimit() {
        byte[] big = new byte[InsurerLogoService.MAX_BYTES + 1];
        System.arraycopy(PNG, 0, big, 0, PNG.length);

        assertThatThrownBy(() -> service.replace(big, "image/png"))
                .isInstanceOf(InvalidLogoException.class)
                .hasMessageContaining("256 KB");
    }

    @Test
    void readingWithoutALogoIsNotFound() {
        when(repository.findById(InsurerLogo.SINGLETON_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get()).isInstanceOf(LogoNotFoundException.class);
    }
}
