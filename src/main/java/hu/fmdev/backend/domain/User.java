package hu.fmdev.backend.domain;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.DBRef;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Document(collection = "users")
@Data
@NoArgsConstructor
public class User {
    @Id
    private String id;

    private String username;

    private String password;

    private String email;

    @DBRef
    private Organization organization;

    @DBRef(lazy = true)
    private Set<Authority> authorities = new HashSet<>();

    private List<String> allowedFileInfoIds = new ArrayList<>();
}
