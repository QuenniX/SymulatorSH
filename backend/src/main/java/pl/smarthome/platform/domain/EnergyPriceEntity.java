package pl.smarthome.platform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Godzinowa cena energii z Rynku Dnia Nastepnego.
 * 24 rekordy na dobe, pobierane raz dziennie z API PSE.
 */
@Entity
@Table(name = "energy_prices")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@AllArgsConstructor
@Builder
public class EnergyPriceEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 20)
    private String market;

    @Column(name = "delivery_date", nullable = false)
    private LocalDate deliveryDate;

    @Column(nullable = false)
    private Integer hour;

    @Column(name = "price_pln_mwh", nullable = false, precision = 10, scale = 2)
    private BigDecimal pricePlnMwh;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;
}
