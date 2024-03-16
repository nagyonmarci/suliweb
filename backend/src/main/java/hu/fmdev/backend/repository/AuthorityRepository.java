package hu.fmdev.backend.repository;

import hu.fmdev.backend.domain.Authority;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthorityRepository extends JpaRepository<Authority, Long> {
}

