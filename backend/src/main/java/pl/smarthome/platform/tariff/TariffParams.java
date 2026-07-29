package pl.smarthome.platform.tariff;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Parametry cenowe taryf energii elektrycznej dla polskich gospodarstw domowych,
 * <b>rozbite na lata 2024, 2025, 2026</b>.
 *
 * <p>Dlaczego per rok? Bo ceny pradu w Polsce drastycznie sie zmienialy:</p>
 * <ul>
 *   <li><b>2024</b> - obowiazywala tarcza mrozeniowa (cena maksymalna dla
 *       gospodarstw: 693 zl/MWh brutto). Rachunki byly stabilne.</li>
 *   <li><b>2025</b> - czesciowe mrozenie (max 621 zl/MWh brutto).</li>
 *   <li><b>2026</b> - <u>koniec mrozenia</u>. Ceny wg zatwierdzonej taryfy
 *       URE (495 zl/MWh netto za sama energie) + zmienna dystrybucja
 *       (wzrost o ~9.36% wzgl. 2025) + VAT 23%. Realny koszt PGE ~1.10 zl/kWh brutto.</li>
 * </ul>
 *
 * <p>Wszystkie ceny G11/G12 podane sa <b>brutto</b> (z VAT 23% i pelna dystrybucja
 * zmienna). Dla RDN podajemy netto skladniki, bo cena hurtowa z PSE dochodzi osobno.</p>
 *
 * <p>Zrodla: URE, PGE Obrot, Tauron Sprzedaz, artykuly branzowe.
 * Zeby zmienic wartosci - edytuj mape {@link #BY_YEAR} ponizej. Wszystko w jednym miejscu.</p>
 */
public final class TariffParams {

    private TariffParams() {
        // klasa narzedziowa
    }

    // ==================================================================
    //  Definicja parametrow dla jednego roku
    // ==================================================================

    /**
     * Zestaw wszystkich parametrow cenowych dla jednego roku.
     *
     * @param g11PriceBrutto        cena G11 brutto (energia + dystrybucja + VAT), zl/kWh
     * @param g12DayPriceBrutto     G12 strefa dzienna brutto, zl/kWh
     * @param g12NightPriceBrutto   G12 strefa nocna brutto, zl/kWh
     * @param rdnDistributionNet    skladnik zmienny oplaty dystrybucyjnej (netto) dla RDN, zl/kWh
     * @param rdnExciseNet          akcyza (netto), zl/kWh (staly komponent 5 zl/MWh)
     * @param rdnMarginNet          marza sprzedawcy energii (netto), zl/kWh
     * @param vatMultiplier         mnoznik VAT (1.23 = 23%)
     */
    public record YearParams(
            BigDecimal g11PriceBrutto,
            BigDecimal g12DayPriceBrutto,
            BigDecimal g12NightPriceBrutto,
            BigDecimal rdnDistributionNet,
            BigDecimal rdnExciseNet,
            BigDecimal rdnMarginNet,
            BigDecimal vatMultiplier
    ) { }

    // ==================================================================
    //  Parametry per rok - EDYTUJ TUTAJ zeby zmienic ceny
    // ==================================================================

    private static final Map<Integer, YearParams> BY_YEAR = new HashMap<>();

    static {
        // ---------- 2024: tarcza mrozeniowa, ceny sztucznie zaniżone ----------
        BY_YEAR.put(2024, new YearParams(
                new BigDecimal("0.69"),   // G11 - cena maksymalna z mrozeniem
                new BigDecimal("0.85"),   // G12 dzien
                new BigDecimal("0.42"),   // G12 noc (mrozona)
                new BigDecimal("0.28"),   // RDN dystrybucja netto
                new BigDecimal("0.005"),  // akcyza (stala)
                new BigDecimal("0.10"),   // marza sprzedawcy netto
                new BigDecimal("1.23")    // VAT 23%
        ));

        // ---------- 2025: czesciowe mrozenie, umiarkowany wzrost ----------
        BY_YEAR.put(2025, new YearParams(
                new BigDecimal("0.75"),   // G11 - cena max 621 zl/MWh
                new BigDecimal("0.90"),   // G12 dzien
                new BigDecimal("0.48"),   // G12 noc
                new BigDecimal("0.30"),   // RDN dystrybucja netto (wzrost)
                new BigDecimal("0.005"),  // akcyza
                new BigDecimal("0.10"),   // marza
                new BigDecimal("1.23")    // VAT
        ));

        // ---------- 2026: koniec mrozenia, pelne stawki ----------
        BY_YEAR.put(2026, new YearParams(
                new BigDecimal("1.10"),   // G11 - realny koszt PGE ~1.10 zl/kWh brutto
                new BigDecimal("1.25"),   // G12 dzien (Energa 2026)
                new BigDecimal("0.62"),   // G12 noc (Energa 2026)
                new BigDecimal("0.33"),   // RDN dystrybucja netto (+9.36% wzgl. 2025)
                new BigDecimal("0.005"),  // akcyza
                new BigDecimal("0.10"),   // marza
                new BigDecimal("1.23")    // VAT
        ));
    }

    // ==================================================================
    //  Godziny nocne G12 - staly uklad w Polsce (nie zmienia sie per rok)
    // ==================================================================

    /**
     * Godziny doby ktore G12 traktuje jako "noc" (tanie).
     * Standard PGE/Tauron/Energa: 22:00-06:00 (glowna noc, 8h)
     * plus 13:00-15:00 (przerwa poludniowa, 2h). Razem 10h taniej doby.
     */
    public static final Set<Integer> G12_NIGHT_HOURS = Set.of(
            22, 23, 0, 1, 2, 3, 4, 5,  // 22-06
            13, 14                       // 13-15
    );

    // ==================================================================
    //  Publiczne API - metody uzywane przez CostCalculatorService
    // ==================================================================

    /**
     * Zwraca parametry dla danego roku. Jesli rok nie jest zdefiniowany
     * (np. 2027), uzywa ostatniego dostepnego roku (2026) jako fallback.
     */
    public static YearParams forYear(int year) {
        YearParams params = BY_YEAR.get(year);
        if (params != null) {
            return params;
        }
        // Fallback: uzyj najnowszego zdefiniowanego roku
        return BY_YEAR.get(BY_YEAR.keySet().stream().max(Integer::compareTo).orElse(2026));
    }

    /** Cena G11 brutto dla danego roku (zl/kWh). */
    public static BigDecimal g11Price(int year) {
        return forYear(year).g11PriceBrutto();
    }

    /** Cena G12 brutto dla danej godziny doby (0-23) w danym roku. */
    public static BigDecimal g12PriceForHour(int year, int hour) {
        YearParams p = forYear(year);
        return G12_NIGHT_HOURS.contains(hour)
                ? p.g12NightPriceBrutto()
                : p.g12DayPriceBrutto();
    }

    /**
     * Liczy koncowa cene brutto RDN dla godziny.
     * Formula: (cena_hurtowa_PSE + dystrybucja + akcyza + marza) * VAT
     *
     * @param year            rok (dobiera odpowiednie narzuty)
     * @param wholesalePlnKwh cena hurtowa z PSE (netto, zl/kWh)
     * @return cena brutto placona przez klienta (zl/kWh)
     */
    public static BigDecimal rdnFinalPrice(int year, BigDecimal wholesalePlnKwh) {
        YearParams p = forYear(year);
        BigDecimal netTotal = wholesalePlnKwh
                .add(p.rdnDistributionNet())
                .add(p.rdnExciseNet())
                .add(p.rdnMarginNet());
        return netTotal.multiply(p.vatMultiplier());
    }

    /** Zwraca liste lat dla ktorych mamy zdefiniowane parametry. */
    public static Set<Integer> availableYears() {
        return BY_YEAR.keySet();
    }
}
