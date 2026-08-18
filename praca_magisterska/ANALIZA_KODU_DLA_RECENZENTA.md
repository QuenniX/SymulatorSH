# Analiza techniczna kodu SymulatorSH — dossier dla recenzenta

Poniższy dokument prezentuje _dokładny_ stan implementacji obliczeń energetycznych,
taryfowych i statystycznych w projekcie SymulatorSH. Wszystkie fragmenty kodu
cytowane są z repozytorium `D:\Programy\PRACA_MAGISTERSKA\` (branch working).
Numery linii pochodzą z aktualnych plików źródłowych. Cel: umożliwić recenzentowi
weryfikację metodologiczną bez konieczności samodzielnej lektury kodu.

**Kluczowa uwaga na wstępie**: wszystkie ścieżki symulatorów urządzeń są w
`.../executor/simulator/` (nie w `.../simulator/`, jak sugerował brief), a klasy
`CostCalculatorService` i `TariffParams` znajdują się w pakiecie `.../tariff/`
(nie `.../cost/`).

---

## 1. Symulator urządzeń

### 1.1. Interfejs `DeviceSimulator` i pętla główna

`backend/src/main/java/pl/smarthome/platform/executor/simulator/DeviceSimulator.java`
(linie 11-26):

```java
public interface DeviceSimulator {
    String getDeviceId();
    String getDeviceType();

