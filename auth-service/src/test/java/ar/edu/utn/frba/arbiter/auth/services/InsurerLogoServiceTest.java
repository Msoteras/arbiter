package ar.edu.utn.frba.arbiter.auth.services;

import ar.edu.utn.frba.arbiter.auth.exceptions.InvalidLogoException;
import ar.edu.utn.frba.arbiter.auth.exceptions.LogoNotFoundException;
import ar.edu.utn.frba.arbiter.auth.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.auth.models.repositories.InsurerLogoRepository;
import ar.edu.utn.frba.arbiter.auth.models.repositories.InsurerRepository;
import ar.edu.utn.frba.arbiter.auth.models.repositories.UserRepository;
import ar.edu.utn.frba.arbiter.common.models.entities.Insurer;
import ar.edu.utn.frba.arbiter.common.models.entities.InsurerLogo;
import ar.edu.utn.frba.arbiter.common.models.entities.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InsurerLogoServiceTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0};

    private static final long BBVA = 7L;

    @Mock
    private InsurerLogoRepository repository;

    @Mock
    private InsurerRepository insurers;

    @Mock
    private UserRepository users;

    @Mock
    private TenantResolver tenantResolver;

    @InjectMocks
    private InsurerLogoService service;

    @BeforeEach
    void resolveTenant() {
        TenantContext.set("arbiter_bbva");
        Insurer bbva = Insurer.builder().id(BBVA).schemaName("arbiter_bbva").build();
        lenient().when(insurers.findBySchemaName("arbiter_bbva")).thenReturn(Optional.of(bbva));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void storesAValidPngAsTheRequestingInsurersLogo() {
        service.replace(PNG, "image/png");

        ArgumentCaptor<InsurerLogo> saved = ArgumentCaptor.forClass(InsurerLogo.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getInsurerId()).isEqualTo(BBVA);
        assertThat(saved.getValue().getContentType()).isEqualTo("image/png");
        assertThat(saved.getValue().getUpdatedAt()).isNotNull();
    }

    @Test
    void rejectsAnSvg() {
        byte[] svg = "<?xml version=\"1.0\"?><svg xmlns=\"http://www.w3.org/2000/svg\"/>"
                .getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.replace(svg, "image/svg+xml"))
                .isInstanceOf(InvalidLogoException.class)
                .hasMessageContaining("PNG o JPG");
        verify(repository, never()).save(any());
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
                .hasMessageContaining("PNG o JPG");
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
        when(repository.findById(BBVA)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get()).isInstanceOf(LogoNotFoundException.class);
    }

    @Test
    void anInsuredReadsTheLogoOfAnInsurerTheyBelongTo() {
        InsurerLogo logo = new InsurerLogo(BBVA, PNG, "image/png", Instant.now());
        when(users.findByEmail("ana@mail.com")).thenReturn(Optional.of(User.builder().id(3L).build()));
        when(tenantResolver.insurerIdsFor(3L)).thenReturn(List.of(BBVA));
        when(repository.findById(BBVA)).thenReturn(Optional.of(logo));

        assertThat(service.forInsurer("arbiter_bbva", "ana@mail.com")).isSameAs(logo);
    }

    @Test
    void theLogoOfAnotherInsurerIsNotFound() {
        when(users.findByEmail("ana@mail.com")).thenReturn(Optional.of(User.builder().id(3L).build()));
        when(tenantResolver.insurerIdsFor(3L)).thenReturn(List.of(99L));

        assertThatThrownBy(() -> service.forInsurer("arbiter_bbva", "ana@mail.com"))
                .isInstanceOf(LogoNotFoundException.class);
        verify(repository, never()).findById(any());
    }
}
