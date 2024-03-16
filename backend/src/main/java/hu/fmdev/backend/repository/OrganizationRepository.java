package hu.fmdev.backend.repository;

import hu.fmdev.backend.domain.Organization;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrganizationRepository extends JpaRepository<Organization, Long> {
}
