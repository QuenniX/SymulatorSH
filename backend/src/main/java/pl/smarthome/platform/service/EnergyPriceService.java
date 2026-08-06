package pl.smarthome.platform.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.smarthome.platform.api.dto.EnergyPriceDto;
import pl.smarthome.platform.domain.EnergyPriceEntity;
import pl.smarthome.platform.pse.PseApiClient;
import pl.smarthome.platform.repository.EnergyPriceRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Serwis do zarzadzania cenami energii z RDN.
 * Metody: pobierz z PSE i zapisz, wylistuj z bazy, zakres dat.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EnergyPriceService {

    private static final String MARKET_RDN = "RDN";
    /** Dzielnik do konwersji zl/MWh -> zl/kWh. */
    private static final BigDecimal MWH_TO_KWH = BigDecimal.valueOf(1000);

    private final EnergyPriceRepository repo;
    private final PseApiClient pseClient;

    /** Pobiera 24 ceny z PSE dla wybranej doby i zapisuje do bazy. Idempotentne (deduplikacja). */
    @Transactional
    public int fetchAndSave(LocalDate deliveryDate) {
        List<PseApiClient.HourPrice> prices = pseClient.fetchPrices(deliveryDate);
        int saved = 0;
        for (PseApiClient.HourPrice p : prices) {
            // Deduplikacja: jesli juz mamy taki rekord, pomijamy
            if (repo.findByMarketAndDeliveryDateAndHour(MARKET_RDN, p.deliveryDate(), p.hour()).isPresent()) {
                continue;
            }
            EnergyPriceEntity entity = EnergyPriceEntity.builder()
                    .id(UUID.randomUUID())
                    .market(MARKET_RDN)
                    .deliveryDate(p.deliveryDate())
                    .hour(p.hour())
                    .pricePlnMwh(p.pricePlnMwh())
                    .fetchedAt(Instant.now())
                    .build();
            repo.save(entity);
            saved++;
        }
        log.info("EnergyPriceService: dla {} zapisano {} nowych rekordow (z {} pobranych)",
                deliveryDate, saved, prices.size());
        return saved;
    }

    /** Lista 24 cen dla wybranej doby (posortowane godzinami). */
    @Transactional(readOnly = true)
    public List<EnergyPriceDto> listByDate(LocalDate deliveryDate) {
        return repo.findByMarketAndDeliveryDateOrderByHourAsc(MARKET_RDN, deliveryDate).stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * Cena dla konkretnej godziny (0-23) danej doby. Uzywane przez eksport CSV/XLSX
     * i cost calculator do liczenia kosztu per godzina.
     *
     * @return cena w zl/MWh albo Optional.empty() jesli brak w bazie
     */
    @Transactional(readOnly = true)
    public java.util.Optional<java.math.BigDecimal> getPriceForHour(String market, LocalDate deliveryDate, int hour) {
        return repo.findByMarketAndDeliveryDateAndHour(market, deliveryDate, hour)
                .map(pl.smarthome.platform.domain.EnergyPriceEntity::getPricePlnMwh);
    }

    /** Zakres dni - do analiz historycznych i porownan miesiac-do-miesiaca. */
    @Transactional(readOnly = true)
    public List<EnergyPriceDto> listByRange(LocalDate from, LocalDate to) {
        return repo.findRange(MARKET_RDN, from, to).stream()
                .map(this::toDto)
                .toList();
    }

    /** Backfill zakresu dni - dla jednorazowego zaimportowania historii. */
    @Transactional
    public BackfillResult backfill(LocalDate from, LocalDate to) {
        int totalSaved = 0;
        int failedDays = 0;
        LocalDate current = from;
        while (!current.isAfter(to)) {
            try {
                totalSaved += fetchAndSave(current);
            } catch (Exception e) {
                log.error("Backfill: blad dla {} - {}", current, e.getMessage());
                failedDays++;
            }
            current = current.plusDays(1);
        }
        return new BackfillResult(from, to, totalSaved, failedDays);
    }

    /** Sprawdza czy mamy komplet 24 rekordow dla dnia (do walidacji). */
    @Transactional(readOnly = true)
    public boolean isComplete(LocalDate deliveryDate) {
        return repo.countByMarketAndDeliveryDate(MARKET_RDN, deliveryDate) == 24;
    }

    private EnergyPriceDto toDto(EnergyPriceEntity e) {
        return EnergyPriceDto.builder()
                .market(e.getMarket())
                .deliveryDate(e.getDeliveryDate())
                .hour(e.getHour())
                .pricePlnMwh(e.getPricePlnMwh())
                .pricePlnKwh(e.getPricePlnMwh().divide(MWH_TO_KWH, 4, RoundingMode.HALF_UP))
                .build();
    }

    /** Wynik backfillu - do zwrocenia z endpointu admin. */
    public record BackfillResult(LocalDate from, LocalDate to, int savedRecords, int failedDays) { }
}
