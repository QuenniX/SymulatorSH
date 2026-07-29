package pl.smarthome.platform.tariff;

/**
 * Trzy taryfy energii elektrycznej porownywane w pracy magisterskiej.
 *
 * <ul>
 *   <li><b>G11</b> - taryfa jednostrefowa (jedna cena calla dobe).
 *       Najprostsza, uzywana przez wiekszosc gospodarstw domowych.</li>
 *   <li><b>G12</b> - taryfa dwustrefowa (dzien drozej, noc taniej).
 *       Godziny nocne (tanie): 22:00-06:00 i 13:00-15:00.</li>
 *   <li><b>RDN</b> - taryfa dynamiczna oparta o Rynek Dnia Nastepnego.
 *       Cena godzinowa zmienna, pobierana codziennie z PSE.
 *       Do ceny hurtowej doliczane sa oplaty dystrybucyjne, akcyza, VAT i marza.</li>
 * </ul>
 */
public enum Tariff {

    G11("Taryfa jednostrefowa (staly cennik)"),
    G12("Taryfa dwustrefowa (dzien/noc)"),
    RDN("Taryfa dynamiczna RDN (cena godzinowa z PSE)");

    private final String description;

    Tariff(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
