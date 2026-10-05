package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.cases.dto.ProviderType;
import ar.edu.utn.frba.arbiter.cases.models.entities.ServiceProvider;
import ar.edu.utn.frba.arbiter.cases.support.AbstractPersistenceIT;
import ar.edu.utn.frba.arbiter.common.models.entities.Branch;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** With real Postgres: the generalist/specialist split is a subquery over the join table. */
@SpringBootTest
@Transactional
class ServiceProviderRepositoryTests extends AbstractPersistenceIT {

    @Autowired private ServiceProviderRepository serviceProviderRepository;
    @Autowired private BranchRepository branchRepository;

    @Test
    void offersTheBranchSpecialistsAndTheGeneralists() {
        Branch phones = branchRepository.save(Branch.builder().name("Celulares SP").build());
        Branch laptops = branchRepository.save(Branch.builder().name("Notebooks SP").build());
        Branch home = branchRepository.save(Branch.builder().name("Hogar SP").build());

        ServiceProvider multi = save("A · Celulares y notebooks", Set.of(phones, laptops), true);
        ServiceProvider generalist = save("B · Generalista", Set.of(), true);
        ServiceProvider other = save("C · Solo hogar", Set.of(home), true);
        ServiceProvider inactive = save("D · Inactivo", Set.of(phones), false);

        assertThat(serviceProviderRepository.findAvailableForBranch(
                        laptops.getId(), ProviderType.ESTUDIO_LIQUIDADOR))
                .extracting(ServiceProvider::getId)
                .contains(multi.getId(), generalist.getId())
                .doesNotContain(other.getId(), inactive.getId());
    }

    /** One row per provider even when it covers several branches: the join must not duplicate it. */
    @Test
    void aProviderInSeveralBranchesIsOfferedOnce() {
        Branch phones = branchRepository.save(Branch.builder().name("Celulares SP2").build());
        Branch laptops = branchRepository.save(Branch.builder().name("Notebooks SP2").build());
        ServiceProvider multi = save("E · Multirramo", Set.of(phones, laptops), true);

        assertThat(serviceProviderRepository.findAvailableForBranch(
                        phones.getId(), ProviderType.ESTUDIO_LIQUIDADOR))
                .filteredOn(provider -> provider.getId().equals(multi.getId()))
                .hasSize(1);
    }

    private ServiceProvider save(String name, Set<Branch> branches, boolean active) {
        return serviceProviderRepository.save(ServiceProvider.builder()
                .name(name)
                .email("proveedor@example.com")
                .branches(new HashSet<>(branches))
                .active(active)
                .build());
    }
}
