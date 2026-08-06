package pl.smarthome.platform.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pl.smarthome.platform.api.dto.CompareResponse;
import pl.smarthome.platform.api.dto.ProjectedCostBreakdown;
import pl.smarthome.platform.api.dto.TestResponse;
import pl.smarthome.platform.tariff.CostCalculatorService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Porownanie wielu testow - agregat metryk (koszty, energie, ryzyko) do jednej odpowiedzi.
 *
 * <p>Dla kazdego testu z listy woła {@link CostCalculatorService#calculateForTest(UUID)}
 * i agreguje najwazniejsze pola. Dodatkowo wskazuje ktory test najlepiej wypada
 * dla RDN, ktory najgorzej, srednia oszczednosc, itd.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CompareService {

    private final TestService testService;
    private final CostCalculatorService costCalculatorService;

    public CompareResponse compare(List<UUID> testIds) {
        if (testIds == null || testIds.isEmpty()) {
            throw new IllegalArgumentException("Lista testIds nie moze byc pusta");
        }
        if (testIds.size() > 100) {
            throw new IllegalArgumentException("Maksymalnie 100 testow na jedno porownanie");
        }

        List<CompareResponse.TestComparison> comparisons = new ArrayList<>();
        List<UUID> failedTests = new ArrayList<>();

        // Zakres cen do projekcji: ostatnie 30 dni (pokrywa 1 miesiac historii)
        LocalDate to = LocalDate.now().minusDays(1);
        LocalDate from = to.minusDays(30);

        for (UUID testId : testIds) {
            try {
                TestResponse test = testService.getTest(testId);
                // Projekcja daje pelny zestaw statystyk (VaR, CVaR, dailyBreakdown)
                ProjectedCostBreakdown breakdown = costCalculatorService.calculateProjectedCosts(testId, from, to);

                comparisons.add(CompareResponse.TestComparison.builder()
                        .testId(testId)
                        .name(test.getName())
                        .durationDays(test.getDurationDays())
                        .totalKwh(breakdown.avgDailyKwh().multiply(BigDecimal.valueOf(breakdown.daysInPeriod())))
                        .totalCostG11(breakdown.totalCostG11Pln())
                        .totalCostG12(breakdown.totalCostG12Pln())
                        .totalCostRdn(breakdown.totalCostRdnPln())
                        .savingsRdnVsG11Percent(breakdown.rdnVsG11SavingsPercent())
                        .rdnDailyMedian(breakdown.rdnDailyMedianPln())
                        .rdnVaR5Percent(breakdown.rdnVaR5PercentPln())
                        .rdnCVaR5Percent(breakdown.rdnCVaR5PercentPln())
                        .cheapestTariff(breakdown.cheapestTariff().name())
                        .build());
            } catch (Exception e) {
                log.warn("Compare: pominieto test {} - {}", testId, e.getMessage());
                failedTests.add(testId);
            }
        }

        // Sortowanie po savings (od najkorzystniejszego dla RDN)
        comparisons.sort((a, b) -> b.savingsRdnVsG11Percent().compareTo(a.savingsRdnVsG11Percent()));

        // Podsumowanie
        CompareResponse.Summary summary = null;
        if (!comparisons.isEmpty()) {
            BigDecimal avgSavings = comparisons.stream()
                    .map(CompareResponse.TestComparison::savingsRdnVsG11Percent)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(comparisons.size()), 2, RoundingMode.HALF_UP);

            summary = CompareResponse.Summary.builder()
                    .bestForRdn(comparisons.get(0).testId())
                    .bestForRdnName(comparisons.get(0).name())
                    .bestForRdnSavingsPercent(comparisons.get(0).savingsRdnVsG11Percent())
                    .worstForRdn(comparisons.get(comparisons.size() - 1).testId())
                    .worstForRdnName(comparisons.get(comparisons.size() - 1).name())
                    .worstForRdnSavingsPercent(comparisons.get(comparisons.size() - 1).savingsRdnVsG11Percent())
                    .avgSavingsPercent(avgSavings)
                    .build();
        }

        return CompareResponse.builder()
                .comparedTests(comparisons.size())
                .failedTests(failedTests.size())
                .failedTestIds(failedTests)
                .comparison(comparisons)
                .summary(summary)
                .build();
    }
}
