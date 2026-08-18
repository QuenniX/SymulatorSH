# Automatyczna weryfikacja hipotez badawczych

_Wygenerowane przez analiza_wyniki.py na podstawie 24 testów baseline._

## H1: RDN jest średnio opłacalny wobec G11 dla polskich gospodarstw

**Wynik:** Średnia oszczędność = **0.97%** (uśredniona po 24 przypadkach).
**Weryfikacja:** POTWIERDZONA (próg: >0%).

## H2: RDN jest opłacalny również wobec G12 (dzień/noc)

**Wynik:** Średnia oszczędność = **-8.11%**.
**Weryfikacja:** NIEPOTWIERDZONA.

## H3: Opłacalność RDN różni się istotnie między profilami

**Wynik:** Najlepszy profil = **D (Senior samotny)** (4.51%), najgorszy = **C (Rodzina 2+2)** (-3.44%). Rozstęp = **7.95 pp**.
**Weryfikacja:** POTWIERDZONA (próg: rozstęp > 2 pp).

## H4: Zima ma większą oszczędność na RDN niż lato (większe amplitudy cen)

**Wynik:** Zima = **-3.39%**, Lato = **0.09%** (różnica: -3.48 pp).
**Weryfikacja:** NIEPOTWIERDZONA.

## H5: Ranking profili jest robust względem sezonu cenowego

**Wynik:** Diagonal - najlepszy = **D**. Cross-season 96 punktów - najlepszy = **D**.
**Weryfikacja:** POTWIERDZONA

## Dodatkowe obserwacje

- Największa oszczędność (%): **18.36%** (Senior samotny - Wiosna)
- Najmniejsza (najgorsza): **-8.70%** (Singiel-biuro - Jesien)
- Średni miesięczny koszt: G11=963.76 zł, G12=886.50 zł, RDN=968.16 zł
- Średni CVaR RDN (dzień drogi): **40.31 zł** (dla porównania: średni dzienny koszt G11 = 32.13 zł)
