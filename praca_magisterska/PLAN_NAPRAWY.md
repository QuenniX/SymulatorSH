# PLAN NAPRAWY PRACY MAGISTERSKIEJ (v2)

Bazuje na:
- recenzja_magisterka_igor.md (Opus 5, pierwsza recenzja)
- weryfikacja_kodu_uzupelnienie.md (Opus 5, po analizie kodu)

**KLUCZOWY WNIOSEK OPUSA 5:** Wszystkie znalezione obciążenia (aliasing bojlera, uśrednianie E_h, uśrednianie procentów) **działają w tę samą stronę: zawyżają atrakcyjność RDN i G12**. Kierunek pracy jest odporny na te błędy — po korekcie wniosek "RDN się nie opłaca" staje się MOCNIEJSZY. Ale trzeba to opisać zanim recenzent sam znajdzie.

Legenda:
- [ ] TODO / [~] W TRAKCIE / [x] ZROBIONE

---

## FAZA 0: MUST-VERIFY (30 minut, tak/nie)

**Trzy pytania które trzeba rozstrzygnąć NAJPIERW.** Jeśli któreś wypadnie źle — poprawka jednolinijkowa, ale rerun partii (6h maszynowych).

### 0.1. Czy `simTimeStart` jest ucięty do północy? [KRYTYCZNE]
**Ryzyko:** Jeśli NIE — cały rozdział 7 nieważny. Urządzenie skonfigurowane na 19:00 trafiłoby np. na 15:57 (wg momentu utworzenia testu) — zamiast wieczornego szczytu cen w dołek fotowoltaiczny.

**Test (2 min):** Otwórz rys. 5.7 (Wzorzec dnia) i sprawdź czy `light_kitchen` świeci w godzinach z konfiguracji (6-7, 17-20). Dodatkowo sprawdź jeden rekord w InfluxDB — znajdź próbkę o mocy ~2000W dla `kettle_1` i zobacz jaka godzina w `_time`.

**Do zrobienia:**
- [ ] Sprawdzić `TestRunner.java` — jak liczony jest `simTimeStart`
- [ ] Zweryfikować w InfluxDB czy timestampy w prawych godzinach
- [ ] Jeśli źle: fix (jedna linia) + rerun partii 24 testów

---

### 0.2. Czy `aggregateWindow` przesuwa profil o +1h? [KRYTYCZNE]
**Ryzyko:** `aggregateWindow(every: 1h, fn: mean)` w Fluxie domyślnie ustawia `_time = _stop` (koniec okna). Energia z okna [20:00, 21:00) dostaje `_time = 21:00` → po `truncatedTo(HOURS)` trafia do godziny 21. **Cały profil dobowy przesunięty o +1h względem cen.**

**Test (5 min):** Dodać do zapytania Flux `timeSrc: "_start"` i porównać sumę kosztu RDN dla jednego testu przed/po. Różnica >1% = przesunięcie realne.

**Do zrobienia:**
- [ ] Sprawdzić `InfluxQueryService.java:98` — czy jest `timeSrc: "_start"`
- [ ] Jeśli brak: fix + rerun

---

### 0.3. Czy październik 2025 (jesień) ma pełne 30×24 ceny bez braków? [WAŻNE]
**Ryzyko:** 26.10.2025 zmiana czasu z letniego na zimowy → doba 25h → PSE zwraca 100 kwadransów. Kod używa `merge` po `atZone(Europe/Warsaw)` → **godzina 02:00 duplikuje się i sumuje energię dwóch godzin w jedną**. Jesień to sezon gdzie RDN wypada najgorzej (−2,50%) — anomalia może być stąd.

Dodatkowo Python przy brakującej godzinie robi `hour_prices.get(h, 0.0)` → **cena 0 zł = energia za darmo** (zaniża RDN).

**Test (5 min):** Wypisać `len(prices_by_day)` i `len(hour_prices)` dla każdego dnia każdego sezonu. Asercja: 30 × 24, zero braków.

**Do zrobienia:**
- [ ] Skrypt Python weryfikujący kompletność cen
- [ ] Jeśli braki: fix + rerun (przynajmniej jesień)

---

### 0.4. Czy `rdnDaily` jest sortowane przed liczeniem VaR w backendzie? [ŚREDNIE]
**Ryzyko:** W cytowanym fragmencie kodu brak sortowania. Jeśli lista jest chronologiczna, `get(28)` zwraca koszt 29. dnia, nie 29. co do wielkości. Rys. 5.9 w pracy pokazuje panele VaR/CVaR z backendu — jeśli źle, na zrzucie są przypadkowe liczby.

