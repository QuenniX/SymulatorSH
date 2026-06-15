package pl.smarthome.platform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "tests")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class TestEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column
    private String description;

    @Column(name = "config_json", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String configJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TestStatus status;

    @Column(name = "duration_days", nullable = false)
    private Integer durationDays;

    @Column(name = "speed_factor", nullable = false)
    private Integer speedFactor;

    @Column(name = "owner_id", nullable = false)
    private String ownerId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "error_message")
    private String errorMessage;
}