    /**
     * Aktualizuje stan wewnętrzny i zwraca chwilową moc pobieraną.
     *
     * @param simulatedMinuteOfDay numer minuty od początku symulowanej doby (0..1439)
     * @return moc chwilowa w watach (zawsze >= 0)
     */
    double updatePower(int simulatedMinuteOfDay);
}
```

**FAKT KRYTYCZNY**: metoda `updatePower` z definicji zwraca moc CHWILOWĄ w
watach dla konkretnej minuty doby (0..1439). Nie ma tu żadnej integracji ani
uśredniania po interwale — jest to sampling punktowy.

### 1.2. Pętla emisji w `TestRunner`

`backend/src/main/java/pl/smarthome/platform/executor/TestRunner.java`
(linie 221-239):

```java
for (int minute = 0; minute < totalMinutes; minute++) {
    int minuteOfDay = minute % 1440;

    // Emit pomiar tylko co N minut symulowanych (zmniejsza obciazenie InfluxDB)
    if (minute % emitEveryN == 0) {
        long simTimeMs = simTimeStart + (long) minute * 60_000L;
        for (DeviceSimulator sim : simulators) {
            double power = sim.updatePower(minuteOfDay);
            mqttPublisher.publishPower(testId, sim.getDeviceId(), power, simTimeMs);
        }
    } else {
        // W minutach bez emisji nadal aktualizujemy stan symulatorow (zeby liczyly cykle)
        for (DeviceSimulator sim : simulators) {
            sim.updatePower(minuteOfDay);
        }
    }
    ...
    Thread.sleep(stepMs);
}
```

Wartość domyślna `emitEveryN` = 5 (linia 169-171): zapisujemy jeden punkt
mocy co 5 symulowanych minut. `simTimeMs` przypisany jest do znacznika
początku tego okna 5-minutowego.

### 1.3. Kod klas urządzeń — parametry i formuły

| Urządzenie | Plik | Model | Domyślne parametry |
|---|---|---|---|
| BOILER | `BoilerSimulator.java` | duty cycle (moc stała w części ON, 0 w OFF) | 2000 W, duty=0.17, cycle=60 min |
| REFRIGERATOR | `RefrigeratorSimulator.java` | duty cycle | 150 W, duty=0.4, cycle=37 min |
| AC | `AcSimulator.java` | schedule + duty cycle wewnątrz ON | 1000 W, duty=0.5, cycle=30 min |
| KETTLE | `KettleSimulator.java` (dziedziczy z WASHER) | event-driven cykl 1x | 2000 W, cycle=3 min |
| WASHER | `WasherSimulator.java` | event-driven, po ON zużywa moc przez `cycle_minutes` | 2000 W, 60 min |
| DISHWASHER | `DishwasherSimulator.java` | 3 fazy: nagrzewanie / mycie / suszenie | 1800/200/1500 W, 10/60/20 min |
| OVEN | `OvenSimulator.java` | schedule + pulsowanie grzałki wewnątrz | 2500 W, cycle=60 min, 5 min ON / 3 min OFF |
| LIGHT | `LightSimulator.java` | timeline ON/OFF, stała moc na ON | 60 W |
| TV | `TvSimulator.java` (dziedziczy z LIGHT) | jak LIGHT | 120 W |
| HEATER | `HeaterSimulator.java` (dziedziczy z LIGHT) | jak LIGHT | 1500 W |
| ROUTER | `RouterSimulator.java` (dziedziczy z LIGHT) | jak LIGHT (always_on) | 15 W |
| COMPUTER | `ComputerSimulator.java` | idle + losowe bursty | idle=100 W, burst=300 W, długość 5 min co ~20 min |

#### BOILER (linie 27-47):

```java
this.powerW = getDoubleParam("power_w", 2000);
this.dutyCycle = Math.max(0.05, Math.min(0.95, getDoubleParam("duty_cycle", 0.17)));
this.cycleLengthMinutes = getIntParam("cycle_length_minutes", 60);
this.onPortionMinutes = (int) Math.round(cycleLengthMinutes * dutyCycle);
...
public double updatePower(int simulatedMinuteOfDay) {
    if (isAlwaysOff()) return 0;
    int positionInCycle = simulatedMinuteOfDay % cycleLengthMinutes;
    if (positionInCycle < onPortionMinutes) {
        return applyPowerJitter(powerW);
    }
    return 0;
}
```

To jest **prosty deterministyczny prostokąt**: dla domyślnych wartości (60 min /
0.17) bojler jest ON przez pierwsze 10 minut każdej pełnej godziny. Ten sam
wzorzec powtarza się każdą dobę – nie ma korelacji z użyciem prysznica ani
rozbiorami wody. Duty cycle bierze parametr z konfiguracji (JSON).

#### KETTLE (dziedziczy z WASHER, event-driven):

`WasherSimulator.updatePower` (linie 61-82):

```java
public double updatePower(int simulatedMinuteOfDay) {
    String action = userActions.get(simulatedMinuteOfDay);
    if (action != null) {
        if ("ON".equals(action)) {
            cycleStartMinute = simulatedMinuteOfDay;
            userTurnedOff = false;
        } else if ("OFF".equals(action)) {
            userTurnedOff = true;
        }
    }

    if (userTurnedOff || cycleStartMinute == null) return 0;

    int elapsed = simulatedMinuteOfDay - cycleStartMinute;
    if (elapsed < 0 || elapsed >= cycleMinutes) return 0;
    return applyPowerJitter(powerW);
}
```

Czajnik ma domyślny `cycle_minutes = 3`, `power_w = 2000` (`KettleSimulator.adjustDefaults`).
Zdarzenie ON o `at:"07:00"` uruchamia cykl trwający dokładnie 3 minuty (minuty
420, 421, 422). Kolejne ON o 19:00 startuje kolejny cykl. Nie ma
mechanizmu rozgrzewania (moc od razu = 2000 W).

#### COMPUTER — stochastyczny (linie 84-116):

```java
public double updatePower(int simulatedMinuteOfDay) {
    ...
    if (nextBurstStart == null) {
        nextBurstStart = simulatedMinuteOfDay + random.nextInt(Math.max(1, burstIntervalMinutes));
    }
    if (currentBurstEnd == null && simulatedMinuteOfDay >= nextBurstStart) {
        currentBurstEnd = simulatedMinuteOfDay + burstLengthMinutes;
        nextBurstStart = currentBurstEnd + random.nextInt(Math.max(1, burstIntervalMinutes));
    }
    if (currentBurstEnd != null && simulatedMinuteOfDay < currentBurstEnd) {
        return applyPowerJitter(burstPowerW);
    }
    ...
    return applyPowerJitter(idlePowerW);
}
```

Odstępy między burstami — `random.nextInt(burstIntervalMinutes)` (rozkład
JEDNOSTAJNY dyskretny 0..N-1). To są jedyne stochastyczne skoki mocy;
poza tym pozostaje `idlePowerW`.

### 1.4. Jitter — wnioski o rozkładach

`BaseSimulator.applyPowerJitter` (linie 53-60):

```java
protected double applyPowerJitter(double power) {
    if (globalJitterPowerPercent <= 0 || power <= 0) return power;
    double sigma = power * globalJitterPowerPercent / 100.0;
    double noise = random.nextGaussian() * sigma;
    return Math.max(0, power + noise);
}
```

**Jitter mocy: GAUSSOWSKI**, σ = P·(jitter_pct/100). W przypadku bojlera 2000 W
i jitter 10% → σ = 200 W. Duże odchylenia rzadkie, ale skończone (rozkład
normalny obcięty przez `max(0, ·)` — asymetryczne odcięcie dla urządzeń
niskomocowych).

Jitter czasu (`buildActions` w `WasherSimulator`, linia 53):

```java
int actualMinute = baseMinute + (jitter > 0 ? random.nextInt(2 * jitter + 1) - jitter : 0);
```

**Jitter czasu: JEDNOSTAJNY dyskretny** w przedziale [−jitter, +jitter] minut.
Zastosowany tylko RAZ na starcie symulacji (`buildActions`/`buildTimeline`
w konstruktorze) — kolejne dni **powtarzają dokładnie ten sam przesunięty
schedule**. Nie ma losowości „per dzień".

### 1.5. Seed

`TestRunner.executeSimulation` (linia 181):

```java
long seed = testId.getMostSignificantBits() ^ testId.getLeastSignificantBits();
List<DeviceSimulator> simulators = new ArrayList<>();
for (DeviceConfig dc : config.getDevices()) {
    simulators.add(simulatorFactory.create(dc, seed + dc.getId().hashCode(), ...));
}
```

Seed pochodzi z UUID testu, per-device modyfikowany przez `id.hashCode()`. Test
jest w pełni deterministyczny wobec pary (testId, config).

**LUKA METODOLOGICZNA**: dla danego testId istnieje dokładnie jedno wykonanie.
Nie ma trybu N replikacji Monte Carlo — nie da się rozdzielić wariancji cen od
wariancji zużycia bez uruchomienia N testów z różnymi UUID.

---

## 2. Zapis pomiarów do InfluxDB

### 2.1. Format punktu

`InfluxWriter.writePower` (linie 33-40):

```java
public void writePower(UUID testId, String deviceId, double powerW, Instant timestamp) {
    Point point = Point.measurement("power")
            .addTag("test_id", testId.toString())
            .addTag("device_id", deviceId)
            .addField("power_w", powerW)
            .time(timestamp, WritePrecision.MS);
    writeApi.writePoint(config.getBucket(), config.getOrg(), point);
}
```

**Zapisywana jest moc chwilowa w W**, NIE zagregowana energia. Field ma nazwę
`power_w`, jednostki: waty. Precyzja czasu: milisekundy.

### 2.2. Kanał MQTT

`MqttPublisher.publishPower` (linia 61-73):

```java
String topic = String.format("tests/%s/devices/%s/energy", testId, deviceId);
Map<String, Object> payload = new HashMap<>();
payload.put("device_id", deviceId);
payload.put("power_w", powerW);
payload.put("timestamp_ms", timestampMs);
```

Topic: `tests/{uuid}/devices/{deviceId}/energy`. Payload JSON: `{device_id, power_w, timestamp_ms}`.

### 2.3. Interwał emisji vs to co zapisujemy

Jak wyjaśnione w §1.2, `emitEveryN = 5` → jeden punkt co 5 minut symulowanych.
Wartość zapisywana = **wynik pojedynczego wywołania `updatePower` na początku
5-minutowego okna** (a nie średnia z 5 minut). Nie ma sumowania „energii w
oknie 5-minutowym".

---

## 3. Odczyt i agregacja godzinowa

### 3.1. `InfluxQueryService.getHourlyEnergyKwh`

`backend/src/main/java/pl/smarthome/platform/influx/InfluxQueryService.java`
(linie 90-140):

```java
@Cacheable(value = "hourlyEnergy", key = "#testId")
public Map<LocalDateTime, BigDecimal> getHourlyEnergyKwh(UUID testId) {
    String flux = String.format("""
            from(bucket: "%s")
              |> range(start: -30d, stop: 365d)
              |> filter(fn: (r) => r._measurement == "power")
              |> filter(fn: (r) => r.test_id == "%s")
              |> filter(fn: (r) => r._field == "power_w")
              |> aggregateWindow(every: 1h, fn: mean, createEmpty: false)
              |> keep(columns: ["_time", "device_id", "_value"])
            """, config.getBucket(), testId);

    Map<LocalDateTime, BigDecimal> hourlyMeanPowerWatts = new TreeMap<>();
    ZoneId polandZone = ZoneId.of("Europe/Warsaw");

    for (FluxTable table : tables) {
        for (FluxRecord record : table.getRecords()) {
            ...
            double meanPowerW = number.doubleValue();
            LocalDateTime hourStart = time.atZone(polandZone)
                    .truncatedTo(ChronoUnit.HOURS).toLocalDateTime();
            hourlyMeanPowerWatts.merge(hourStart, BigDecimal.valueOf(meanPowerW), BigDecimal::add);
        }
    }

    Map<LocalDateTime, BigDecimal> hourlyKwh = new TreeMap<>();
    for (Map.Entry<LocalDateTime, BigDecimal> entry : hourlyMeanPowerWatts.entrySet()) {
        BigDecimal kwh = entry.getValue().divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP);
        hourlyKwh.put(entry.getKey(), kwh);
    }
    return hourlyKwh;
}
```

### 3.2. FAKTY do zweryfikowania

1. **Funkcja Flux: `mean`, NIE `integral()` ani `sum()`.** Wynik dla jednego
   urządzenia w jednej godzinie = arytmetyczna średnia mocy chwilowych
   (samplowanych co 5 min). Przy 5-minutowym samplingu jest ~12 próbek na godzinę.
2. **Sumowanie po urządzeniach** dzieje się w Javie (`.merge(..., BigDecimal::add)`)
   — dodajemy średnie moce urządzeń w tej samej godzinie.
3. **Konwersja jednostek**: `mean_power_W / 1000` = `mean_power_kW`, następnie
   traktowane jako `kWh`. To jest **poprawne** o ile założymy że średnia moc w
   watach razy 1 h daje energię w Wh; podzielone przez 1000 = kWh. Kod jednak
   pomija jawne mnożenie przez 1h — jest ono milcząco zawarte w tożsamości
   „średnia moc [kW] · 1h = energia [kWh]".
4. **Komentarz w kodzie (linie 82-84)** sam sygnalizuje słabość:
   > _"mean(power_w) * 1h daje energie tylko gdy dane sa gestre (np. co sekunde).
   > Dla naszego symulatora ktory emituje pomiary co ~1s to jest OK."_
   Ta uwaga jest ROZBIEŻNA z rzeczywistością: `emitEveryN = 5` (linia
   `TestRunner:169`) oznacza pomiar co 5 minut, NIE co 1 sekundę. Zapis
   `power_w` **nie jest** gęsty w sensie sekundowym, a nawet minutowym.

### 3.3. Skala aliasingu (KRYTYCZNE dla czajnika)

Czajnik ma cykl 3 min, sampling co 5 min. W ciągu godziny z jednym cyklem
czajnika (np. o 07:00-07:03) próbki są brane w minutach 0, 5, 10, ..., 55.
Prawdopodobieństwo trafienia w okno [0,3): próbka minutowa 0 → moc=2000 W;
próbki 5..55 → 0 W. Średnia z 12 próbek = 2000/12 ≈ 167 W → energia w tej
godzinie ~ 0.167 kWh. Realna energia czajnika: 2000 W · 3/60 h = 0.1 kWh.
**Błąd systematyczny ~+67%** dla tej godziny w tym przykładzie. Przy jitterze
czasu ±20 min położenie zdarzenia dryfuje między dniami, ale to konkretne
uruchomienie ma zawsze ten sam offset (jitter tylko przy starcie —
patrz §1.4), więc aliasing jest DETERMINISTYCZNY per test.

---

## 4. Formuły taryfowe

### 4.1. Katalog parametrów `TariffParams`

`backend/src/main/java/pl/smarthome/platform/tariff/TariffParams.java` (linie 87-120):

```java
BY_YEAR.put(2024, new YearParams(
        new BigDecimal("0.69"),   // G11 - cena maksymalna z mrozeniem
        new BigDecimal("0.85"),   // G12 dzien
        new BigDecimal("0.42"),   // G12 noc (mrozona)
        new BigDecimal("0.28"),   // RDN dystrybucja netto
        new BigDecimal("0.005"),  // akcyza (stala)
        new BigDecimal("0.10"),   // marza sprzedawcy netto
        new BigDecimal("1.23")    // VAT 23%
));
BY_YEAR.put(2025, new YearParams(
        new BigDecimal("0.75"), new BigDecimal("0.90"), new BigDecimal("0.48"),
        new BigDecimal("0.30"), new BigDecimal("0.005"), new BigDecimal("0.10"),
        new BigDecimal("1.23")
));
BY_YEAR.put(2026, new YearParams(
        new BigDecimal("1.10"),   // G11 - realny koszt PGE ~1.10 zl/kWh brutto
        new BigDecimal("1.25"),   // G12 dzien
        new BigDecimal("0.62"),   // G12 noc
        new BigDecimal("0.33"),   // RDN dystrybucja netto
        new BigDecimal("0.005"),  // akcyza
        new BigDecimal("0.10"),   // marza
        new BigDecimal("1.23")    // VAT
));
```

**ZGODNOŚĆ Z TEKSTEM PRACY**: dokładnie te wartości (G11=1.10, G12 day=1.25,
G12 night=0.62, narzut RDN = 0.33+0.005+0.10 = 0.435 zł/kWh netto, VAT 23%)
występują w tekście rozdziału metodologicznego.

### 4.2. Definicja godzin nocnych G12

`TariffParams.java` linie 131-134:

```java
public static final Set<Integer> G12_NIGHT_HOURS = Set.of(
        22, 23, 0, 1, 2, 3, 4, 5,  // 22-06
        13, 14                       // 13-15
);
```

Łącznie 10 godzin taniej doby (22-06 = 8h, 13-15 = 2h). Zgodne ze standardem
PGE/Tauron/Energa.

### 4.3. Formuła RDN

`TariffParams.rdnFinalPrice` (linie 174-181):

```java
public static BigDecimal rdnFinalPrice(int year, BigDecimal wholesalePlnKwh) {
    YearParams p = forYear(year);
    BigDecimal netTotal = wholesalePlnKwh
            .add(p.rdnDistributionNet())
            .add(p.rdnExciseNet())
            .add(p.rdnMarginNet());
    return netTotal.multiply(p.vatMultiplier());
}
```

`P_RDN(h) = (cena_hurtowa_PSE(h) + dystrybucja + akcyza + marża) · 1.23`.

Cena hurtowa pochodzi z PSE API (`PseApiClient.aggregateQuartersToHours`) —
PSE zwraca **96 kwadransów** dziennie, agregowanych przez
`ArithmeticMean` do 24 godzinowych cen (linie 106-115 w `PseApiClient.java`).
Konwersja MWh→kWh w `CostCalculatorService.loadRdnPricesForRange` (linie 218-220):
```java
BigDecimal pricePlnKwh = e.getPricePlnMwh().divide(MWH_TO_KWH, 6, RoundingMode.HALF_UP);
```

---

## 5. Wyliczenie profilu dobowego E_h

### 5.1. W skrypcie Python

`analiza/analiza_wyniki.py` (linie 198-212):

```python
def build_daily_profile(hourly_breakdown: List[dict]) -> np.ndarray:
    """
    Z listy 720 punktów hourly -> profil dobowy (24 wartości kWh).
    Uśredniamy zużycie w każdej godzinie doby po 30 dniach symulacji.
    """
    buckets = defaultdict(list)  # hour_of_day -> lista kWh
    for h in hourly_breakdown:
        hour_of_day = datetime.fromisoformat(h["hour"]).hour
        buckets[hour_of_day].append(float(h["kwh"]))
    profile = np.zeros(24)
    for h in range(24):
        vals = buckets.get(h, [0.0])
        profile[h] = float(np.mean(vals))
    return profile
