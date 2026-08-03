package pl.smarthome.platform.api.dto;

import lombok.Builder;
import pl.smarthome.platform.tariff.Tariff;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Wynik projekcji historycznej: profil zuzycia z testu × ceny RDN z wybranego okresu.
 *
 * <p>Odpowiada na pytanie: "Ile by kosztowal ten profil zuzycia, gdyby dzialal
 * kazdy dzien w okresie [from..to] przy realnych historycznych cenach RDN?"</p>
 *
 * <p>Kluczowe dla czesci badawczej pracy magisterskiej - pozwala policzyc:</p>
 * <ul>
 *   <li>Sredni koszt roczny w 3 taryfach</li>
 *   <li>Wariancje kosztu dziennego (min, max, kwartyle)</li>
 *   <li>Value at Risk 5% (jak wielka strata w najgorszych 5% dni)</li>
 *   <li>Sezonowa zmiennosc</li>
 * </ul>
 *
 * @param testId               ID testu ktorego profilu uzywamy
 * @param from                 poczatek okresu cen RDN
 * @param to                   koniec okresu cen RDN
 * @param year                 rok wg ktorego dobrano parametry cenowe G11/G12/RDN
 * @param daysInPeriod         liczba dni w okresie
 * @param daysWithFullPrices   liczba dni z kompletem 24 cen RDN w bazie
 * @param avgDailyKwh          srednie dzienne zuzycie z profilu testu (kWh/dzien)
 * @param totalKwh             calkowite zuzycie w okresie (avgDailyKwh × daysInPeriod)
 * @param totalCostG11Pln      laczny koszt w G11 dla calego okresu
 * @param totalCostG12Pln      laczny koszt w G12
 * @param totalCostRdnPln      laczny koszt w RDN
 * @param cheapestTariff       najtansza taryfa
 * @param rdnVsG11SavingsPercent oszczednosc RDN wzgl. G11 (%)
 * @param rdnDailyMinPln       dzien o najnizszym koszcie RDN (najkorzystniejszy)
 * @param rdnDailyMaxPln       dzien o najwyzszym koszcie RDN (worst case)
 * @param rdnDailyMedianPln    mediana dziennego kosztu RDN
 * @param rdnVaR5PercentPln    Value at Risk 5% - koszt w 95tym percentylu (5% dni gorsze)
 * @param rdnCVaR5PercentPln   Conditional VaR (Expected Shortfall) - srednia z 5% najgorszych dni.
 *                             Uzupelnia VaR o odpowiedz "a JAK BARDZO jest zle w tym ogonie". CVaR >= VaR zawsze.
 * @param dailyBreakdown       koszt dzienny w 3 taryfach - do wykresu liniowego
 */
@Builder
public record ProjectedCostBreakdown(
        UUID testId,
        LocalDate from,
        LocalDate to,
        int year,
        int daysInPeriod,
        int daysWithFullPrices,

        BigDecimal avgDailyKwh,
        BigDecimal totalKwh,

        BigDecimal totalCostG11Pln,
        BigDecimal totalCostG12Pln,
        BigDecimal totalCostRdnPln,

        Tariff cheapestTariff,
        BigDecimal rdnVsG11SavingsPercent,

        // Statystyki dzienne dla RDN (do analizy ryzyka)
        BigDecimal rdnDailyMinPln,
        BigDecimal rdnDailyMaxPln,
        BigDecimal rdnDailyMedianPln,
        BigDecimal rdnVaR5PercentPln,
        BigDecimal rdnCVaR5PercentPln,

        List<DailyCostPoint> dailyBreakdown
) {

    /** Jeden punkt dzienny - do wykresu roczna projekcji. */
    @Builder
    public record DailyCostPoint(
            LocalDate date,
            BigDecimal kwh,
            BigDecimal costG11Pln,
            BigDecimal costG12Pln,
            BigDecimal costRdnPln
    ) { }
}
