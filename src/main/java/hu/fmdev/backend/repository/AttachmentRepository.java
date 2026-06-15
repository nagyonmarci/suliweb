package hu.fmdev.backend.repository;

import hu.fmdev.backend.domain.Attachment;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface AttachmentRepository extends MongoRepository<Attachment, String> {
    List<Attachment> findByEmailId(String emailId);

    List<Attachment> findByEmailIdIn(Collection<String> emailIds);
}
