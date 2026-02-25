package hu.fmdev.backend.repository.nodetypes;

import hu.fmdev.backend.domain.nodetypes.CommitmentClaim;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CommitmentClaimRepository extends MongoRepository<CommitmentClaim, String> {
}
