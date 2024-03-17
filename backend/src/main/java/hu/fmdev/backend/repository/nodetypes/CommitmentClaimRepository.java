package hu.fmdev.backend.repository.nodetypes;

import hu.fmdev.backend.domain.nodetypes.CommitmentClaim;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CommitmentClaimRepository extends JpaRepository<CommitmentClaim, Long> {
}
