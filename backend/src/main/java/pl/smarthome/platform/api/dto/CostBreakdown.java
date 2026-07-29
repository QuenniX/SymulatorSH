package pl.smarthome.platform.api.dto;

import lombok.Builder;
import pl.smarthome.platform.tariff.Tariff;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Wynik kalkulacji kosztu testu w 3 taryfach.
 * Ten obiekt frontend dostaje z endpointu {@code GET /api/v1/tests/{id}/costs}.
 *
 * @param testId                    ID testu
 * @param year                      rok wg ktorego dobrano parametry cenowe (2024/2025/2026)
 * @param hoursAnalyzed             ile godzin danych zagregowano z InfluxDB
 * @param hoursWithMissingRdnPrice  ile godzin nie mialo ceny RDN w bazie
 *                                  (fallback do sredniej G11)
 * @param totalKwh                  laczne zuzycie w kWh
 * @param totalCostG11Pln           laczny koszt w G11 (zl brutto)
 * @param totalCostG12Pln           laczny koszt w G12 (zl brutto)
 * @param totalCostRdnPln           laczny koszt w RDN (zl brutto)
 * @param cheapestTariff            ktora taryfa najtansza dla tego testu
 * @param rdnVsG11SavingsPercent    procent oszczednosci RDN wzgledem G11
 *                                  (wartosc dodatnia = RDN tansze, ujemna = drozsze)
 * @param hourlyBreakdown           szczegoly per godzina (do wykresu)
 */
@Builder
public record CostBreakdown(
        UUID testId,
        int year,
        int hoursAnalyzed,
        int hoursWithMissingRdnPrice,
        BigDecimal totalKwh,
        BigDecimal totalCostG11Pln,
        BigDecimal totalCostG12Pln,
        BigDecimal totalCostRdnPln,
        Tariff cheapestTariff,
        BigDecimal rdnVsG11SavingsPercent,
        List<HourlyCost> hourlyBreakdown
) { }
