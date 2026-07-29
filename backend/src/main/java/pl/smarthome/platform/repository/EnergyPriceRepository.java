package pl.smarthome.platform.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import pl.smarthome.platform.domain.EnergyPriceEntity;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EnergyPriceRepository extends JpaRepository<EnergyPriceEntity, UUID> {

    /** Wszystkie 24 ceny dla konkretnego dnia (jednego rynku). */
    List<EnergyPriceEntity> findByMarketAndDeliveryDateOrderByHourAsc(
            String market, LocalDate deliveryDate);

    /** Konkretna cena godzinowa (do sprawdzenia unikalnosci). */
    Optional<EnergyPriceEntity> findByMarketAndDeliveryDateAndHour(
            String market, LocalDate deliveryDate, Integer hour);

    /** Zakres dni - do analiz historycznych. */
    @Query("""
        SELECT p FROM EnergyPriceEntity p
        WHERE p.market = :market
          AND p.deliveryDate BETWEEN :from AND :to
        ORDER BY p.deliveryDate ASC, p.hour ASC
        """)
    List<EnergyPriceEntity> findRange(
            @Param("market") String market,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    /** Sprawdza czy dla danego dnia mamy komplet 24 rekordow (do walidacji backfilla). */
    long countByMarketAndDeliveryDate(String market, LocalDate deliveryDate);
}
