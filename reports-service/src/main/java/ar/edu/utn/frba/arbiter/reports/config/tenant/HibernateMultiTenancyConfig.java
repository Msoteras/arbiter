package ar.edu.utn.frba.arbiter.reports.config.tenant;

import lombok.RequiredArgsConstructor;
import org.hibernate.cfg.MultiTenancySettings;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Wires the schema-per-tenant beans into Hibernate. Spring Boot doesn't auto-detect
 * {@link org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider} /
 * {@link org.hibernate.context.spi.CurrentTenantIdentifierResolver} beans the way it
 * does other Hibernate integration points — they have to be pushed into the properties
 * map by hand, under the exact keys {@link MultiTenancySettings} declares.
 */
@Configuration
@RequiredArgsConstructor
public class HibernateMultiTenancyConfig {

    private final TenantConnectionProvider connectionProvider;
    private final TenantIdentifierResolver identifierResolver;

    @Bean
    public HibernatePropertiesCustomizer multiTenancyCustomizer() {
        return (Map<String, Object> hibernateProperties) -> {
            hibernateProperties.put(MultiTenancySettings.MULTI_TENANT_CONNECTION_PROVIDER, connectionProvider);
            hibernateProperties.put(MultiTenancySettings.MULTI_TENANT_IDENTIFIER_RESOLVER, identifierResolver);
        };
    }
}
