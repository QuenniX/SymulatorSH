package pl.smarthome.platform.api.dto;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Koszt zuzycia energii dla <b>jednej godziny testu</b> w 3 taryfach.
 * Dla kazdej taryfy mamy: cene jednostkowa (zl/kWh brutto) i koszt godzinowy (zl).
 *
 * @param hour            poczatek godziny w czasie polskim, np. 2024-06-15T14:00
 * @param kwh             zuzyta energia w tej godzinie
 * @param g11PricePlnKwh  cena G11 (staly cennik)
 * @param g12PricePlnKwh  cena G12 (zalezy od godziny - dzien/noc)
 * @param rdnPricePlnKwh  cena RDN (dynamiczna, z PSE + narzuty). Moze byc null gdy brak danych.
 * @param g11CostPln      koszt tej godziny w G11
 * @param g12CostPln      koszt tej godziny w G12
 * @param rdnCostPln      koszt tej godziny w RDN. 0 gdy brak ceny.
 * @param rdnPriceMissing true jesli brak ceny RDN dla tej godziny (dane niekompletne w bazie)
 */
@Builder
public record HourlyCost(
        LocalDateTime hour,
        BigDecimal kwh,
        BigDecimal g11PricePlnKwh,
        BigDecimal g12PricePlnKwh,
        BigDecimal rdnPricePlnKwh,
        BigDecimal g11CostPln,
        BigDecimal g12CostPln,
        BigDecimal rdnCostPln,
        boolean rdnPriceMissing
) { }
