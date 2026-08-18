# PLAN NAPRAWY PRACY MAGISTERSKIEJ (v3)

**Deadline APD: 11 września 2026.** Stan na 18 sierpnia — zostały ~3,5 tygodnia.

Bazuje na: `recenzja_magisterka_igor.md`, `weryfikacja_kodu_uzupelnienie.md`, `audyt_backend_v3.md`.

Legenda: `[ ]` TODO · `[~]` w trakcie · `[x]` zrobione · `[-]` świadomie odpuszczone

---

## STAN NA TERAZ

**Kod: naprawiony, czeka na build i rerun.** Wszystkie zmiany są w working tree na
`feature/rdn-taryfy`, **niezacommitowane**. Kompilacja nie była testowana (brak Mavena
w środowisku, w którym powstały poprawki) — najbardziej ryzykowny fragment to listener `WriteErrorEvent` w `InfluxWriter`.
W repo nie ma wrappera Mavena, a build i tak idzie w Dockerze na EC2 (`backend/Dockerfile`,
stage `maven:3.9-eclipse-temurin-21`) — kompilacja weryfikuje sie przy `docker compose up --build`.

**Tekst pracy: nietknięty.** Żadna zmiana nie weszła jeszcze do `praca.tex`.

**Blokada:** cała FAZA 1–3 czeka na nową tabelę 7.1 z rerunu partii, z wyjątkiem
punktów 1.2 i 3.3, które można pisać równolegle.

---

## FAZA 0 — MUST-VERIFY · ZAMKNIĘTA

| # | Pytanie | Odpowiedź | Status |
|---|---|---|---|
| 0.1 | Czy `simTimeStart` ucięty do północy? | TAK, `TestRunner:221-226`, poprawnie (`ZonedDateTime.truncatedTo(DAYS)` w Europe/Warsaw) | `[x]` |
| 0.2 | Czy `aggregateWindow` przesuwa profil o +1 h? | NIE, `timeSrc: "_start"` obecny, `InfluxQueryService:103` | `[x]` |
| 0.3 | Czy październik ma pełne 30×24 ceny? | NIEZWERYFIKOWANE empirycznie — dodana twarda asercja w `analiza_wyniki.py`, wykryje przy najbliższym uruchomieniu | `[~]` |
| 0.4 | Czy `rdnDaily` sortowane przed VaR? | TAK, `CostCalculatorService:380` `Collections.sort()`. Było OK od początku | `[x]` |

- [ ] **0.3 — SQL do odpalenia na Neonie przed rerunem** (30 s):
```sql
SELECT delivery_date, COUNT(*) FROM energy_prices
WHERE market='RDN' AND delivery_date BETWEEN '2025-01-01' AND '2025-10-30'
GROUP BY delivery_date HAVING COUNT(*) <> 24 ORDER BY delivery_date;
```
Ma zwrócić **zero wierszy**. Uwaga: 26.10.2025 to zmiana czasu — PSE zwraca 100 kwadransów,
`PseApiClient.extractHour` mapuje oba bloki 02:00 na godzinę 2 i uśrednia 8 kwadransów.
Wynikiem są nadal 24 godziny, więc braku być nie powinno.

---

## FAZA 0.5 — AUDYT KODU v3 · WPROWADZONE

Wszystko poniżej jest w working tree, niezacommitowane.