**Test:** Zajrzeć w `CostCalculatorService.java` sekcja VaR/CVaR.

**Do zrobienia:**
- [ ] Sprawdzić czy `rdnDaily.sort()` przed `get(varIndex)`
- [ ] Fix jeśli brak

---

## FAZA 1: BOJLER + KRYTYCZNE Z RECENZJI

### 1.0. Aliasing bojlera [NAJPOWAŻNIEJSZE Z NOWO ZNALEZIONYCH]
**Problem:** Bojler jest ON przez pierwsze N minut każdej godziny (od minuty 0). Próbki co 5 min (też od minuty 0). Faza STAŁA I DETERMINISTYCZNA — brak jittera który by to uśrednił.

**Skala błędu** (dla realnych duty cycle):
| Profil | Wiosna/Lato | Zima | Jesień |
|---|---|---|---|
| A Singiel | duty 0.10 → **+67%** | 0.25 → 0% | 0.15 → +11% |
| B Remote | 0.12 → **+43%** | 0.27 → +25% | 0.17 → 0% |
| C Rodzina | 0.25 → 0% | 0.35 → +19% | 0.22 → +15% |
| D Senior | 0.08 → 0% | 0.23 → +7% | 0.13 → +25% |
| E Studenci | 0.12 → **+43%** | 0.27 → +25% | 0.17 → 0% |
| F Para | 0.15 → +11% | 0.30 → +11% | 0.20 → +25% |

**Sanity check na Twoich danych:** A-Wiosna zużycie 14 kWh/dobę, bojler zmierzony 8 kWh (57%!). Prawda: 4,8 kWh. Realne ΣE ≈ 10,8 kWh → **zużycie A-Wiosna zawyżone o ~30%**.

**Kierunek błędu:** Zawyża płaską składową → podnosi udział nocny (bojler wnosi 41,7%) → **zawyża przewagę G12** i **spłaszcza profil → zawyża atrakcyjność RDN**.

**Do zrobienia:**
- [ ] **Test rozstrzygający (15 min):** Dla każdego z 24 przypadków policz analitycznie ΣE z konfiguracji JSON, porównaj z K_G11/33 z tabeli 7.1
- [ ] **NAPRAWA - opcja A (najtańsza, JEDNA LICZBA):** Zmienić `cycle_length_minutes` bojlera z **60 na 37** (względnie pierwsze z 5 i 60) w JSON-ach profili. Lodówka ma 37 i nie ma tego problemu → sprawdzone empirycznie
- [ ] **NAPRAWA - opcja B (porządna):** `integral(unit: 1h)` zamiast `mean` we Fluxie
- [ ] Jeśli fix → rerun partii 24 testów (6h)

---

### 1.1. Efekt Simpsona w H1 [KRYTYCZNE, z pierwszej recenzji]
**Problem:** +0,97% to średnia procentów. Ze średnich kwot wychodzi **−0,46%** (RDN droższy). Kod: `analiza_wyniki.py:627` — `.mean()` na kolumnie procent.

**Do zrobienia:**
- [ ] W §7.1 dodać obie miary jawnie:
  - "średnia nieważona oszczędności procentowych": +0,97%
  - "oszczędność na koszcie zagregowanym": −0,46%
- [ ] Dodać wyjaśnienie efektu Simpsona
- [ ] W §7.7 tabela hipotez: H1 → "Odrzucona (w ujęciu kwotowym)"
- [ ] W §8 dodać "w ujęciu kwotowym RDN o 0,46% droższy od G11, wzmacnia wniosek o braku opłacalności"
- [ ] To samo dla G12: −8,11% (proc.) vs −9,21% (kwoty)

**Pliki:** praca.tex + skrypt Python

---

### 1.2. CVaR — REDEFINICJA jako "średnia z 2 najgorszych z 30" [KRYTYCZNE, uściślone]
**Problem 1:** §3.5 mówi że CVaR mierzy "zbieg wysokiego zużycia i wysokich cen" — ale E_h uśrednione po 30 dobach → zużycie deterministyczne.