```

**Wprost**: E_h = średnia arytmetyczna z 30 wartości `kwh` (jedna wartość na
dzień) dla każdej godziny doby. Nie jest brany żaden pomiar surowy z Influx;
wejściem są już **zagregowane godzinowe kWh** zwracane przez backend endpoint
`GET /tests/{id}/costs` → `hourlyBreakdown` (patrz §3, `getHourlyEnergyKwh`).

### 5.2. Analogicznie w backendzie

`CostCalculatorService.extractHourlyProfile` (linie 442-465, cytowane w §6.1):
identyczna operacja — sums[h] += kwh; profile[h] = sums[h] / counts[h].

---

## 6. Wyliczenie kosztów miesięcznych

### 6.1. Projekcja `calculateProjectedCosts` (backend) — używana m.in. w bootstrapie

`CostCalculatorService.java` (linie 299-434). Kluczowy fragment (linie 337-358):

```java
for (int h = 0; h < 24; h++) {
    BigDecimal kwh = hourlyProfileKwh[h];      // <-- E_h uśrednione po dniach
    dayKwh = dayKwh.add(kwh);

    dayG11 = dayG11.add(kwh.multiply(g11UnitPrice));
    BigDecimal g12Price = TariffParams.g12PriceForHour(midYear, h);
    dayG12 = dayG12.add(kwh.multiply(g12Price));

    BigDecimal wholesale = rdnPrices.get(new PriceKey(cursor, h));
    if (wholesale != null) {
        BigDecimal rdnPrice = TariffParams.rdnFinalPrice(midYear, wholesale);
        dayRdn = dayRdn.add(kwh.multiply(rdnPrice));
    } else {
        dayRdn = dayRdn.add(kwh.multiply(g11UnitPrice));
    }
}
```

`hourlyProfileKwh[h]` pochodzi z `extractHourlyProfile` — czyli jest to E_h
UŚREDNIONE po dniach testu. Ten sam wektor 24-elementowy jest używany dla
**każdego dnia** okresu projekcji.

### 6.2. Skrypt Python `recalculate_costs`

`analiza/analiza_wyniki.py` (linie 248-294):

```python
def recalculate_costs(daily_profile_kwh, prices_by_day):
    daily_costs_rdn: List[float] = []
    total_g11 = total_g12 = total_rdn = 0.0
    for day, hour_prices in sorted(prices_by_day.items()):
        day_rdn = day_g11 = day_g12 = 0.0
        for h in range(24):
            kwh = float(daily_profile_kwh[h])            # <-- E_h dla każdego dnia
            price_rdn = hour_prices.get(h, 0.0)
            day_rdn += kwh * price_rdn
            day_g11 += kwh * G11_PLN_KWH
            day_g12 += kwh * g12_price_for_hour(h)
        daily_costs_rdn.append(day_rdn)
        total_rdn += day_rdn
        total_g11 += day_g11
        total_g12 += day_g12
