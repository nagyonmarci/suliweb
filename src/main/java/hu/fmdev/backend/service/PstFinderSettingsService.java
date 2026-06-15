package hu.fmdev.backend.service;

import hu.fmdev.backend.domain.PstFinderSettings;
import hu.fmdev.backend.repository.PstFinderSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PstFinderSettingsService {

    private final PstFinderSettingsRepository repository;

    public PstFinderSettings get() {
        return repository.findById("singleton").orElseGet(PstFinderSettings::new);
    }

    public PstFinderSettings save(List<String> searchDirectories, List<String> excludedDirectories) {
        PstFinderSettings doc = repository.findById("singleton").orElseGet(PstFinderSettings::new);
        doc.setSearchDirectories(searchDirectories != null ? searchDirectories : List.of());
        doc.setExcludedDirectories(excludedDirectories != null ? excludedDirectories : List.of());
        return repository.save(doc);
    }
}
