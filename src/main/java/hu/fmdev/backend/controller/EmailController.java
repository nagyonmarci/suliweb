package hu.fmdev.backend.controller;

import hu.fmdev.backend.domain.Email;
import hu.fmdev.backend.repository.EmailRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/emails")
public class EmailController {

    @Autowired
    private EmailRepository emailRepository;

    @GetMapping
    public List<Email> getAllEmails() {
        return emailRepository.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Email> getEmailById(@PathVariable("id") String id) {
        Email email = emailRepository.findById(id).orElse(null);
        if (email != null) {
            return ResponseEntity.ok(email);
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping
    public ResponseEntity<Email> saveEmail(@RequestBody Email email) {
        Email savedEmail = emailRepository.save(email);
        return ResponseEntity.status(HttpStatus.CREATED).body(savedEmail);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Email> updateEmail(@PathVariable("id") String id, @RequestBody Email email) {
        if (!emailRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        email.setId(id);
        Email updatedEmail = emailRepository.save(email);
        return ResponseEntity.ok(updatedEmail);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteEmail(@PathVariable("id") String id) {
        if (!emailRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        emailRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
