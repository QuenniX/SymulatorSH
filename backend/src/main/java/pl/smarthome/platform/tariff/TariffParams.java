package pl.smarthome.platform.tariff;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Month;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Parametry cenowe taryf energii elektrycznej dla polskich gospodarstw domowych.
 *
 * <h3>Zrodla stawek dla roku 2026 (dokumenty zatwierdzone przez Prezesa URE)</h3>
 * <ul>
 *   <li><b>PGE Obrot S.A.</b>, "Taryfa dla energii elektrycznej dla Odbiorcow
 *       z grup taryfowych G", obowiazujaca od 1 stycznia 2026 r., pkt 5
 *       "Ceny za energie elektryczna" - cena energii czynnej.</li>
 *   <li><b>PGE Dystrybucja S.A.</b>, "Taryfa dla uslug dystrybucji energii
 *       elektrycznej", tekst jednolity od 1 lutego 2026 r., tabela "Grupy
 *       taryfowe G" oraz rozdzial "Strefy czasowe stosowane w rozliczeniach
 *       z odbiorcami" (tabela dla grup C12b, G12).</li>
 * </ul>
 * <p>PGE Dystrybucja jest operatorem wlasciwym dla Rzeszowa, co uzasadnia wybor
 * tej taryfy jako podstawy modelu kosztowego.</p>
 *
 * <h3>Skladniki ceny 2026 (netto, zl/kWh)</h3>
 * <pre>
 *                          G11        G12 dzien   G12 noc
 *   energia czynna         0,4982     0,5656      0,3718   [PGE Obrot]
 *   skladnik zmienny
 *   stawki sieciowej       0,3469     0,4014      0,0765   [PGE Dystrybucja]
 *   stawka jakosciowa      0,0332     0,0332      0,0332
 *   oplata OZE             0,0073     0,0073      0,0073
 *   oplata kogeneracyjna   0,0030     0,0030      0,0030
 *   akcyza                 0,0050     0,0050      0,0050
 *   ----------------------------------------------------
 *   razem netto            0,8936     1,0155      0,4968
 *   brutto (x1,23)         1,0991     1,2491      0,6111
 * </pre>
 *
 * <p>Oplata mocowa NIE wchodzi do ceny zmiennej: dla gospodarstw domowych
 * (art. 89a ust. 1 pkt 1 ustawy o rynku mocy) jest oplata ryczaltowa zalezna od
 * rocznego zuzycia. Podobnie skladnik staly stawki sieciowej i oplata
 * abonamentowa - to oplaty stale, poza modelem kosztu zmiennego.</p>
 *
 * <h3>UWAGA metodologiczna - marza sprzedawcy</h3>
 * <p>W G11 i G12 marza jest WLICZONA w zatwierdzona cene i nie da sie jej
 * wyodrebnic. Dla RDN jest JAWNA jako {@code rdnMarginNet}, bo cene skladamy sami
 * z notowania hurtowego PSE. Odbiorca taryfy dynamicznej jest rozliczany
 * dystrybucyjnie w grupie G11, ponosi wiec te same stawki sieciowe i systemowe;
 * roznica dotyczy wylacznie skladnika energii. Marza jest zatem <b>jedynym
 * parametrem swobodnym</b> modelu kosztowego i decyduje o znaku porownania
 * RDN vs G11 - patrz analiza/wrazliwosc_g12.py.</p>
 *
 * <p>Lata 2024 i 2025 (tarcza mrozeniowa) pozostawiono dla kompletnosci historycznej.
 * Analiza wynikowa pracy prowadzona jest w calosci na parametrach {@link #ANALYSIS_YEAR}.</p>
 */
public final class TariffParams {

    private TariffParams() {
        // klasa narzedziowa
    }

    /**
     * Rok taryfowy przyjety jako podstawa analizy wynikowej.
     *
     * <p>Testy uruchomiono w 2026 r., wiec sciezka {@code calculateForTest} i tak
     * dobiera ten rok z sygnatur czasowych pomiarow. Projekcja kosztu operuje
     * natomiast na historycznych <i>datach</i> notowan RDN (2025) i bez tej stalej
     * siegalaby po stawki 2025 objete tarcza mrozeniowa (G11 = 0,75), co dawaloby
     * wynik niespojny z rozdzialem wynikowym pracy. Model swiadomie laczy
     * <b>ksztalt cen hurtowych z 2025</b> z <b>poziomem stawek regulowanych 2026</b>.</p>
     */
    public static final int ANALYSIS_YEAR = 2026;

    // ==================================================================
    //  Definicja parametrow dla jednego roku
    // ==================================================================

    /**
     * Zestaw wszystkich parametrow cenowych dla jednego roku.
     *
     * @param g11PriceBrutto        cena G11 brutto (energia + dystrybucja + oplaty + VAT), zl/kWh
     * @param g12DayPriceBrutto     G12 strefa dzienna brutto, zl/kWh
     * @param g12NightPriceBrutto   G12 strefa nocna brutto, zl/kWh
     * @param rdnDistributionNet    skladnik zmienny oplaty dystrybucyjnej powiekszony
     *                              o oplaty systemowe (jakosciowa + OZE + kogeneracyjna), netto, zl/kWh
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
        // ---------- 2024: tarcza mrozeniowa (historyczne, nieuzywane w analizie) ----------
        BY_YEAR.put(2024, new YearParams(
                new BigDecimal("0.69"),
                new BigDecimal("0.85"),
                new BigDecimal("0.42"),
                new BigDecimal("0.28"),
                new BigDecimal("0.005"),
                new BigDecimal("0.10"),
                new BigDecimal("1.23")
        ));

        // ---------- 2025: czesciowe mrozenie (historyczne, nieuzywane w analizie) ----------
        BY_YEAR.put(2025, new YearParams(
                new BigDecimal("0.75"),
                new BigDecimal("0.90"),
                new BigDecimal("0.48"),
                new BigDecimal("0.30"),
                new BigDecimal("0.005"),
                new BigDecimal("0.10"),
                new BigDecimal("1.23")
        ));

        // ---------- 2026: koniec mrozenia, stawki zlozone ze skladnikow taryf URE ----------
        BY_YEAR.put(2026, new YearParams(
                // (0,4982 + 0,3469 + 0,0332 + 0,0073 + 0,0030 + 0,0050) * 1,23
                new BigDecimal("1.0991"),
                // (0,5656 + 0,4014 + 0,0332 + 0,0073 + 0,0030 + 0,0050) * 1,23
                new BigDecimal("1.2491"),
                // (0,3718 + 0,0765 + 0,0332 + 0,0073 + 0,0030 + 0,0050) * 1,23
                new BigDecimal("0.6111"),
                // siec G11 0,3469 + jakosciowa 0,0332 + OZE 0,0073 + kogeneracyjna 0,0030
                new BigDecimal("0.3904"),
                new BigDecimal("0.005"),
                new BigDecimal("0.10"),
                new BigDecimal("1.23")
        ));
    }

    // ==================================================================
    //  Strefy czasowe G12 - SEZONOWE
    // ==================================================================

    /**
     * Strefa tansza G12 obejmuje 10 godzin na dobe, ale okno popoludniowe zalezy
     * od okresu taryfowego (PGE Dystrybucja, tabela dla grup C12b i G12):
     * <ul>
     *   <li>okres <b>letni</b> (1 IV - 30 IX): 15:00-17:00 oraz 22:00-06:00</li>
     *   <li>okres <b>zimowy</b> (1 X - 31 III): 13:00-15:00 oraz 22:00-06:00</li>
     * </ul>
     */
    private static final Set<Integer> G12_NIGHT_HOURS_WINTER =
            Set.of(22, 23, 0, 1, 2, 3, 4, 5, 13, 14);

    private static final Set<Integer> G12_NIGHT_HOURS_SUMMER =
            Set.of(22, 23, 0, 1, 2, 3, 4, 5, 15, 16);

    /**
     * @deprecated okno popoludniowe jest sezonowe - uzywaj {@link #g12NightHours(LocalDate)}.
     *             Stala zachowana dla zgodnosci wstecznej i odpowiada okresowi zimowemu.
     */
    @Deprecated
    public static final Set<Integer> G12_NIGHT_HOURS = G12_NIGHT_HOURS_WINTER;

    /** Czy podana data nalezy do taryfowego okresu letniego (1 IV - 30 IX). */
    public static boolean isSummerPeriod(LocalDate date) {
        Month m = date.getMonth();
        return m.getValue() >= Month.APRIL.getValue() && m.getValue() <= Month.SEPTEMBER.getValue();
    }

    /** Zbior godzin objetych tansza strefa G12 w dobie o podanej dacie. */
    public static Set<Integer> g12NightHours(LocalDate date) {
        return isSummerPeriod(date) ? G12_NIGHT_HOURS_SUMMER : G12_NIGHT_HOURS_WINTER;
    }

    // ==================================================================
    //  Publiczne API - metody uzywane przez CostCalculatorService
    // ==================================================================

    /**
     * Zwraca parametry dla danego roku. Dla roku spoza zdefiniowanego zakresu
     * uzywa najblizszego dostepnego (dolny clamp dla lat starszych, gorny dla nowszych),
     * zamiast po cichu podstawiac najnowszy rok pod date historyczna.
     */
    public static YearParams forYear(int year) {
        YearParams params = BY_YEAR.get(year);
        if (params != null) {
            return params;
        }
        int min = BY_YEAR.keySet().stream().min(Integer::compareTo).orElse(ANALYSIS_YEAR);
        int max = BY_YEAR.keySet().stream().max(Integer::compareTo).orElse(ANALYSIS_YEAR);
        return BY_YEAR.get(year < min ? min : max);
    }

    /** Cena G11 brutto dla danego roku (zl/kWh). */
    public static BigDecimal g11Price(int year) {
        return forYear(year).g11PriceBrutto();
    }

    /**
     * Cena G12 brutto dla godziny doby (0-23) w dobie o podanej dacie.
     * Data jest potrzebna, bo okno popoludniowe strefy tanszej jest sezonowe.
     */
    public static BigDecimal g12PriceForHour(int year, LocalDate date, int hour) {
        YearParams p = forYear(year);
        return g12NightHours(date).contains(hour)
                ? p.g12NightPriceBrutto()
                : p.g12DayPriceBrutto();
    }

    /**
     * @deprecated pomija sezonowosc stref - zaklada okres zimowy.
     *             Uzywaj {@link #g12PriceForHour(int, LocalDate, int)}.
     */
    @Deprecated
    public static BigDecimal g12PriceForHour(int year, int hour) {
        YearParams p = forYear(year);
        return G12_NIGHT_HOURS_WINTER.contains(hour)
                ? p.g12NightPriceBrutto()
                : p.g12DayPriceBrutto();
    }

    /**
     * Liczy koncowa cene brutto RDN dla godziny.
     * Formula: (cena_hurtowa_PSE + dystrybucja i oplaty systemowe + akcyza + marza) * VAT
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
