package pl.smarthome.platform.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Wynik porownania wielu testow - agregat metryk (koszty, energia, ryzyko VaR/CVaR).
 * Zwracany przez {@code POST /api/v1/tests/compare}.
 *
 * <p>Dla kazdego testu wskazuje: nazwe, laczne kWh, koszty w 3 taryfach, oszczednosc RDN vs G11,
 * mediana kosztu dziennego, VaR i CVaR 5%, najtansza taryfa.</p>
 *
 * <p>Lista {@code comparison} posortowana od najkorzystniejszego dla RDN (najwyzsze savings).
 * {@code summary} wskazuje best/worst dla RDN + srednia oszczednosc.</p>
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CompareResponse(
        int comparedTests,
        int failedTests,
        List<UUID> failedTestIds,
        List<TestComparison> comparison,
        Summary summary
) {

    /** Pojedynczy wiersz porownania. */
    @Builder
    public record TestComparison(
            UUID testId,
            String name,
            int durationDays,
            BigDecimal totalKwh,
            BigDecimal totalCostG11,
            BigDecimal totalCostG12,
            BigDecimal totalCostRdn,
            BigDecimal savingsRdnVsG11Percent,
            BigDecimal rdnDailyMedian,
            BigDecimal rdnVaR5Percent,
            BigDecimal rdnCVaR5Percent,
            String cheapestTariff
    ) { }

    /** Podsumowanie caleg porownania - best/worst dla RDN + srednia savings. */
    @Builder
    public record Summary(
            UUID bestForRdn,
            String bestForRdnName,
            BigDecimal bestForRdnSavingsPercent,
            UUID worstForRdn,
            String worstForRdnName,
            BigDecimal worstForRdnSavingsPercent,
            BigDecimal avgSavingsPercent
    ) { }
}