**Problem 2 (NOWE):** Dla n=30 i `np.percentile(x, 95)` (linear): indeks = 0,95·29 = 27,55 → VaR między 28. a 29. wartością posortowaną. Zbiór {c ≥ VaR} = **zawsze dokładnie 2 elementy**. Czyli:
> CVaR₀,₉₅ w tej pracy = **średnia arytmetyczna kosztu 2 najdroższych dób z 30**
> Odpowiada ogonowi 2/30 = **6,7% a nie 5%**

**Do zrobienia:**
- [ ] Usunąć z §3.5 zdanie o "zbiegu wysokiego zużycia i wysokich cen"
- [ ] Przeformułować: "CVaR kwantyfikuje ekspozycję na zmienność cen hurtowych przy ustalonym profilu zużycia"
- [ ] Dodać zdanie: "Uzyskane wartości CVaR stanowią dolne oszacowanie ryzyka rzeczywistego"
- [ ] **Nowe zdanie:** "W praktycznej implementacji dla n=30 i estymatora liniowego kwantyla, zbiór {c ≥ VaR₀,₉₅} zawiera dokładnie 2 obserwacje. W konsekwencji CVaR₀,₉₅ oznacza średnią z kosztu 2 najdroższych dób miesiąca, co odpowiada ogonowi rozkładu 2/30 ≈ 6,7%. Estymator ma dużą wariancję próbkową i służy do porównań względnych, nie jako bezwzględna miara ryzyka."
- [ ] Dodać uwagę matematyczną: K_RDN = 30·Σ_h E_h·c̄_h → koszt zależy tylko od średniego kształtu dobowego cen
- [ ] Uzupełnić metodę estymacji kwantyla (linear interpolation)
- [ ] Rozstrzygnąć rozjazd backend (nearest-rank) vs Python (linear) — napisać że rozdz. 7 pochodzi ze ścieżki Pythona

---

### 1.3. G12 wygrywa arytmetycznie [KRYTYCZNE, z pierwszej recenzji]
**Problem:** Próg opłacalności G12: x = (1,25−1,10)/(1,25−0,62) = **23,8%**. Płaski profil = 41,7%. G12 wygrywa dla wszystkich.

**Do zrobienia:**
- [ ] W §7.2 wyprowadzić wzór progu 23,8%
- [ ] Tabela: udział strefy nocnej dla 6 profili (Zima)
- [ ] Zweryfikować profil F-Zima (~58% nocne wg recenzji) — czy błąd harmonogramu
- [ ] Usunąć zdanie "co potwierdza hipotezę..." (nie było takiej w H1-H5)
- [ ] Przeformułować: "matematyczna konsekwencja stawek — próg 23,8% jest znacznie poniżej obserwowanego"
- [ ] Cytat rzeczywistej taryfy PGE 2026 → weryfikacja stawek 0,62/1,25/1,10

---

## FAZA 2: SPRZECZNOŚCI I BRAKUJĄCE ELEMENTY

### 2.1. Sprzeczność "8-10 kWh/dobę latem" vs tabela 7.1 [NOWE, KRYTYCZNE]
**Problem:** §6.1 pisze "w wariancie letnim zapotrzebowanie spada do 8-10 kWh/dobę". Z tabeli 7.1: A-Lato → K_G11 = 781,92 → ΣE = **23,7 kWh/dobę**. **Sprzeczność 2,5-krotna**, do sprawdzenia kalkulatorem w 10s.

**Rozpiska ΣE dla wszystkich przypadków** (z tabeli 7.1):
| Profil | Zima | Wiosna | Lato | Jesień |
|---|---|---|---|---|
| A Singiel | 37,2 | 14,0 | 23,7 | 17,4 |
| B Remote | 45,6 | 19,0 | 29,3 | 22,8 |
| C Rodzina | 59,3 | 28,4 | 38,5 | 31,3 |
| D Senior | 39,1 | 13,0 | 22,8 | 19,3 |
| E Studenci | 48,6 | 18,9 | 29,6 | 20,5 |
| F Para | 46,9 | 19,4 | 29,4 | 27,3 |

**Do wyjaśnienia:**
- Lato > Wiosna o 60-75% — to klimatyzacja. Ale duty 0,5 przez 20h/dobę dla singla poza domem 8-17 nierealne. **Sprawdzić harmonogram AC**
- D Senior (39,1 kWh zimą) > A Singiel (37,2) — możliwe (senior cały dzień w domu), ale wymaga komentarza