```

**POTWIERDZENIE**: skrypt Python KORZYSTA Z UŚREDNIONEGO E_h (24-elementowego
wektora `daily_profile_kwh`) i pomnaża go przez ceny każdego dnia okresu.
NIGDZIE nie używa e(d,h) surowych — konsumpcja dla każdego z 30 dni jest
IDENTYCZNA, różnią się tylko ceny.

### 6.3. Formuły

- `K_G11 = Σ_d Σ_h E_h · c_G11`  (`c_G11` stała 1.10 zł/kWh dla 2026)
- `K_G12 = Σ_d Σ_h E_h · c_G12(h)`  (c_G12 zależy od pory doby)
- `K_RDN = Σ_d Σ_h E_h · c_RDN(d,h)`  (c_RDN zależy od dnia i godziny)

**IMPLIKACJA dla §3.5 CVaR w tekście pracy**: problem opisany w recenzji
(uśrednianie zużycia niszczy wariancję konsumpcji) JEST realny w tym
skrypcie. Wariancja `daily_costs_rdn` pochodzi wyłącznie z wariancji cen
RDN dzień-po-dniu, nie z wariancji zachowania mieszkańców.

### 6.4. Kontrast: `calculateForTest` (backend, /costs endpoint) używa e(d,h)

`CostCalculatorService.calculateForTest` (linie 103-137, cytowane w §1) iteruje po
mapie `hourlyKwh: Map<LocalDateTime, BigDecimal>` gdzie klucze to konkretne
_godziny w konkretnych dniach_ (30d × 24h = 720 wpisów). Tutaj `e(d,h)` NIE
jest uśrednione. Zatem endpoint `/tests/{id}/costs` zwraca koszty policzone
na **surowych** e(d,h), ale skrypt analizy magisterki i tak potem robi
`build_daily_profile` (uśrednia) i przelicza z historycznymi cenami RDN
metodą z §6.2.

**Wniosek**: ostateczne tabele CSV/MD/wykresy w `wyniki_analizy/` opierają
się na E_h (uśrednione), NIE na e(d,h).

---

## 7. Wyliczenie VaR i CVaR

### 7.1. W skrypcie Python (używane do rozdziału 7)

`analiza/analiza_wyniki.py` (linie 276-284):

```python
if daily_costs_rdn:
    var_95 = float(np.percentile(daily_costs_rdn, 95))
    tail = [c for c in daily_costs_rdn if c >= var_95]
    cvar_95 = float(np.mean(tail)) if tail else var_95
