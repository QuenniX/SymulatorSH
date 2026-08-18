package pl.smarthome.platform.tariff;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pl.smarthome.platform.api.dto.CostBreakdown;
import pl.smarthome.platform.api.dto.HourlyCost;
import pl.smarthome.platform.api.dto.ProjectedCostBreakdown;
import pl.smarthome.platform.api.dto.ProjectedCostBreakdown.DailyCostPoint;
import pl.smarthome.platform.domain.EnergyPriceEntity;
import pl.smarthome.platform.influx.InfluxQueryService;
import pl.smarthome.platform.repository.EnergyPriceRepository;
import pl.smarthome.platform.service.EnergyPriceService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Glowny serwis liczacy koszt testu dla 3 taryf.
 *
 * <p><b>Algorytm:</b></p>
 * <ol>
 *   <li>Pobierz zuzycie z InfluxDB agregowane per godzina (Europe/Warsaw).</li>
 *   <li>Wczytaj ceny RDN z bazy dla zakresu dat testu (jednym zapytaniem, dla wydajnosci).</li>
 *   <li>Dla kazdej godziny testu policz koszt w G11, G12 i RDN.</li>
 *   <li>Zsumuj, wybierz najtansza, policz oszczednosc RDN vs G11.</li>
 * </ol>
 *
 * <p><b>Wybor roku:</b> rok bierzemy z pierwszej godziny testu - parametry cenowe
 * (G11/G12/narzuty RDN) sa dobierane per rok, bo w Polsce mrozenia i deregulacje
 * powoduja rozne stawki.</p>
 *
 * <p><b>Brak cen RDN:</b> jesli dla ktoregos godziny brakuje ceny w bazie
 * (nie backfillowany dzien, awaria PSE), uzywamy jako fallbacku sredniej ceny G11
 * z danego roku. Liczba brakujacych godzin jest raportowana w wyniku - zeby
 * bylo widac ze wynik jest przyblizony.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CostCalculatorService {

    private static final String MARKET_RDN = "RDN";
    /** Dzielnik zl/MWh -> zl/kWh. */
    private static final BigDecimal MWH_TO_KWH = BigDecimal.valueOf(1000);

    private final InfluxQueryService influxQueryService;
    private final EnergyPriceRepository priceRepository;
    private final EnergyPriceService energyPriceService;

    /**
     * Liczy koszty testu dla 3 taryf.
     *
     * @param testId ID testu w bazie
     * @return pelny breakdown z podzialem godzinowym
     */
    public CostBreakdown calculateForTest(UUID testId) {
        // Krok 1: zuzycie z InfluxDB
        Map<LocalDateTime, BigDecimal> hourlyKwh = influxQueryService.getHourlyEnergyKwh(testId);

        if (hourlyKwh.isEmpty()) {
            log.warn("Test {}: brak danych zuzycia w InfluxDB", testId);
            return emptyResult(testId);
        }

        // Krok 2: okresl rok i zakres dat
        LocalDateTime firstHour = hourlyKwh.keySet().iterator().next();
        int year = firstHour.getYear();
        LocalDate firstDay = firstHour.toLocalDate();
        LocalDate lastDay = hourlyKwh.keySet().stream()
                .reduce((a, b) -> a.isAfter(b) ? a : b)
                .orElse(firstHour)
                .toLocalDate();

        // Krok 2.5: AUTO-FETCH z PSE dla dat testu ktore nie maja kompletu 24 cen.
        // Bez tego, gdy test byl przeprowadzony w dniu ktorego nie backfillowalismy,
        // wpadalismy w fallback do G11 i wynik RDN byl mylacy.
        autoFetchMissingPricesForTestDates(hourlyKwh.keySet(), testId);

        // Krok 3: wczytaj ceny RDN dla calego zakresu (jednym zapytaniem)
        Map<PriceKey, BigDecimal> rdnPrices = loadRdnPricesForRange(firstDay, lastDay);
        log.debug("Test {}: wczytano {} cen RDN dla zakresu {}..{}",
                testId, rdnPrices.size(), firstDay, lastDay);

        // Krok 4: iteruj po godzinach i licz koszty
        BigDecimal totalKwh = BigDecimal.ZERO;
        BigDecimal totalG11 = BigDecimal.ZERO;
        BigDecimal totalG12 = BigDecimal.ZERO;
        BigDecimal totalRdn = BigDecimal.ZERO;
        int missingRdnHours = 0;
        List<HourlyCost> hourlyDetails = new ArrayList<>();

        for (Map.Entry<LocalDateTime, BigDecimal> entry : hourlyKwh.entrySet()) {
            LocalDateTime hour = entry.getKey();
            BigDecimal kwh = entry.getValue();
            int hourOfDay = hour.getHour();

            // G11: staly cennik
            BigDecimal g11UnitPrice = TariffParams.g11Price(year);
            BigDecimal g11Cost = kwh.multiply(g11UnitPrice);

            // G12: dzien lub noc zaleznie od godziny
            BigDecimal g12UnitPrice = TariffParams.g12PriceForHour(year, hour.toLocalDate(), hourOfDay);
            BigDecimal g12Cost = kwh.multiply(g12UnitPrice);

            // RDN: cena z PSE + narzuty + VAT
            BigDecimal wholesale = rdnPrices.get(new PriceKey(hour.toLocalDate(), hourOfDay));
            BigDecimal rdnUnitPrice;
            BigDecimal rdnCost;
            boolean missing = false;

            if (wholesale != null) {
                rdnUnitPrice = TariffParams.rdnFinalPrice(year, wholesale);
                rdnCost = kwh.multiply(rdnUnitPrice);
            } else {
                // Fallback: brak ceny RDN -> uzyj sredniej G11 z danego roku
                // (to nie zafalszuje wyniku bo raportujemy liczbe brakujacych godzin)
                missingRdnHours++;
                missing = true;
                rdnUnitPrice = g11UnitPrice;
                rdnCost = kwh.multiply(rdnUnitPrice);
            }

            totalKwh = totalKwh.add(kwh);
            totalG11 = totalG11.add(g11Cost);
            totalG12 = totalG12.add(g12Cost);
            totalRdn = totalRdn.add(rdnCost);

            hourlyDetails.add(HourlyCost.builder()
                    .hour(hour)
                    .kwh(kwh.setScale(4, RoundingMode.HALF_UP))
                    .g11PricePlnKwh(g11UnitPrice.setScale(4, RoundingMode.HALF_UP))
                    .g12PricePlnKwh(g12UnitPrice.setScale(4, RoundingMode.HALF_UP))
                    .rdnPricePlnKwh(rdnUnitPrice.setScale(4, RoundingMode.HALF_UP))
                    .g11CostPln(g11Cost.setScale(4, RoundingMode.HALF_UP))
                    .g12CostPln(g12Cost.setScale(4, RoundingMode.HALF_UP))
                    .rdnCostPln(rdnCost.setScale(4, RoundingMode.HALF_UP))
                    .rdnPriceMissing(missing)
                    .build());
        }

        // Krok 5: wybor najtanszej taryfy
        Tariff cheapest = pickCheapest(totalG11, totalG12, totalRdn);

        // Krok 6: oszczednosc RDN vs G11 (dodatni = RDN taniej)
        BigDecimal savings = calculateSavingsPercent(totalG11, totalRdn);

        log.info("Test {}: rok={}, godzin={}, kWh={}, G11={} zl, G12={} zl, RDN={} zl, najtansze={}",
                testId, year, hourlyDetails.size(),
                totalKwh.setScale(2, RoundingMode.HALF_UP),
                totalG11.setScale(2, RoundingMode.HALF_UP),
                totalG12.setScale(2, RoundingMode.HALF_UP),
                totalRdn.setScale(2, RoundingMode.HALF_UP),
                cheapest);

        return CostBreakdown.builder()
                .testId(testId)
                .year(year)
                .hoursAnalyzed(hourlyDetails.size())
                .hoursWithMissingRdnPrice(missingRdnHours)
                .totalKwh(totalKwh.setScale(3, RoundingMode.HALF_UP))
                .totalCostG11Pln(totalG11.setScale(2, RoundingMode.HALF_UP))
                .totalCostG12Pln(totalG12.setScale(2, RoundingMode.HALF_UP))
                .totalCostRdnPln(totalRdn.setScale(2, RoundingMode.HALF_UP))
                .cheapestTariff(cheapest)
                .rdnVsG11SavingsPercent(savings)
                .hourlyBreakdown(hourlyDetails)
                .build();
    }

    /**
     * Sprawdza kazda unikalna date testu - jesli nie ma kompletu 24 cen w bazie,
     * probuje pobrac z PSE. Zapobiega mylacym fallbackom RDN=G11 gdy uzytkownik
     * nie backfillowal dnia w ktorym byl test.
     */
    private void autoFetchMissingPricesForTestDates(Set<LocalDateTime> hours, UUID testId) {
        Set<LocalDate> testDates = new HashSet<>();
        for (LocalDateTime h : hours) {
            testDates.add(h.toLocalDate());
        }
        for (LocalDate date : testDates) {
            long count = priceRepository.countByMarketAndDeliveryDate("RDN", date);
            if (count >= 24) {
                continue; // komplet dla tej doby - pomijamy
            }
            try {
                log.info("Test {}: auto-fetch cen RDN dla {} (w bazie tylko {}/24)",
                        testId, date, count);
                int saved = energyPriceService.fetchAndSave(date);
                log.info("Test {}: auto-fetch dla {} - zapisano {} nowych cen",
                        testId, date, saved);
            } catch (Exception e) {
                // PSE moze nie miec danych (przyszlosc, awaria) - lecimy dalej z fallbackiem
                log.warn("Test {}: nie udalo sie auto-fetch cen RDN dla {}: {}",
                        testId, date, e.getMessage());
            }
        }
    }

    /**
     * Wczytuje ceny RDN z bazy dla zakresu dat i buduje mape {data, godzina} -> cena zl/kWh.
     * Jedno zapytanie do bazy zamiast N zapytan per godzina - szybciej.
     */
    private Map<PriceKey, BigDecimal> loadRdnPricesForRange(LocalDate from, LocalDate to) {
        List<EnergyPriceEntity> entities = priceRepository.findRange(MARKET_RDN, from, to);
        Map<PriceKey, BigDecimal> map = new HashMap<>();
        for (EnergyPriceEntity e : entities) {
            // Konwersja zl/MWh -> zl/kWh
            BigDecimal pricePlnKwh = e.getPricePlnMwh()
                    .divide(MWH_TO_KWH, 6, RoundingMode.HALF_UP);
            map.put(new PriceKey(e.getDeliveryDate(), e.getHour()), pricePlnKwh);
        }
        return map;
    }

    /** Wybiera najtansza z 3 taryf. */
    private Tariff pickCheapest(BigDecimal g11, BigDecimal g12, BigDecimal rdn) {
        Tariff cheapest = Tariff.G11;
        BigDecimal minCost = g11;
        if (g12.compareTo(minCost) < 0) { cheapest = Tariff.G12; minCost = g12; }
        if (rdn.compareTo(minCost) < 0) { cheapest = Tariff.RDN; }
        return cheapest;
    }

    /**
     * Liczy procent oszczednosci RDN wzgledem G11.
     * Dodatnia wartosc = RDN tansze. Ujemna = drozsze.
     * ((G11 - RDN) / G11) * 100
     */
    private BigDecimal calculateSavingsPercent(BigDecimal g11, BigDecimal rdn) {
        if (g11.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        return g11.subtract(rdn)
                .divide(g11, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** Pusty wynik gdy brak danych zuzycia. */
    private CostBreakdown emptyResult(UUID testId) {
        return CostBreakdown.builder()
                .testId(testId)
                .year(LocalDate.now().getYear())
                .hoursAnalyzed(0)
                .hoursWithMissingRdnPrice(0)
                .totalKwh(BigDecimal.ZERO)
                .totalCostG11Pln(BigDecimal.ZERO)
                .totalCostG12Pln(BigDecimal.ZERO)
                .totalCostRdnPln(BigDecimal.ZERO)
                .cheapestTariff(Tariff.G11)
                .rdnVsG11SavingsPercent(BigDecimal.ZERO)
                .hourlyBreakdown(List.of())
                .build();
    }

    /** Klucz do mapowania {data + godzina} -> cena RDN. */
    private record PriceKey(LocalDate date, int hour) { }

    // ======================================================================
    //  BOOTSTRAP: PROJEKCJA HISTORYCZNA
    //  Wyciaga 24-godzinny profil z testu i multiplikuje go przez wybrany
    //  okres historyczny cen RDN. Sluzy do analizy oplacalnosci taryf
    //  w oparciu o realna wariancje cen (dla pracy magisterskiej).
    // ======================================================================

    /**
     * Liczy hipotetyczny koszt profilu testu w okresie historycznym.
     *
     * <p>Metodologia:</p>
     * <ol>
     *   <li>Wyciagnij godzinowy profil z testu (uzycia energii per hour_of_day 0-23).
     *       Jesli test trwal wiele dni - liczymy srednia per godzine doby.</li>
     *   <li>Dla kazdego dnia w [from..to]:
     *       <ul>
     *         <li>G11: 24 godziny × profil × cena_G11_dla_roku</li>
     *         <li>G12: 24 godziny × profil × cena_G12(hour) (dzien/noc)</li>
     *         <li>RDN: 24 godziny × profil × realna cena RDN z bazy dla tej godziny</li>
     *       </ul>
     *   </li>
     *   <li>Zsumuj, policz statystyki (mediana, min, max, VaR 5%).</li>
     * </ol>
     *
     * <p>Rok parametrow cenowych = rok srodka okresu (np. dla 2024-06-01..2025-06-01
     * uzyjemy parametrow 2024).</p>
     *
     * @param testId ID testu (baseline profil zuzycia)
     * @param from   poczatek okresu
     * @param to     koniec okresu
     */
    public ProjectedCostBreakdown calculateProjectedCosts(UUID testId, LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from musi byc <= to");
        }

        // Krok 1: profil godzinowy testu (24 wartosci)
        BigDecimal[] hourlyProfileKwh = extractHourlyProfile(testId);
        if (hourlyProfileKwh == null) {
            return emptyProjectedResult(testId, from, to);
        }

        BigDecimal avgDailyKwh = BigDecimal.ZERO;
        for (BigDecimal v : hourlyProfileKwh) avgDailyKwh = avgDailyKwh.add(v);

        // Krok 2: rok srodka okresu -> parametry cenowe
        int tariffYear = TariffParams.ANALYSIS_YEAR;   // patrz TariffParams.ANALYSIS_YEAR

        // Krok 3: wczytaj ceny RDN dla calego okresu
        Map<PriceKey, BigDecimal> rdnPrices = loadRdnPricesForRange(from, to);

        // Krok 4: iteruj dzien po dniu, licz koszty
        List<DailyCostPoint> daily = new ArrayList<>();
        BigDecimal g11UnitPrice = TariffParams.g11Price(tariffYear);
        BigDecimal totalG11 = BigDecimal.ZERO;
        BigDecimal totalG12 = BigDecimal.ZERO;
        BigDecimal totalRdn = BigDecimal.ZERO;
        int daysWithFullPrices = 0;
        int daysInPeriod = 0;

        LocalDate cursor = from;
        while (!cursor.isAfter(to)) {
            daysInPeriod++;
            BigDecimal dayG11 = BigDecimal.ZERO;
            BigDecimal dayG12 = BigDecimal.ZERO;
            BigDecimal dayRdn = BigDecimal.ZERO;
            BigDecimal dayKwh = BigDecimal.ZERO;
            int hoursWithRdn = 0;

            for (int h = 0; h < 24; h++) {
                BigDecimal kwh = hourlyProfileKwh[h];
                dayKwh = dayKwh.add(kwh);

                // G11
                dayG11 = dayG11.add(kwh.multiply(g11UnitPrice));

                // G12
                BigDecimal g12Price = TariffParams.g12PriceForHour(tariffYear, cursor, h);
                dayG12 = dayG12.add(kwh.multiply(g12Price));

                // RDN
                BigDecimal wholesale = rdnPrices.get(new PriceKey(cursor, h));
                if (wholesale != null) {
                    BigDecimal rdnPrice = TariffParams.rdnFinalPrice(tariffYear, wholesale);
                    dayRdn = dayRdn.add(kwh.multiply(rdnPrice));
                    hoursWithRdn++;
                } else {
                    // Brak ceny -> fallback do G11 dla tej godziny
                    dayRdn = dayRdn.add(kwh.multiply(g11UnitPrice));
                }
            }

            if (hoursWithRdn == 24) daysWithFullPrices++;

            totalG11 = totalG11.add(dayG11);
            totalG12 = totalG12.add(dayG12);
            totalRdn = totalRdn.add(dayRdn);

            daily.add(DailyCostPoint.builder()
                    .date(cursor)
                    .kwh(dayKwh.setScale(3, RoundingMode.HALF_UP))
                    .costG11Pln(dayG11.setScale(2, RoundingMode.HALF_UP))
                    .costG12Pln(dayG12.setScale(2, RoundingMode.HALF_UP))
                    .costRdnPln(dayRdn.setScale(2, RoundingMode.HALF_UP))
                    .build());

            cursor = cursor.plusDays(1);
        }

        // Krok 5: statystyki dzienne dla RDN (do analizy ryzyka)
        List<BigDecimal> rdnDaily = new ArrayList<>();
        for (DailyCostPoint p : daily) rdnDaily.add(p.costRdnPln());
        Collections.sort(rdnDaily);

        BigDecimal rdnMin = rdnDaily.isEmpty() ? BigDecimal.ZERO : rdnDaily.get(0);
        BigDecimal rdnMax = rdnDaily.isEmpty() ? BigDecimal.ZERO : rdnDaily.get(rdnDaily.size() - 1);
        BigDecimal rdnMedian = rdnDaily.isEmpty() ? BigDecimal.ZERO
                : rdnDaily.get(rdnDaily.size() / 2);
        // VaR 5% = 95ty percentyl (5% dni gorzej) - prog powyzej ktorego jest 5% najgorszych dni
        int varIndex = rdnDaily.isEmpty() ? 0 : Math.min(rdnDaily.size() - 1, (int) (rdnDaily.size() * 0.95));
        BigDecimal rdnVaR = rdnDaily.isEmpty() ? BigDecimal.ZERO : rdnDaily.get(varIndex);
        // CVaR 5% = srednia z 5% najgorszych dni (Expected Shortfall).
        // Bierzemy od varIndex do konca sortowanej listy i liczymy srednia.
        // CVaR uzupelnia VaR o odpowiedz na pytanie "a JAK BARDZO jest zle w tym ogonie".
        BigDecimal rdnCVaR;
        if (rdnDaily.isEmpty()) {
            rdnCVaR = BigDecimal.ZERO;
        } else {
            List<BigDecimal> tail = rdnDaily.subList(varIndex, rdnDaily.size());
            BigDecimal sum = tail.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            rdnCVaR = sum.divide(BigDecimal.valueOf(tail.size()), 2, RoundingMode.HALF_UP);
        }

        Tariff cheapest = pickCheapest(totalG11, totalG12, totalRdn);
        BigDecimal savings = calculateSavingsPercent(totalG11, totalRdn);
        BigDecimal totalKwh = avgDailyKwh.multiply(BigDecimal.valueOf(daysInPeriod));

        log.info("Projekcja testu {}: okres={}..{} ({} dni), sredni kWh/dzien={}, G11={} zl, G12={} zl, RDN={} zl (median={}, VaR5%={}, CVaR5%={})",
                testId, from, to, daysInPeriod,
                avgDailyKwh.setScale(2, RoundingMode.HALF_UP),
                totalG11.setScale(2, RoundingMode.HALF_UP),
                totalG12.setScale(2, RoundingMode.HALF_UP),
                totalRdn.setScale(2, RoundingMode.HALF_UP),
                rdnMedian, rdnVaR, rdnCVaR);

        return ProjectedCostBreakdown.builder()
                .testId(testId)
                .from(from)
                .to(to)
                .year(tariffYear)
                .daysInPeriod(daysInPeriod)
                .daysWithFullPrices(daysWithFullPrices)
                .avgDailyKwh(avgDailyKwh.setScale(3, RoundingMode.HALF_UP))
                .totalKwh(totalKwh.setScale(2, RoundingMode.HALF_UP))
                .totalCostG11Pln(totalG11.setScale(2, RoundingMode.HALF_UP))
                .totalCostG12Pln(totalG12.setScale(2, RoundingMode.HALF_UP))
                .totalCostRdnPln(totalRdn.setScale(2, RoundingMode.HALF_UP))
                .cheapestTariff(cheapest)
                .rdnVsG11SavingsPercent(savings)
                .rdnDailyMinPln(rdnMin)
                .rdnDailyMaxPln(rdnMax)
                .rdnDailyMedianPln(rdnMedian)
                .rdnVaR5PercentPln(rdnVaR)
                .rdnCVaR5PercentPln(rdnCVaR)
                .dailyBreakdown(daily)
                .build();
    }

    /**
     * Wyciaga uśredniony profil dobowy z testu.
     * <p>Zwraca 24-elementowa tablice: pozycja [h] = srednia kWh w godzinie h doby
     * (usredniana po wszystkich dniach testu).</p>
     * @return null jesli brak danych zuzycia
     */
    private BigDecimal[] extractHourlyProfile(UUID testId) {
        Map<LocalDateTime, BigDecimal> hourlyKwh = influxQueryService.getHourlyEnergyKwh(testId);
        if (hourlyKwh.isEmpty()) return null;

        BigDecimal[] sums = new BigDecimal[24];
        int[] counts = new int[24];
        for (int i = 0; i < 24; i++) sums[i] = BigDecimal.ZERO;

        for (Map.Entry<LocalDateTime, BigDecimal> entry : hourlyKwh.entrySet()) {
            int h = entry.getKey().getHour();
            sums[h] = sums[h].add(entry.getValue());
            counts[h]++;
        }

        BigDecimal[] profile = new BigDecimal[24];
        for (int h = 0; h < 24; h++) {
            if (counts[h] == 0) {
                profile[h] = BigDecimal.ZERO; // godzina nieobserwowana w tescie
            } else {
                profile[h] = sums[h].divide(BigDecimal.valueOf(counts[h]), 6, RoundingMode.HALF_UP);
            }
        }
        return profile;
    }

    /** Pusty wynik projekcji gdy brak danych zuzycia. */
    private ProjectedCostBreakdown emptyProjectedResult(UUID testId, LocalDate from, LocalDate to) {
        return ProjectedCostBreakdown.builder()
                .testId(testId).from(from).to(to)
                .year(LocalDate.now().getYear())
                .daysInPeriod(0).daysWithFullPrices(0)
                .avgDailyKwh(BigDecimal.ZERO).totalKwh(BigDecimal.ZERO)
                .totalCostG11Pln(BigDecimal.ZERO).totalCostG12Pln(BigDecimal.ZERO)
                .totalCostRdnPln(BigDecimal.ZERO)
                .cheapestTariff(Tariff.G11)
                .rdnVsG11SavingsPercent(BigDecimal.ZERO)
                .rdnDailyMinPln(BigDecimal.ZERO).rdnDailyMaxPln(BigDecimal.ZERO)
                .rdnDailyMedianPln(BigDecimal.ZERO).rdnVaR5PercentPln(BigDecimal.ZERO)
                .rdnCVaR5PercentPln(BigDecimal.ZERO)
                .dailyBreakdown(List.of())
                .build();
    }
}
