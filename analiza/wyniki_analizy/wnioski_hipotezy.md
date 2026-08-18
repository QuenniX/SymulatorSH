# Automatyczna weryfikacja hipotez badawczych

_Wygenerowane przez analiza_wyniki.py na podstawie 24 testów baseline._

Konwencja znaku: wartość dodatnia oznacza, że taryfa dynamiczna jest **tańsza** od taryfy odniesienia, ujemna - że jest **droższa**.

Parametry: G11 = 1.0991, G12 = 1.2491/0.6111 zł/kWh, narzut RDN = 0.4954 zł/kWh (marża sprzedawcy 0.10 zł/kWh).

## H1: RDN jest średnio opłacalny wobec G11 dla polskich gospodarstw

**Wynik:** średnia z procentów = **-7.78%**, ujęcie kwotowe = **-9.00%**.
**Weryfikacja:** NIEPOTWIERDZONA (próg: >0% w obu ujęciach).

## H2: RDN jest opłacalny również wobec G12 (dzień/noc)

**Wynik:** średnia z procentów = **-13.33%**, ujęcie kwotowe = **-14.34%**.
**Weryfikacja:** NIEPOTWIERDZONA.

## H3: Wynik taryfy dynamicznej różni się istotnie między profilami

**Wynik:** Najlepszy profil = **D (Senior samotny)** (-3.48%), najgorszy = **A (Singiel-biuro)** (-11.85%). Rozstęp = **8.38 pp**.
**Weryfikacja:** POTWIERDZONA (próg: rozstęp > 2 pp).

## H4: Zimą taryfa dynamiczna wypada korzystniej niż latem (większe amplitudy cen)

**Wynik:** Zima = **-11.41%**, Lato = **-8.48%** (różnica: -2.93 pp).
**Weryfikacja:** NIEPOTWIERDZONA.

## H5: Ranking profili jest robust względem sezonu cenowego

**Wynik:** Diagonal - najlepszy = **D**. Cross-season 96 punktów - najlepszy = **B**.
**Weryfikacja:** CZĘŚCIOWA (wynik zależy od okresu cen)

## Dodatkowe obserwacje

- Najkorzystniejszy przypadek: **9.59%** (Senior samotny - Wiosna)
- Najmniej korzystny przypadek: **-13.76%** (Singiel-biuro - Jesien)
- Średni miesięczny koszt: G11=914.79 zł, G12=872.05 zł, RDN=997.14 zł
- Średni CVaR RDN (dzień drogi): **41.31 zł** (dla porównania: średni dzienny koszt G11 = 30.49 zł)
