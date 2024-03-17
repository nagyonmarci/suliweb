package hu.fmdev.backend.domain.nodetypes;

import lombok.NoArgsConstructor;

import javax.persistence.Entity;

@Entity
@NoArgsConstructor
public class Institution extends BaseNodeType {
    private String name;
    private String address;
}
