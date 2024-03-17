package hu.fmdev.backend.domain.nodetypes;

import lombok.NoArgsConstructor;

import javax.persistence.Entity;
import javax.persistence.Lob;

@Entity
@NoArgsConstructor
public class Contract extends BaseNodeType {
    private String title;
    @Lob
    private String content;
}
