package pl.smarthome.platform.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import pl.smarthome.platform.service.EnergyPriceService;

import java.time.LocalDate;

/**
 * Scheduled task pobierajacy godzinowe ceny z RDN codziennie.
 *
 * Glowny pobor: 14:00 - po publikacji przez PSE ok. 13:00.
 * Backup: co godzine sprawdzenie czy nie ma korekty dla dnia biezacego.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RdnFetcher {

    private final EnergyPriceService priceService;

    /**
     * Codzienny pobor cen na dobe nastepna.
     * Uruchamiany o 14:00 (PSE publikuje ok. 13:00).
     */
    @Scheduled(cron = "0 0 14 * * *")
    public void fetchTomorrowPrices() {
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        log.info("RdnFetcher: pobieram ceny na {}", tomorrow);
        try {
            int saved = priceService.fetchAndSave(tomorrow);
            log.info("RdnFetcher: sukces - zapisano {} rekordow dla {}", saved, tomorrow);
        } catch (Exception e) {
            log.error("RdnFetcher: blad przy pobieraniu {} - {}", tomorrow, e.getMessage());
        }
    }

    /**
     * Backup task: co godzine sprawdza czy dla dnia biezacego mamy komplet 24 rekordow.
     * Jesli nie - probuje pobrac. Naprawia sytuacje gdy PSE opublikowal opoznione ceny
     * albo gdy backend byl offline podczas glownego poboru o 14:00.
     */
    @Scheduled(cron = "0 30 * * * *")
    public void hourlyBackupCheck() {
        LocalDate today = LocalDate.now();
        if (priceService.isComplete(today)) {
            return; // wszystko w porzadku
        }
        log.warn("RdnFetcher backup: dla {} brak kompletu 24 rekordow, probuje pobrac", today);
        try {
            int saved = priceService.fetchAndSave(today);
            if (saved > 0) {
                log.info("RdnFetcher backup: uzupelniono {} rekordow dla {}", saved, today);
            }
        } catch (Exception e) {
            log.warn("RdnFetcher backup: nie udalo sie uzupelnic {} - {}", today, e.getMessage());
        }
    }
}
