package hu.fmdev.backend.repository;

import hu.fmdev.backend.domain.FailedConversion;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface FailedConversionRepository extends MongoRepository<FailedConversion, String> {

    List<FailedConversion> findByResolved(boolean resolved);

    List<FailedConversion> findByFailureTypeAndResolved(
            FailedConversion.FailureType failureType, boolean resolved);
}
