# PO POWROCIE — 18 sierpnia, ~16:00

Stan: partia 24 testów `[Partia 2026-08-18T09:38]` startowała o 09:38, koniec ok. 15:30–16:00.
Backend zamknięty. Nic nie wymaga deployu przed odbiorem wyników.

---

## KROK PO KROKU

### 1. Czy partia jest kompletna
Na liście testów wszystkie 24 mają mieć „Zakończony". Potem w logach:

```bash
sudo docker compose logs backend --tail=20000 | grep -c "potok pomiarowy czysty"
sudo docker compose logs backend --tail=20000 | grep -c "zagregowano 720 godzin"
```

Obie liczby mają dać **24**. Jeśli mniej — nie idź dalej, wklej wynik do czatu.

Szum od botów (`No static resource api/crm/info.php`) ignoruj, to skanery internetowe,
nie Twoja aplikacja. Filtr na sensowne linie:

```bash
sudo docker compose logs backend --tail=2000 | grep -E "potok pomiarowy|zagregowano|okno symulowane|WRITE ERROR|Blad publikacji"
```

### 2. EKSPORT DANYCH — najpilniejsze, nie odkładaj
Retencja InfluxDB kasuje pomiary dobę po dobie od 19 VIII. Do 11 IX zniknie ~23 z 30 dób.

```powershell
cd D:\Programy\PRACA_MAGISTERSKA\analiza
python eksport_danych.py
```

Sam wykrywa prefiks partii. Ma wypisać `[OK] komplet: 24 testy x 720 godzin`.
Po tym kroku dane są u Ciebie na dysku i czas przestaje grać rolę.

### 3. Brakująca doba cenowa (26.10.2025 — zmiana czasu)
```powershell
$key = (Get-Content D:\Programy\PRACA_MAGISTERSKA\analiza\api_key.txt -Raw).Trim()
Invoke-RestMethod -Method POST -Uri "http://3.77.28.199/api/v1/prices/fetch?date=2025-10-26" -Headers @{ "X-API-Key" = $key }
```
Ma zwrócić `savedRecords: 24`. Jeśli 0 — PSE nie ma tego dnia, jesień zostaje na 29 dobach
i trzeba to opisać w pracy.

### 4. Prefiks partii w skrypcie analizy
`analiza/analiza_wyniki.py`, linia 67:
```python
BATCH_NAME_PREFIX = "[Partia 2026-08-18T09:38]"
```

### 5. Analiza
```powershell
python analiza_wyniki.py
```

### 6. Przebudowa frontu (poprawka już w kodzie, czeka na deploy)
Kafelek „Zużycie" pokazywał wartość 5× za małą. Poprawione w `TestDetailsPage.tsx`.
Po przebudowie profil D-Wiosna ma pokazać ok. **365 kWh**, nie 73.

```bash
cd ~/SymulatorSH && git pull
cd infra && sudo docker compose up --build -d frontend
```
(tylko `frontend`, backend zostaje w spokoju)

Dopiero po tym rób nowe zrzuty ekranu do rozdziału 5.

---

## CO ZOSTAŁO ZROBIONE POD TWOJĄ NIEOBECNOŚĆ

- `analiza/statystyki_cen.py` — statystyki opisowe cen RDN + `figures/wykres_8_ceny_dobowe.png`
- `analiza/eksport_danych.py` — zrzut danych źródłowych (patrz krok 2)
- `analiza/demand_response.py` — scenariusz aktywnego sterowania, wariant B
- `frontend/.../TestDetailsPage.tsx` — poprawka kafelka „Zużycie"
- Sprawdzenie realnych cenników PGE na 2026 — patrz niżej, jest problem

---

## OTWARTE DECYZJE (nie pilne, ale do rozstrzygnięcia)

### A. Modyfikator zimowy bojlera — decyzja po zobaczeniu wyników
Obecnie addytywny `+0,15`, co podnosi CWU singla o 150 %. Realny wzrost zimowy to 25–40 %.
Skutek: zawyżony udział strefy nocnej, czyli **zawyżona przewaga G12** — Twój główny wniosek.
Poprawka: `base * 1,35` zamiast `base + 0,15` w `generate_seasonal.py`. **Wymaga rerunu partii.**

### B. Stawki G12 — sprawdzone i wyszedł problem
G11 = 1,10 zł/kWh brutto **potwierdzone** przez dwa niezależne źródła. Ale dla G12 źródła
się rozjeżdżają i to zmienia wszystko:

| Źródło | noc | dzień | próg opłacalności G12 |
|---|---|---|---|
| Twoje założenie | 0,62 | 1,25 | **23,8 %** |
| cenypradu.info | 0,77 | 1,17 | 17,5 % |
| akademia-fotowoltaiki | ~0,68 | ~1,46 | **46,2 %** |

Przy 46,2 % G12 przestaje wygrywać dla większości profili. Potrzebna **oficjalna taryfa PGE
zatwierdzona przez URE**, nie blogi porównywarkowe.

### C. Godziny stref G12 mogą być sezonowe
Jedno ze źródeł podaje, że popołudniowe okno taniej strefy to 13:00–15:00 zimą,
ale **15:00–17:00 latem**. Twój model ma na sztywno `{22,23,0-5,13,14}` przez cały rok.
Do zweryfikowania w oficjalnej taryfie.

**Dobra wiadomość: B i C nie wymagają rerunu.** To parametry liczone w Pythonie na gotowym
profilu zużycia. Poprawka to kilka stałych i przeliczenie analizy.

---

## CO WYMAGA RERUNU, A CO NIE

**Wymaga** (zmienia profil zużycia `E_h`): bojler, klimatyzacja, harmonogramy, moce urządzeń.

**Nie wymaga** (liczone w Pythonie): stawki G11/G12/RDN, VAT, marża, godziny stref G12,
okna cenowe, metoda VaR/CVaR, sposób agregacji, demand response, wszystkie tabele i wykresy.
