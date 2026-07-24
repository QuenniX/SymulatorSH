package pl.smarthome.platform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "templates")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class TemplateEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column
    private String description;

    @Column(name = "config_json", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String configJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
