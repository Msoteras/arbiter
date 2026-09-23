package ar.edu.utn.frba.arbiter.classification.config.tenant;

import lombok.RequiredArgsConstructor;
import org.hibernate.cfg.MultiTenancySettings;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/** Spring Boot doesn't auto-detect the multi-tenancy beans; they have to be pushed into Hibernate's properties by hand. */
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