**Do zrobienia:**
- [ ] Poprawić zdanie w §6.1 (8-10 → realistyczne)
- [ ] Dodać tabelę ΣE per przypadek do rozdz. 6 lub 7 (i tak potrzebna wg recenzji 4.3)
- [ ] Sprawdzić duty cycle AC w JSONach + harmonogram

---

### 2.2. Sekcja "Ograniczenia numeryczne modelu pomiarowego" [NOWE, WAŻNE]
**Problem:** Aliasing bojlera trzeba opisać samodzielnie zanim recenzent go znajdzie.

**Do zrobienia:**
- [ ] Nowa podsekcja w rozdz. 6 albo 8: "Ograniczenia numeryczne"
- [ ] Opisać aliasing bojlera z tabelą z pkt 1.0
- [ ] **Kluczowe:** dodać zdanie o KIERUNKU obciążenia: "zawyżenie płaskiej składowej działa na korzyść G12 i RDN, więc korekta wzmocniłaby wniosek o nieopłacalności RDN oraz nieznacznie zmniejszyła przewagę G12"

---

## FAZA 3: WAŻNE Z PIERWSZEJ RECENZJI

### 3.1. Scenariusz demand response [NAJWIĘKSZA MERYTORYCZNA SZANSA]
- [ ] Skrypt Python: przesunąć pralkę/zmywarkę/bojler do 3 najtańszych godzin
- [ ] Nowa sekcja §7.7 "Scenariusz z aktywnym sterowaniem"
- [ ] Wiersz w tabeli 7.1: "RDN + proste przesunięcie"
- [ ] W §8 przeformułować: "RDN nie opłaca się biernie, opłaca się przy sterowaniu — i o X%"

### 3.2. Statystyki opisowe cen RDN
- [ ] Tabela: sezon × (średnia, mediana, SD, min, max, godziny <0, godziny >500)
- [ ] Wykres: 4 krzywe dobowe c̄_h nakładające się
- [ ] Nowa sekcja w §2.3 albo §6.4
- [ ] Poprawić "ceny ujemne" w §7.3 — po narzucie 0,435 cena detaliczna spada do 0,45-0,55 zł/kWh, NIE do zera

### 3.3. Próg 459 zł/MWh + analiza wrażliwości parametrów
- [ ] Wyprowadzić c_hurt,BEP = 1,10/1,23 − 0,435 = 0,459 zł/kWh = 459 zł/MWh
- [ ] Wykres tornado: c_G11 ∈ [1,00; 1,20], s_marża ∈ [0,05; 0,15], s_dyst ±20%
- [ ] Nowa sekcja "Analiza wrażliwości na parametry cenowe"
- [ ] Przemianować §7.5 → "Walidacja krzyżowa profil × sezon"

### 3.4. Znormalizowany CVaR
- [ ] Kolumna CVaR/średni koszt dobowy w tabeli 7.1
- [ ] LUB CVaR różnicy C_d^RDN − C_d^G12 (ciekawiej)
- [ ] Regenerować rys. 7.6 z znormalizowaną osią X

---

## FAZA 4: OPCJONALNE (jeśli czas)

### 4.1. Zawężenie wniosków w rozdz. 8
- [ ] "typowe polskie gospodarstwo" → "gospodarstwa z ogrzewaniem elektrycznym"
- [ ] Zaznaczyć że 24 przypadki = plan eksperymentu 6×4, nie próba losowa

### 4.2. Model dnia tygodnia
- [ ] MIN: dodać w rozdz. 8 jako "obciążenie w znanym kierunku, na niekorzyść RDN"

### 4.3. Opłaty stałe
- [ ] W rozdz. 8: "pominięte składniki stałe są rzędu X zł/mies., co przekracza zmierzoną różnicę... wniosek o BRAKU opłacalności RDN odporny, wniosek o marginalnej opłacalności nie byłby"

### 4.4. Literatura bottom-up
- [ ] 3-5 pozycji: Richardson, Thomson & Infield (CREST), Widén & Wäckelgård, Pflugradt
- [ ] Pół strony w rozdz. 2

### 4.5. Wielokrotne ziarna
- [ ] MIN: usunąć "istotnie" z H3, nazwać próg arbitralnym
- [ ] MAX: 5-10 przebiegów per konfiguracja

