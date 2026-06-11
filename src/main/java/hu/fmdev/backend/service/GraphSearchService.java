package hu.fmdev.backend.service;

import hu.fmdev.backend.domain.graph.EmailNode;
import hu.fmdev.backend.domain.graph.PersonNode;
import hu.fmdev.backend.logger.CentralLogger;
import hu.fmdev.backend.repository.graph.EmailNodeRepository;
import hu.fmdev.backend.repository.graph.PersonNodeRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
public class GraphSearchService {

    private final PersonNodeRepository personRepo;
    private final EmailNodeRepository emailNodeRepo;

    public GraphSearchService(PersonNodeRepository personRepo,
                              EmailNodeRepository emailNodeRepo) {
        this.personRepo    = personRepo;
        this.emailNodeRepo = emailNodeRepo;
    }

    public List<PersonNode> findCommunicationPartners(String email,
                                                      LocalDate from, LocalDate to) {
        try {
            if (from != null && to != null) {
                return personRepo.findCommunicationPartnersInRange(
                        email, from.toString(), to.toString());
            }
            return personRepo.findCommunicationPartners(email);
        } catch (Exception e) {
            CentralLogger.logError("KG findCommunicationPartners hiba", e);
            return List.of();
        }
    }

    public List<EmailNode> getThreadEmails(String threadId) {
        try {
            return emailNodeRepo.findByThreadId(threadId);
        } catch (Exception e) {
            CentralLogger.logError("KG getThreadEmails hiba", e);
            return List.of();
        }
    }

    public List<EmailNode> findEmailsByConceptProximity(String conceptName, int topK) {
        try {
            return emailNodeRepo.findByConceptProximity(conceptName, topK);
        } catch (Exception e) {
            CentralLogger.logError("KG findEmailsByConceptProximity hiba", e);
            return List.of();
        }
    }
}