| # | Problem | Plik | Status |
|---|---|---|---|
| B1 | Aliasing klimatyzacji (cykl 30 = wielokrotność 5): duty 0,40 → +25 %, 0,55 → +17,6 % | `AcSimulator`, generator | `[x]` cykl → 43 |
| B2 | Harmonogramy przez północ ucinane (`put(0,false)`): nocne AC 2 h z 8 h, cały profil E gasł o północy (~1,7 kWh/dobę ze strefy nocnej) | `generate_seasonal.split_overnight()` | `[x]` |
| B3 | Odwrócone pary ON/OFF w profilach letnich (ON 19:45 / OFF 19:15 → światło do 23:00) + duplikat minuty (ON i OFF o 23:30 → nigdy nie zapala) | `generate_seasonal.shift_evening_on()` + walidator | `[x]` |
| B4 | `/costs/projected` liczy na stawkach roku **okresu cenowego** (2025, G11 = 0,75), a rozdz. 7 na 2026 (1,10). Rys. 5.9: 634,29/(30×28,19) = 0,750 — potwierdzone | `CostCalculatorService:314` | `[ ]` **decyzja Igora** |
| B5 | `range(start: -30d)` liczony od czasu zapytania — obcinał 1–8 dób z każdego testu, rosło z opóźnieniem analizy | `InfluxQueryService:41,99` | `[x]` → `-400d` |
| B6 | Dwie ciche ścieżki utraty pomiarów bez licznika (MQTT `return` przy rozłączeniu, async WriteApi bez listenera) | `MqttPublisher`, `InfluxWriter`, `TestRunner`, `analiza_wyniki.py` | `[x]` |
| B7 | Cap jesienny obniżał bojler profilu C poniżej wartości bazowej (0,25 → 0,22) | `generate_seasonal:144` | `[x]` |
| B8 | Komputer profilu E: burst 25 min ≥ odstęp 15 min → 78 % duty, ~377 W zamiast ~150 W | `base/E_studenci.json` | `[x]` |
| B5b | **Retencja InfluxDB**: ucinanie `simTimeStart` do północy cofało okno o ≤24 h, więc przy 30 dniach symulacji i 30-dniowej retencji bucketu najstarsze punkty były odrzucane (HTTP 400 „outside of the retention period"). Potwierdzone w logach 18.08: odrzucone 10 h 55 min pierwszej doby (~1,5 % danych), test przez ~55 s realnych pokazywał 0 kWh | `TestRunner:257-278` | `[x]` zaokrąglanie w GÓRĘ (`plusDays(1)`) + log okna i marginesu |
| B6b | **Paho `maxInflight` = 10**: przy QoS 1 i 15 urządzeniach × 4 równoległych testach `publish()` rzucał „Too many publishes in progress", a pomiar przepadał. Utrata NIE była losowa — okno zapełnia się w trakcie pętli po urządzeniach, więc ginęły konsekwentnie urządzenia z KOŃCA listy, a generator dopisuje grzejniki na końcu konfiguracji zimowej. Zaniżało to zużycie największych odbiorników sezonu grzewczego | `MqttPublisher:72` | `[x]` `setMaxInflight(1000)` |
| A.3a | Faza cyklu z `minuteOfDay %` resetowała się o północy → zamrożone tętnienie godzinowe ±35 %, nieusuwalne przez uśrednianie po 30 dobach | `BaseSimulator.nextCycleTick()` + 3 symulatory | `[x]` |
| A.3b | Przy cyklu 37 duty 0,10 i 0,12 dawały to samo `4/37` — profile A i B/E miały identyczny bojler | cykl 37 → **43** | `[x]` |
| — | Angielskie nazwy profili w wyjściach: „Remote worker", „Para DINK", „Double Income, No Kids" | `base/B`, `base/F`, `analiza_wyniki.py` | `[x]` → „Pracownik zdalny", „Para bez dzieci" |

**Nienaprawione świadomie (nieaktywne przy obecnej konfiguracji):**

- `[-]` **B9** — cykl pralki/zmywarki/piekarnika nie przeżywa północy (`elapsed < 0`). Żadne urządzenie nie startuje po 22:45, ale jitter ±20 min może to wywołać przy startach ok. 22:50.
- `[-]` **B10** — `HeaterSimulator`/`TvSimulator` gubią pozostałe `params`, gdy brak `power_w`.
- `[-]` **B11** — `@Cacheable` + `range` względny; znika po B5.
- `[-]` **B14** — `year` z pierwszej godziny testu; partia przez sylwestra wzięłaby stawki poprzedniego roku.
- `[~]` **B12** — brak walidacji liczby rekordów w `PseApiClient`. Zabezpieczone od strony Pythona (twardy błąd zamiast ceny 0 zł), backend bez zmian.

---

## FAZA 0.9 — BUILD, DEPLOY, RERUN  ← **JESTEŚMY TUTAJ**

- [ ] `mvn -q compile` lokalnie. Jeśli błąd — prawie na pewno listenery w `InfluxWriter`;
      usunąć oba `listenEvents` i `flush()`, reszta zmian jest od nich niezależna.
- [ ] Commit + push (`feature/rdn-taryfy`)
- [ ] Deploy na EC2
- [ ] `upload_templates.ps1 -Url "http://3.77.28.199"` — **obowiązkowo przed partią**.
      Stara wersja skryptu POMIJAŁA szablon, jeśli nazwa już była w bazie, więc baza trzymała
      stare konfiguracje mimo wdrożonego backendu (wykryte 18.08: partia ruszyła na profilach
      sprzed poprawek — nazwa testu „Para pracujaca bez dzieci (DINK)" zamiast „Para bez dzieci").
      Skrypt przepisany na **synchronizację**: kasuje szablony „Profil *" i wgrywa od nowa,
      na końcu weryfikuje liczbę i brak angielskich nazw. Podgląd bez zmian: `-DryRun`.
- [ ] **Zmienić `BATCH_NAME_PREFIX` w `analiza_wyniki.py:67`** na prefiks nowej partii.
      Obecnie `[Partia 2026-08-06T18:57]` — bez zmiany skrypt znajdzie 0 testów.
- [ ] Partia 24 testów z UI (30 dni, ×720, emit=5), ~5–6 h maszynowe
- [ ] **Kontrola na starcie każdego testu:** w logu ma być linia
      `okno symulowane ... (margines nad granica retencji: X h Y min)`. Margines < 1 h → WARN.
- [ ] **Kontrola po partii:** w logach backendu mają być dwie linie na test —
      `„potok pomiarowy czysty"` oraz `„zagregowano 720 godzin"`. Cokolwiek innego = rerun.
- [ ] **EKSPORT DANYCH — `python analiza/eksport_danych.py`.** Priorytet: retencja InfluxDB
      kasuje pomiary dobę po dobie od 19 VIII; do 11 IX zniknie ~23 z 30 dób. Zrzut odcina
      analizę od EC2 i stanowi załącznik do pracy. Walidacja: 24 testy × 720 godzin.
- [ ] `python archetypes/oczekiwane_kwh.py` → porównać z `K_G11 / 33` z nowej tabeli.
      Rozbieżność > 3 % oznacza błąd w potoku, nie w konfiguracji.

### Referencja analityczna (oczekiwane kWh/dobę, `oczekiwane_kwh.py`)

| Profil | Zima | Wiosna | Lato | Jesień | udział nocny G12 (Z/W/L/J) |
|---|---|---|---|---|---|
| A Singiel-biuro | 37,3 | 10,4 | 21,1 | 16,7 | 27,9 % · 27,3 % · 37,5 % · **22,6 %** |
| B Pracownik zdalny | 43,5 | 16,7 | 27,3 | 22,9 | 31,4 % · 36,7 % · 40,9 % · 30,8 % |
| C Rodzina 2+2 | 53,3 | 28,5 | 39,1 | 32,5 | 27,0 % · 26,8 % · 32,8 % · 23,5 % |
| D Senior samotny | 38,5 | 11,8 | 22,3 | 19,2 | 25,5 % · **19,7 %** · 32,9 % · **19,4 %** |
| E Studenci | 43,8 | 16,9 | 27,4 | 23,2 | 37,7 % · 52,4 % · 50,5 % · 42,3 % |
| F Para bez dzieci | 46,6 | 17,7 | 28,3 | 25,9 | 28,3 % · 27,3 % · 34,9 % · 25,4 % |

Trzy wnioski jeszcze przed rerunem:

1. **A-Wiosna 10,4 kWh analitycznie vs 14,0 ze starej tabeli 7.1** (−26 %) — niezależne
   potwierdzenie skali aliasingu bojlera. Gotowy materiał do punktu 2.2.
2. **Zdanie „8–10 kWh na dobę" z §6.1 pasuje do wiosny (10,4), nie do lata (21,1)** —
   prawdopodobnie pomylony sezon w tekście, nie zła liczba.
3. **G12 przestaje wygrywać automatycznie.** D-wiosna 19,7 %, D-jesień 19,4 %,
   A-jesień 22,6 % są **poniżej progu 23,8 %** — tam G11 powinna wyjść taniej niż G12.
   To jakościowa zmiana względem starych wyników (G12 wygrywała 24/24) i zmienia
   treść punktu 1.3.

---

## FAZA 1 — KRYTYCZNE POPRAWKI TEKSTU

### 1.1. Efekt Simpsona w H1 · `[ ]` — czeka na nową tabelę
Średnia procentów: +0,97 %. Ze średnich kwot: **−0,46 %** (RDN droższy). To samo dla
G12: −8,11 % (proc.) vs −9,21 % (kwoty). Kod: `analiza_wyniki.py:627`.
- [ ] §7.1: podać obie miary jawnie i je nazwać
- [ ] §7.1: wyjaśnić mechanizm (RDN wygrywa procentowo tam, gdzie kwoty małe — wiosna 350–590 zł; przegrywa tam, gdzie duże — zima 1300–2060 zł)
- [ ] §7.7 tabela hipotez: H1 → „Odrzucona w ujęciu kwotowym"
- [ ] §8: zdanie o wzmocnieniu wniosku
- [ ] `analiza_wyniki.py`: dodać wiersz z agregatem kwotowym obok średniej procentów

### 1.2. CVaR — redefinicja · `[ ]` **można pisać teraz, nie czeka na rerun**
- [ ] §3.5: usunąć zdanie o „zbiegu wysokiego zużycia i wysokich cen" — przy uśrednionym E_h zużycie jest deterministyczne
- [ ] §3.5: „CVaR kwantyfikuje ekspozycję na zmienność cen hurtowych przy ustalonym profilu zużycia"
- [ ] §3.5: „Uzyskane wartości stanowią dolne oszacowanie ryzyka rzeczywistego"
- [ ] §3.5: dla n = 30 i estymatora liniowego zbiór `{c ≥ VaR}` ma **dokładnie 2 elementy** → CVaR to średnia z 2 najdroższych dób, ogon 2/30 ≈ **6,7 %, nie 5 %**
- [ ] §3.5: uwaga matematyczna `K_RDN = 30·Σ_h E_h·c̄_h` — koszt zależy wyłącznie od średniego kształtu dobowego cen
- [ ] §3.5: rozjazd backend (nearest-rank) vs Python (interpolacja liniowa) — zaznaczyć, że rozdz. 7 pochodzi ze ścieżki Pythona

### 1.3. Próg opłacalności G12 · `[ ]` — treść zmieniona przez nowe dane
`x = (1,25 − 1,10)/(1,25 − 0,62) = 23,8 %`. Profil płaski daje 41,7 %.
**Nowość:** po naprawach D-wiosna/D-jesień/A-jesień spadają poniżej progu, więc teza
„G12 wygrywa zawsze" już nie obowiązuje — teraz to „G12 wygrywa wszędzie tam, gdzie
udział nocny przekracza 23,8 %, a nie przekracza go tylko profil D poza sezonem grzewczym".
- [ ] §7.2: wyprowadzić próg
- [ ] §7.2: wstawić tabelę udziału nocnego (jest wyżej, z `oczekiwane_kwh.py`)
- [ ] §7.2: usunąć „co potwierdza hipotezę…" — takiej hipotezy nie ma w H1–H5
- [ ] Zweryfikować stawki 0,62 / 1,25 / 1,10 wobec rzeczywistej taryfy PGE/Energa 2026 i zacytować

---

## FAZA 2 — SPRZECZNOŚCI

### 2.1. Tabela ΣE + zdanie o zużyciu letnim · `[ ]`
- [ ] §6.1: poprawić „8–10 kWh na dobę" (dotyczy wiosny, nie lata)
- [ ] §6 lub §7: wstawić tabelę ΣE per przypadek (potrzebna też do interpretacji CVaR)
- [ ] Skomentować, dlaczego D-Senior zimą (38,5) ≈ A-Singiel (37,3) — senior cały dzień w domu

### 2.2. Sekcja „Ograniczenia numeryczne modelu pomiarowego" · `[ ]`
- [ ] Nowa podsekcja w rozdz. 6 albo 8
- [ ] Opisać aliasing przy próbkowaniu 5-minutowym: mechanizm `ceil(onPortion/5)`, dotknięte urządzenia (bojler, klimatyzacja), skala do +67 %
- [ ] Opisać naprawę: długość cyklu względnie pierwsza z 5 i 60 (43 min) + faza z licznika absolutnego
- [ ] Podać porównanie A-Wiosna 14,0 → 10,4 kWh jako weryfikację empiryczną
- [ ] **Kierunek obciążenia napisać dopiero po zobaczeniu nowych liczb** — patrz ostrzeżenie niżej

---

## FAZA 3 — WARTOŚĆ MERYTORYCZNA

### 3.1. Scenariusz demand response · `[x]` **POLICZONE** (18 VIII), `[ ]` do wpisania w tekst
Praca nazywa się „Model systemu Smart Home", §2.5 jest o demand response, a badanie
symuluje wyłącznie biernego odbiorcę. To pierwsze pytanie, jakie padnie na obronie.
- [x] Skrypt `analiza/demand_response_zmierzone.py` — wariant B, liczony na **zmierzonych** profilach `E_h` z partii 24 testów (nie na modelu analitycznym)
- [x] Wyniki: `analiza/wyniki_analizy/demand_response_zmierzone.csv` + `demand_response_wydruk.txt`

**Liczby (iloraz średnich, 24 przypadki × 30 dób):**

| Wielkość | Biernie | Aktywnie |
|---|---|---|
| RDN względem G11 | −2,2 % (droższa) | **+4,3 % (tańsza)** |
| RDN względem G12 | −7,1 % | **−14,7 %** |
| Rachunek RDN | 22 447 zł | 21 024 zł (**−6,3 %**) |
| Rachunek G12 | 20 955 zł | 18 322 zł (**−12,6 %**) |
| Udział strefy nocnej | 32,0 % | 45,9 % |

**Wniosek, który idzie do §7.7 i §8:** sterowanie odwraca znak porównania RDN–G11
(z −2,2 % na +4,3 %), ale **nie** zmienia rankingu ogólnego — G12 reaguje na to samo
sterowanie mocniej (−12,6 % wobec −6,3 %) i pozostaje najtańsza. Przyczyna jest
policzalna: stawka nocna G12 wynosi 0,62 zł/kWh, a średnia sześciu najtańszych godzin
RDN to 0,95 (zima), 0,65 (wiosna), 0,85 (lato), 0,90 (jesień) zł/kWh — czyli
**strefa nocna G12 jest tańsza od najtańszych godzin RDN w każdym sezonie**.
Stały narzut 0,435 zł/kWh netto nie podlega przesunięciu i tłumi rozpiętość RDN.

- [ ] Nowa sekcja §7.7 „Scenariusz z aktywnym sterowaniem"
- [ ] Wiersz w tabeli 7.1: „RDN + proste przesunięcie"
- [ ] §8: „RDN nie opłaca się biernie, opłaca się przy sterowaniu względem G11 (+4,3 %) — ale nadal przegrywa z G12, która na sterowaniu zyskuje dwa razy więcej"
- [ ] Opisać założenia: kontrfaktyk ex post, doskonała znajomość cen D+1, bezkosztowe przesunięcie → **górne** ograniczenie wartości sterowania
- [ ] Opisać dekompozycję profilu (składnik elastyczny odtworzony z konfiguracji i przeskalowany, sztywny jako różnica; korekta ≤ 0,58 % zużycia dobowego)
- [ ] Wspomnieć o regule racjonalnego sterownika: rezygnuje z przesunięcia w dobach, w których ograniczenie komfortu (AGD gotowe do 07:00) czyni je nieopłacalnym — średnio 2,2 doby na 30, u seniora wiosną aż 22/30

### 3.2. Statystyki opisowe cen RDN · `[x]` policzone, `[ ]` do wpisania w tekst
Ceny RDN to najważniejsza dana wejściowa i nie ma dla nich w pracy żadnej statystyki.
- [ ] Tabela: sezon × (średnia, mediana, SD, min, max, liczba godzin < 0)
- [ ] Wykres: 4 nałożone krzywe dobowe `c̄_h` — to jest dokładnie to, co determinuje wynik RDN
- [ ] §7.3: doprecyzować „ceny ujemne" — po narzucie 0,435 zł/kWh netto cena detaliczna spada do ~0,45–0,55 zł/kWh, nie do zera

### 3.3. Próg 459 zł/MWh + analiza wrażliwości · `[x]` policzone, `[ ]` do wpisania w tekst
`c_hurt,BEP = 1,10/1,23 − 0,435 = 0,459 zł/kWh = 459 zł/MWh`
- [x] Skrypt `analiza/wrazliwosc_bojler.py` — 5 wariantów modyfikatora sezonowego bojlera × 24 przypadki, na rzeczywistych cenach 2025

**Wrażliwość na założenie o bojlerze (najbardziej arbitralny parametr modelu):**

| Wariant | kWh/dobę | udział bojlera | RDN vs G11 | RDN vs G12 | G12 vs G11 | G11 > G12 |
|---|---|---|---|---|---|---|
| obecny (addytywny) | 27,8 | 34,2 % | −2,33 % | −7,11 % | 4,46 % | 4/24 |
| ×1,40 | 26,5 | 30,9 % | −2,35 % | −6,81 % | 4,17 % | 5/24 |
| ×1,35 | 26,5 | 30,9 % | −2,35 % | −6,81 % | 4,17 % | 5/24 |
| ×1,25 | 26,2 | 30,0 % | −2,36 % | −6,72 % | 4,09 % | 5/24 |
| bez modyfikatora | 25,4 | 28,0 % | −2,40 % | −6,57 % | 3,92 % | 6/24 |

**Wniosek:** w całym zakresie prawdopodobnych wariantów wynik RDN względem G11 zmienia
się o **0,07 p.p.** (−2,33 % → −2,40 %) — konkluzja pracy jest odporna na to założenie
i **przeliczanie partii nie jest uzasadnione**. Przesunięcie dotyczy natomiast przewagi
G12 (4,46 % → 3,92 %) i liczby przypadków, w których G11 wygrywa z G12 (4/24 → 6/24),
co należy uczciwie odnotować.

- [ ] Wyprowadzić w §7 — elegancko rozdziela efekt poziomu cen od efektu kształtu
- [ ] Tornado: `c_G11 ∈ [1,00; 1,20]`, `s_marża ∈ [0,05; 0,15]`, `s_dyst ± 20 %`
- [ ] Wstawić powyższą tabelę do §7.5 jako właściwą analizę wrażliwości
- [ ] Przemianować dotychczasowy §7.5 na „Walidacja krzyżowa profil × sezon" — obecna nazwa „analiza wrażliwości" jest nadużyciem

### 3.3b. Stawki i strefy taryfy G12 · `[!]` **BLOKUJE TEKST §7.2**
Moduł `analiza/taryfy.py` (jedno źródło prawdy) + `analiza/wrazliwosc_g12.py`.

**Defekt do usunięcia:** strefy G12 są sezonowe. Tańsze okno popołudniowe to
13:00–15:00 w okresie zimowym (1 X – 31 III) i **15:00–17:00 w letnim (1 IV – 30 IX)**.
W kodzie zaszyte było 13:00–15:00 przez cały rok → Wiosna (kwiecień) i Lato (lipiec)
liczone błędnie. Agregat rusza się o 0,2 p.p., ale pojedyncze przypadki znacznie
więcej — Pracownik zdalny latem: udział nocny **42,0 % → 33,1 %**.

**Wynik kluczowy — cała różnica G11 vs G12 sprowadza się do jednej liczby:**
`τ = (c_dzień − c_G11) / (c_dzień − c_noc)`; G12 jest tańsza dokładnie wtedy, gdy
zmierzony udział strefy nocnej przekracza τ. Sprawdzone numerycznie na
**192 kombinacjach (8 wariantów stawek × 24 przypadki) — 0 rozbieżności.**

| Wariant stawek | τ | G12 vs G11 | RDN vs G12 | G11 > G12 |
|---|---|---|---|---|
| 1,25 / 0,62 (obecny) | 23,8 % | 4,43 % | −6,90 % | 3/24 |
| 1,17 / 0,77 | 17,5 % | 5,11 % | −7,66 % | **0/24** |
| 1,15 / 0,85 (wąski spread) | 16,7 % | 4,06 % | −6,48 % | 0/24 |
| 1,30 / 0,60 (szeroki spread) | 28,6 % | 1,90 % | −4,14 % | 10/24 |

Znak wniosku (G12 najtańsza) trzyma się w **całym** zakresie. Rozstrzygnięcia
wymaga natomiast zdanie „G11 bywa tańsza od G12": przy obecnych stawkach dotyczy
3 przypadków (A-Jesień, D-Wiosna, D-Jesień), przy 1,17/0,77 — żadnego.

- [ ] **Igor: pobrać PGE Obrót „ceny energii dla grup G 2026" + PGE Dystrybucja „taryfa 2026"** (WebFetch zwraca 403 / błąd certyfikatu)
- [ ] Wpisać ustalone stawki do `taryfy.py`, przełączyć na nie `analiza_wyniki.py`, `demand_response_zmierzone.py`, `wrazliwosc_bojler.py` (mają jeszcze własne stałe)
- [ ] Regenerować tabele i rysunki rozdz. 7 — **jednym przebiegiem, dopiero po ustaleniu stawek**
- [ ] §7.2: wzór na τ + tabela wrażliwości powyżej; §3: przypis o sezonowości stref z powołaniem na taryfę OSD

### 3.3c. Spójność backendu z rozdziałem 7 · `[x]` kod gotowy, `[ ]` deploy
Backend liczył na starych parametrach, więc zrzuty z UI w rozdz. 5 pokazywałyby inne
stawki niż tabela 7.1. Poprawione w `TariffParams.java`:

- stawki 2026 → **1,0991 / 1,2491 / 0,6111** zł/kWh, rozpisane w komentarzu na składniki z powołaniem na obie taryfy
- `rdnDistributionNet` **0,33 → 0,3904** (dochodzą jakościowa 0,0332 + OZE 0,0073 + kogeneracyjna 0,0030); narzut razem **0,4954**
- strefy G12 **sezonowe** — `g12PriceForHour(year, LocalDate, hour)`, stara sygnatura oznaczona `@Deprecated`
- `forYear()` clampuje w dół dla lat < 2024 zamiast podstawiać najnowszy rok pod datę historyczną
- **B4 zamknięte**: `midYear` liczony przez `Period.getDays()` zwracał samą składową dni (dla stycznia 29 → rok 2025 → G11 = 0,75). Zastąpione stałą `TariffParams.ANALYSIS_YEAR = 2026` — projekcja świadomie łączy kształt cen hurtowych 2025 z poziomem stawek regulowanych 2026, spójnie z rozdz. 7
- zaktualizowane wywołania: `CostCalculatorService` (2), `ExportService` (2)

Logika zweryfikowana kompilacyjnie: zima h13 = 0,6111 / h15 = 1,2491, lato h13 = 1,2491 /
h15 = 0,6111, po 10 godzin taniej strefy w obu okresach, `rdnFinalPrice(2026, 0)` = 0,609342
= 0,4954 × 1,23 — zgodne z `taryfy.py`.

- [ ] Deploy (backend + front jednym przebiegiem) i nowe zrzuty do rozdz. 5
- [ ] Kontrola po deployu: w UI `koszt G11 / zużycie` ma dać **1,0991**, nie 1,10

### 3.4. Znormalizowany CVaR · `[ ]`
Rys. 7.6 miesza ryzyko ze skalą zużycia (profil C ma najwyższy CVaR, bo zużywa najwięcej).
- [ ] Kolumna `CVaR / średni koszt dobowy` w tabeli 7.1, albo CVaR różnicy `C_d^RDN − C_d^G12`
- [ ] Regenerować rys. 7.6 ze znormalizowaną osią X

---

## FAZA 4 — JEŚLI STARCZY CZASU

- [ ] **4.1** §8: „typowe polskie gospodarstwo" → „gospodarstwa z ogrzewaniem elektrycznym"; zaznaczyć, że 24 przypadki to plan 6×4, nie próba losowa
- [ ] **4.2** §8: model dnia tygodnia jako pominięcie o znanym kierunku (ceny RDN niższe w weekendy, zużycie wyższe w dzień → zerowana kowariancja działa na niekorzyść RDN)
- [ ] **4.3** §8: opłaty stałe rzędu X zł/mies. przekraczają zmierzoną różnicę RDN–G11 (4,40 zł/mies.)
- [ ] **4.4** Literatura bottom-up: Richardson, Thomson & Infield (CREST), Widén & Wäckelgård, Pflugradt — pół strony w rozdz. 2
- [ ] **4.5** H3: usunąć słowo „istotnie" albo nazwać próg 2 p.p. arbitralnym przyjętym a priori
- [ ] **4.6** CVaR na surowym `e(d,h)` zamiast na `E_h` — backend ma tę ścieżkę w `calculateForTest`, wystarczy pominąć `build_daily_profile`. ~10 linii, przywraca sens jitterowi
- [ ] **4.7** B4: decyzja — parametr `tariffYear` w backendzie albo regeneracja rys. 5.9 z opisem w podpisie

---

## FAZA 5 — KOSMETYKA

- [ ] §4.1: „około 4000 pomiarów" → poprawić (rys. 5.5 pokazuje 77 989)
- [ ] §3.2, §3.4: odwołania do wzoru (6.1) przed jego pojawieniem — przenieść wzór do rozdz. 3
- [ ] (3.11)–(3.13) vs (6.1): usunąć redundancję
- [ ] Ujednolicić jitter: tekst 15/10 vs zrzut 20/12 vs JSON 10/5
- [ ] Tab. 4.1: dopisać Recharts
- [ ] §7.7 vs §6.4: rozjazd w treści H4
- [ ] Literówka „aggregatorzy" → „agregatorzy"
- [ ] Wstęp: mocniejsze wiązanie tytułu z treścią

---

## KALENDARZ DO 11 WRZEŚNIA

| Termin | Zakres |
|---|---|
| 18–19 VIII | Build, deploy, upload, partia 24 testów. Równolegle: 1.2 i 3.3 (nie zależą od nowych liczb) |
| 20–22 VIII | Nowa tabela 7.1. FAZA 1 w całości (1.1, 1.3) + 2.1, 2.2 |
| 23–27 VIII | FAZA 3: demand response (3.1), statystyki cen (3.2), znormalizowany CVaR (3.4) |
| 28–31 VIII | FAZA 4 wg pozostałego czasu; decyzja o B4 |
| 1–5 IX | FAZA 5, przegląd całości, spójność tabel i rysunków |
| 6–9 IX | Bufor: promotor, korekta, wydruk |
| 11 IX | APD |

---

## OSTRZEŻENIE — ZMIANA WZGLĘDEM POPRZEDNIEJ TEZY

Do audytu v3 obowiązywało: „wszystkie znalezione błędy zawyżają atrakcyjność RDN i G12,
więc korekta wzmocni wniosek o nieopłacalności RDN". **To już nie jest prawdą.**

| błąd | kierunek |
|---|---|
| aliasing bojlera i klimatyzacji | ↑ płaska składowa zużycia → **na korzyść RDN i G12** |
| ucięte harmonogramy przez północ (B2) | ↓ zużycie nocne → **przeciw G12 i RDN** |
| światło palące się do 23:00 (B3) | ↑ zużycie w szczycie wieczornym → **przeciw RDN** |
| tętnienie bojlera (A.3a) | losowe, wartość oczekiwana ≈ 0 |

B2 i B3 działają przeciwnie do B1. **Kierunku zmiany wyniku nie da się przewidzieć
przed rerunem** — zdania do punktu 2.2 nie wolno przepisać z planu, trzeba je napisać
po porównaniu nowej tabeli 7.1 ze starą.

Co się nie zmienia: praca uczciwie raportuje wynik negatywny i konfrontuje go
z literaturą (§7.6). Ta narracja zostaje niezależnie od tego, w którą stronę pojadą liczby.

---

## PYTANIA NA OBRONĘ

1. „Uśrednił Pan zużycie po 30 dobach — co w takim razie mierzy CVaR?" → §3.5 po 1.2
2. „Skąd 1,10 zł/kWh dla G11 i dlaczego to porównywalne z RDN składanym oddolnie?" → potrzebna dekompozycja G11 na te same składniki
3. „Praca nazywa się Smart Home — gdzie sterowanie?" → §7.7 po 3.1
4. „Dlaczego uśrednia Pan procenty, a nie kwoty?" → §7.1 po 1.1
5. „Czy przy innej relacji stawek G12 wnioski by się utrzymały?" → tornado, 3.3
6. „Czy sześć profili reprezentuje polskie gospodarstwa?" → zawężenie 4.1
7. „Jaka jest niepewność +0,97 %?" → 4.5 albo przyznać brak
8. „Czy nie ma aliasingu przy próbkowaniu 5-minutowym?" → sekcja z 2.2
9. „CVaR na 2 najgorszych dobach z 30 — czy to nie za mała próba?" → §3.5 po 1.2