### 4.6. Naprawa CVaR na surowym e(d,h) [DUŻA WARTOŚĆ, MAŁY KOSZT]
Backend już ma tę ścieżkę w `calculateForTest`. Wystarczy żeby skrypt analizy brał `hourlyBreakdown` bez `build_daily_profile`. Zmiana ~10 linii Pythona → **przywraca sens CVaR i jitterowi**.
- [ ] Modyfikacja `analiza_wyniki.py`
- [ ] Rerun analizy (nie trzeba nowych testów)

### 4.7. Cap jesienny bojlera
- [ ] Sprawdzić `generate_seasonal.py` linie 141-144 — czy C-Jesień (cap 0,22 przy bazowym 0,25) MNIEJ niż wiosną (0,25) to błąd logiki

---

## FAZA 5: KOSMETYKA

- [ ] §4.1: "około 4000 pomiarów" → poprawić (77 989 na rys. 5.5)
- [ ] §3.2, §3.4: odwołania do wzoru (6.1) przed jego pojawieniem — przenieść
- [ ] (3.11)-(3.13) vs (6.1) — usunąć redundancję
- [ ] Ujednolicić jitter: tekst 15/10 vs screen 20/12 vs JSON 10/5 → jeden zestaw
- [ ] Tab. 4.1: dodać Recharts
- [ ] §7.7 vs §6.4: treść H4 rozjazd — ujednolicić
- [ ] Literówka "aggregatorzy" → "agregatorzy"
- [ ] Wstęp: mocniejsze wiązanie tytuł↔treść

---

## PRIORYTET WYKONANIA v2

**KROK 0 (najpierw!) — MUST-VERIFY, 30 min:**
1. Sprawdzić `simTimeStart` (0.1)
2. Sprawdzić `aggregateWindow timeSrc` (0.2)
3. Sprawdzić kompletność cen październik (0.3)
4. Sprawdzić sortowanie `rdnDaily` w backendzie (0.4)

**Jeśli 0.1-0.3 wypadną źle → fix + rerun 6h partii.**

**KROK 1 (najbardziej wpływowe, 1-2h):**
5. Test analityczny ΣE vs K_G11/33 — potwierdzić aliasing bojlera (1.0)
6. Fix bojlera: `cycle_length_minutes` 60 → 37 w JSON-ach (1.0)
7. Rerun partii 24 testów jeśli fix zastosowany (6h maszynowe)

**KROK 2 (tekst pracy, po rerun, ~3h):**
8. Efekt Simpsona (1.1)
9. CVaR redefinicja (1.2)
10. Próg G12 23,8% + tabela udziału nocnego (1.3)
11. Sprzeczność 8-10 kWh + tabela ΣE (2.1)
12. Sekcja "Ograniczenia numeryczne" z aliasingiem bojlera (2.2)

**KROK 3 (dodatkowa wartość, 4-6h):**
13. Statystyki cen RDN (3.2)
14. Próg 459 zł/MWh + tornado (3.3)
15. Znormalizowany CVaR (3.4)
16. **Naprawa CVaR na e(d,h)** (4.6) — 10 linii Pythona, przywraca sens

**KROK 4 (game-changer, 4h):**
17. Demand response scenariusz (3.1) — jedna sekcja, zmienia wymowę pracy

**KROK 5 (kosmetyka, 1h):**
18. Wszystko z FAZY 5

---

## PYTANIA NA OBRONĘ

1. "Uśrednił Pan zużycie po 30 dobach. Co mierzy Pana CVaR?" → §3.5 zaktualizowane (1.2)
2. "Skąd 1,10 zł/kWh dla G11 i dlaczego porównywalne z RDN oddolnie?" → potrzebna dekompozycja G11
3. "Praca nazywa się Smart Home. Gdzie sterowanie?" → §7.7 nowy demand response (3.1)
4. "Dlaczego uśrednia Pan procenty a nie kwoty?" → §7.1 obie miary (1.1)
5. "Czy przy innej relacji stawek G12 wnioski by się utrzymały?" → tornado (3.3)
6. "Czy sześć profili reprezentuje polskie gospodarstwa?" → zawężenie (4.1) + walidacja
7. "Jaka jest niepewność +0,97%?" → wielokrotne ziarna (4.5) lub przyznać brak
8. **NOWE:** "Czy bojler w Pana modelu nie ma problemu aliasingu przy próbkowaniu 5-min?" → sekcja Ograniczenia (2.2)
9. **NOWE:** "CVaR liczony na 2 najgorszych dobach z 30 — czy to nie za mała próba?" → §3.5 wprost (1.2)