else:
    var_95 = 0.0
    cvar_95 = 0.0
```

- **Rozmiar próby**: `len(daily_costs_rdn) = 30` (30 dni sezonu).
- **VaR**: `np.percentile(x, 95)` — domyślnie metoda `linear` (interpolacja).
  Dla n=30, indeks docelowy = 0.95·(30−1) = 27.55, czyli VaR = liniowa
  interpolacja między 27. i 28. wartością sortowaną.
- **CVaR**: średnia z wszystkich wartości ≥ VaR. Dla n=30 zazwyczaj `tail`
  będzie mieć 1-2 elementy (28. i 29. w sortowanym). Małe n → duża wariancja
  estymatora CVaR.

### 7.2. W backendzie (endpoint `/costs/projected`)

`CostCalculatorService.calculateProjectedCosts` (linie 386-399):

```java
int varIndex = rdnDaily.isEmpty() ? 0
        : Math.min(rdnDaily.size() - 1, (int) (rdnDaily.size() * 0.95));
BigDecimal rdnVaR = rdnDaily.isEmpty() ? BigDecimal.ZERO : rdnDaily.get(varIndex);

BigDecimal rdnCVaR;
if (rdnDaily.isEmpty()) {
    rdnCVaR = BigDecimal.ZERO;
} else {
    List<BigDecimal> tail = rdnDaily.subList(varIndex, rdnDaily.size());
    BigDecimal sum = tail.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    rdnCVaR = sum.divide(BigDecimal.valueOf(tail.size()), 2, RoundingMode.HALF_UP);
}
```

- **VaR (backend): nearest-rank z odcięciem podłogowym**. `(int)(30 · 0.95) = 28`.
  VaR = element o indeksie 28 w sortowanej rosnąco liście → 29. wartość
  (indeksy 0..29). Tail = subList(28, 30) → 2 wartości (indeks 28 i 29).
  CVaR = średnia z dwóch najgorszych dni.
- **NIE-ZGODNOŚĆ**: dwie różne metody obliczania VaR (linearna w Pythonie vs
  nearest-rank w backendzie). Dla n=30 może dawać różnice do ~1 wartości
  między środkami.

### 7.3. Skąd biorą się dzienne C_d

W obu przypadkach C_d = koszt dnia policzony na **E_h** (uśrednionym), a nie
e(d,h). Zatem C_d = Σ_h E_h · c(d,h) — jedyne źródło zmienności to c(d,h).

---

## 8. Wyliczenie oszczędności δ_RDN/G11

### 8.1. Per-przypadek

`analiza_wyniki.py` linie 352-353 (wewnątrz pętli po parach profil × sezon):

```python
saving_vs_g11 = ((sc.cost_g11 - sc.cost_rdn) / sc.cost_g11 * 100) if sc.cost_g11 > 0 else 0
saving_vs_g12 = ((sc.cost_g12 - sc.cost_rdn) / sc.cost_g12 * 100) if sc.cost_g12 > 0 else 0
```

Więc każdy z 24 (albo 96 w trybie cross-season) wpisów niesie ze sobą własny
procent oszczędności, policzony na podstawie sum G11 i RDN JEGO przypadku.

### 8.2. Agregacja globalna — średnia PROCENTÓW

`zapisz_wnioski` (linia 627):

```python
avg = diag["oszczednosc_RDN_vs_G11_pct"].mean()
```

To jest **arytmetyczna średnia procentów** oszczędności — 24 wartości procentów
z „diagonalnych" par (profil × ten sam sezon dla harmonogramu i cen).

Wykres 6 (`wykres_6_ranking_sezonow`, linia 512):

```python
ranking = diag.groupby("sezon_ceny")["oszczednosc_RDN_vs_G11_pct"].mean().reindex(SEASON_ORDER)
```

Wykres 2 (`wykres_2_ranking_profilow`, linia 418):

```python
ranking = diag.groupby("profil_kod")["oszczednosc_RDN_vs_G11_pct"].mean().sort_values(...)
```

**POTWIERDZENIE efektu Simpsona (§1.1 recenzji)**: WSZYSTKIE agregaty
przedstawione w rozdziale Wyniki (H1..H5, wykres 2 rankingu profili,
wykres 6 rankingu sezonów, tabela zbiorcza) używają arytmetycznej średniej z
procentów. Nie ma nigdzie liczenia „ważonej" formy `((ΣG11 − ΣRDN) / ΣG11) · 100`
po profilach ani po sezonach.

Efekt: małe gospodarstwo (D senior) z małą kwotą wpływa na średnią procent
tak samo, jak duże gospodarstwo (C rodzina). Kwotowo ich udział w oszczędności
byłby proporcjonalny do konsumpcji.

---

## 9. Profile gospodarstw — duty cycle bojlera

Z plików `archetypes/base/*.json`:

| Kod | Profil | boiler power_w | boiler duty_cycle | boiler cycle_length | Osób |
|---|---|---|---|---|---|
| A | Singiel-biuro | 2000 | **0.10** | 60 min | 1 |
| B | Remote worker | 2000 | **0.12** | 60 min | 1 |
| C | Rodzina 2+2 | 2500 | **0.25** | 60 min | 4 |
| D | Senior samotny | 2000 | **0.08** | 60 min | 1 |
| E | Studenci | 2000 | **0.12** | 60 min | 2 |
| F | Para DINK | 2500 | **0.15** | 60 min | 2 |

Modyfikator zimowy dodaje ±0.15 do duty (górna granica 0.35),
jesienny ±0.05 (górna granica 0.22) — `archetypes/generate_seasonal.py`
linie 36-38, 141-144.

**Wniosek dla recenzji (§ „boiler jednakowy dla 1- i 4-osobowego")**:
duty cycle bojlera JEST zróżnicowany między profilami (0.08 dla seniora vs
0.25 dla rodziny 2+2). Model reaguje na liczbę osób. **JEDNAK**:
- moc bojlera dla singla to 2000 W, dla rodziny 4-osobowej 2500 W — proporcja
  1:1.25, gdy realne zużycie CWU rośnie ~1:3-4 wraz z liczbą osób;
- w efekcie sumaryczne dzienne kWh bojlera: A = 2000 · 0.10 · 24 = 4.8 kWh,
  C = 2500 · 0.25 · 24 = 15.0 kWh. Ratio ~3.1× — akurat w rozsądnym zakresie.
- **KLUCZOWA UWAGA**: bojler pracuje _bez powiązania z rzeczywistymi rozbiorami_
  (patrz §1.3 — deterministyczny prostokąt 10 min ON / 50 min OFF, powtarzany
  co godzinę, przez całą dobę i wszystkie 30 dni). Nie ma pików wieczornych
  po prysznicu, nie ma czasu docelowego rozgrzewania po użyciu wody.

Pozostałe urządzenia w plikach potwierdzają realistyczne zróżnicowanie
schedule'i (E studenci mają aktywność do 02:00, D senior do 22:30, C rodzina
ma dwie kolejki 06:00-08:00 i 15:30-22:30 itd.). Kompletne listy urządzeń w
`archetypes/base/{A..F}_*.json`.

---

## 10. Znane problemy i luki metodologiczne

### 10.1. Aliasing samplingowy (§3.3)

`emitEveryN = 5` w `TestRunner:170`, `mean` w Fluxie (§3.1). Krótkie
urządzenia (kettle 3 min < 5 min) mogą być mierzone błędnie o rzędy wielkości
w konkretnej godzinie. Bilans dobowy statystycznie się „prawie zamyka" (bo
błąd zależy od fazy jitter'a), ale rozkład godzinowy jest zniekształcony.
Ma to wpływ na porównanie G12 (zależy od godziny) i RDN (godzinowa cena).

**Prawidłowe rozwiązanie**: albo (a) w symulatorze zwracać energię zsumowaną
w oknie 5-minutowym (`updateEnergyKWh(minute, N=5)`), albo (b) w Influx użyć
`integral(unit: 1h)` zamiast `mean`. Żadne z tych nie jest zaimplementowane.

### 10.2. Deterministyczne zużycie (§6)

Wszystkie analizy w rozdziale 7 pracy używają E_h uśrednionego po dniach,
nie e(d,h). CVaR liczony na 30 wartościach C_d, gdzie każde C_d = Σ E_h · c(d,h)
— jedyna wariancja pochodzi z c(d,h). To niszczy interpretację CVaR jako
„ryzyko dnia drogiego dla użytkownika" — pomija ryzyko „dnia z awarią bojlera"
albo „dnia z długim praniem".

### 10.3. Uśrednianie procentów zamiast kwot (§8.2)

`diag["oszczednosc_..._pct"].mean()` w miejscu, gdzie prawidłowo powinno być
`(sum(K_G11) − sum(K_RDN)) / sum(K_G11)`. Efekt Simpsona: nadreprezentacja
małych gospodarstw w globalnych średnich.

### 10.4. Dwie różne definicje VaR (§7)

Python: `np.percentile(x, 95)` interpolacja liniowa. Java:
`x[floor(0.95·n)]` nearest-rank. Wyniki rozjeżdżają się przy małym n.
Ponieważ rozdział 7 opiera się na danych Python, warto ten dwuinterfejsowy
konflikt zauważyć.

### 10.5. Fallback RDN → G11 przy brakujących cenach

`CostCalculatorService.java` linie 125-132: gdy PSE nie ma ceny na daną godzinę,
`rdnCost = kwh · G11`. Liczba brakujących godzin jest raportowana
(`missingRdnHours`), ale w skrypcie Python `group_prices_by_day` całkowicie
pomija godziny bez ceny (`hour_prices.get(h, 0.0)` → cena=0 → koszt=0).
**Rozjazd między backendem a Pythonem**: backend zawyża RDN przez fallback,
Python zaniża RDN przez ustawienie ceny=0. Skala zależy od pokrycia PSE, ale
poza kontrolą walidacji.

### 10.6. Jitter tylko na starcie (§1.4)

`buildActions` / `buildTimeline` wykonują się raz w konstruktorze. Wszystkie
30 dni ma identyczny (przesunięty) schedule. Rzeczywista wariancja
dzień-do-dnia nie istnieje. Jedynym „prawdziwie" losowym elementem między
dniami jest gaussowski jitter mocy oraz burst intervals w ComputerSimulator.

### 10.7. Bojler bez sprzężenia z użyciem wody

Bojler jest tak samo aktywny o 3:00 nad ranem jak o 20:00 po prysznicu.
Skoro G12 tanie godziny obejmują 22-06, deterministyczny prostokąt bojlera
akurat _sprzyja_ G12 (znaczna część zużycia w oknie taniej strefy). To
podnosi atrakcyjność G12 w wynikach względem realnego przypadku, gdzie
grzałka aktywuje się po termostacie po użyciu wody wieczorem.

### 10.8. Brak niepewności estymatora CVaR

n=30 daje CVaR (Python) z ogona 1-2 wartości — estymator o gigantycznym
błędzie próbkowania. W rozdziale 7 nie ma bootstrapowanego CI dla CVaR.

### 10.9. `mean` vs `sum` przy pustych oknach

`aggregateWindow(every: 1h, fn: mean, createEmpty: false)` — jeśli w danej
godzinie NIE MA ŻADNYCH próbek dla urządzenia, godzina jest pomijana. Suma
po urządzeniach nie „doda zera" dla brakującego urządzenia; efektywnie
zakładamy że urządzenie było wyłączone. Zgodne z rzeczywistością, ale wart
odnotowania.

### 10.10. Punkt czasowy próbki

`TestRunner:229` przypisuje `simTimeMs = simTimeStart + minute · 60_000` — czyli
znacznik jest na _początku_ 5-minutowego okna. Przy `aggregateWindow` Flux
domyślnie używa `right`-labelu (znacznik na końcu okna). To może przesunąć
próbki graniczne godziny o jedno okno — subtelnie, ale przy naprawdę
gęstych zdarzeniach może wpłynąć na przypisanie kWh do godziny.

---

## Podsumowanie techniczne dla recenzenta

Kod jest wewnętrznie spójny, dobrze udokumentowany, formuły taryfowe w
`TariffParams.java` dokładnie odpowiadają wartościom cytowanym w rozdziale
metodologicznym (G11=1.10 zł/kWh 2026, G12=1.25/0.62, narzut RDN
0.435 netto + VAT 23%, godziny nocne G12 = {22,23,0..5,13,14}).

Trzy kluczowe źródła krytyki, które w kodzie są potwierdzone _in-situ_:

1. **Aliasing 5-minutowego samplingu** vs krótsze urządzenia (kettle 3 min).
2. **Uśrednianie E_h przed liczeniem C_d** — pozbawia analizę CVaR wariancji
   konsumpcyjnej; „ryzyko" mierzy tylko wariancję cen RDN.
3. **Uśrednianie procentów oszczędności** zamiast liczenia z sum kwot —
   efekt Simpsona w agregatach ogólnych.

Dodatkowo warto zauważyć **rozjazdy** między backendem a skryptem Python:
inna definicja VaR (linearna vs nearest-rank), inne zachowanie przy braku
ceny RDN (fallback G11 vs zerowa cena), inna ścieżka obliczeń kosztowych
(surowe e(d,h) w `/costs`, ale skrypt i tak buduje uśrednione E_h).

Ostatecznie: metodologia „bazowa" (formuły taryfowe, definicje) — spójna i
poprawna. Metodologia „analityczna" (obliczenie CVaR i agregatów oszczędności)
— zawiera wskazane systematyczne błędy, które warto adresować w tekście
pracy (albo naprawić w kodzie i ponownie wygenerować wyniki).
