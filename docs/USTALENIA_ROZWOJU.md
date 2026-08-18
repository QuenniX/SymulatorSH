# Ustalenia rozwojowe -- iteracja po spotkaniu z promotorem

Dokument spisuje **decyzje kierunkowe** dla kolejnego etapu prac nad platformą SymulatorSH. Nie jest to plan implementacji ani harmonogram -- służy jako punkt odniesienia, żeby uniknąć powrotu do dyskusji na już zamknięte tematy.

## 1. Kontekst i reset

Wracamy do rozwoju od zera. **Jawnie porzucamy** rzeczy z wcześniejszej iteracji, których teraz nie robimy:

- Metryka kosztu w PLN (taryfa G12).
- Metryka komfortu użytkownika.
- Reguły biznesowe typu "if-else" jako element badawczy.
- Silnik ML.
- Model baterii i fotowoltaiki.

Nie znaczy, że nigdy do nich nie wrócimy. Znaczy, że **w tej iteracji ich nie ma**, żeby nie mieszać kontekstu.

## 2. Cel iteracji

Trzy priorytety wskazane przez promotora:

1. **Więcej testów w jednym momencie** -- batch experiments.
2. **Ładna, rozbudowana wizualizacja mieszkania** -- pokoje, urządzenia, animacje, "sprzedaż wizualna".
3. **Wygodny konfigurator urządzeń w UI** -- zamiast klejenia JSON-a.

Wszystko trzy razem robi z platformy **narzędzie dla inżyniera**, o którym mówił promotor.

## 3. Nowe urządzenia -- lista finalna

Rozszerzamy paletę z 4 do **12 urządzeń** (Tier A + Tier B).

### Tier A -- must-have (5 nowych)

| Typ | Domyślna moc | Profil zachowania | Pokój |
|-----|--------------|---------------------|-------|
| `TV` | 120 W | Stała moc na ON/OFF (jak LIGHT) | Salon |
| `OVEN` (piekarnik) | 2500 W | Cykl grzania z pulsem (grzałka on/off co kilka minut) | Kuchnia |
| `DISHWASHER` (zmywarka) | 1800 W | Event-driven cykl z fazami (nagrzewanie / mycie / suszenie) | Kuchnia |
| `AC` (klimatyzacja) | 1000 W | Duty cycle (jak lodówka, utrzymuje temperaturę) | Salon |
| `BOILER` (podgrzewacz wody) | 2000 W | Duty cycle utrzymujący temperaturę wody | Łazienka |

### Tier B -- nice-to-have (3 nowe)

| Typ | Domyślna moc | Profil zachowania | Pokój |
|-----|--------------|---------------------|-------|
| `KETTLE` (czajnik) | 2000 W | Krótki ostry pik (3 min) | Kuchnia |
| `COMPUTER` (komputer) | 100-300 W | Baseload + skoki (zmienna moc) | Sypialnia |
| `ROUTER` | 15 W | Baseload 24/7 (zawsze ON) | Salon |

### Istniejące (4)

`LIGHT`, `REFRIGERATOR`, `WASHER`, `HEATER` -- bez zmian mechanicznych, dorabiamy jedynie ich modele 3D i przypisanie do pokojów.

### Tier C -- odłożone (na potem, po zakończeniu Tieru A+B)

`MICROWAVE`, `HAIR_DRYER`, `IRON`, `VACUUM_ROBOT`, `ELECTRIC_CAR_CHARGER`.

## 4. Struktura mieszkania

Startowy layout referencyjny -- **jedno predefiniowane mieszkanie 60m² z 5 pomieszczeniami**. Wielo-layoutowość dokładamy potem.

| Pomieszczenie | Urządzenia |
|---------------|------------|
| **Kuchnia** | REFRIGERATOR, OVEN, DISHWASHER, KETTLE, LIGHT (kuchni) |
| **Salon** | TV, AC, HEATER (salonu), ROUTER, LIGHT (salonu) |
| **Sypialnia** | COMPUTER, LIGHT (sypialni) |
| **Łazienka** | WASHER, BOILER, LIGHT (łazienki) |
| **Przedpokój** | LIGHT (przedpokoju) |

Razem: **12 urządzeń** (8 typów + 4 światła w różnych pokojach).

W konfiguracji JSON każde urządzenie dostaje pole `"room"`. Przyszłościowo `room` może mieć własne parametry (powierzchnia, orientacja, izolacja -- pod Kierunek 1 badawczy).

## 5. Wizualizacja 3D

### Wybór techniczny

- **Three.js** jako silnik WebGL.
- **@react-three/fiber** jako wrapper na Three.js dla Reacta.
- **@react-three/drei** dla gotowych komponentów pomocniczych (OrbitControls, Sky, itd.).
- Modele urządzeń i mebli -- **proceduralne, generowane w kodzie z prymitywów Three.js**. Bez pobierania modeli zewnętrznych.
- Styl wizualny -- **low-poly cukierkowy** (spójny, geometryczny, jak Monument Valley / Two Dots).

### Poziom ambicji

**Wariant B (średni)** -- z trzech rozpatrywanych opcji:

- Meble i urządzenia jako proste bryły + kolor.
- Ściany obniżone do ~1.5m dla widoku z góry.
- Kamera obracalna (OrbitControls).
- Realne oświetlenie z bloomem dla żarówek.
- Cienie w czasie rzeczywistym.
- Skybox z dobowym cyklem dnia/nocy (synchronizowany z zegarem symulacji).
- Kliknięcie w urządzenie → panel z aktualnym stanem.

### Kluczowe decyzje UX

- **Osobne modele proceduralne dla każdego urządzenia** (nie tylko wariant kolorystyczny). Dla wow-effectu.
- **Tryb real-time podczas symulacji** + **tryb playback po zakończeniu** z timeline scrubberem.
- **Widoczność ścian** -- top-down bez ścian albo obcięte na 1.5m (do rozstrzygnięcia w implementacji).
- **Rendering wyłącznie na żądanie** -- podczas batch experiments (100 testów) nie renderujemy 3D dla każdego, tylko dla wybranego.

### Rozwiązanie kompatybilności

- WebGL renderuje się w przeglądarce użytkownika, nie na serwerze -- **serwer AWS bez GPU jest wystarczający**.
- Fallback na płaską SVG-ową wizualizację dla środowisk bez WebGL (opcjonalnie, later).

## 6. Podejście do danych symulacji

**Realistyczne cykle w bazie** -- piekarnik pracuje impulsowo (grzałka on/off co kilka minut), lodówka duty cycle 15/22, zmywarka fazy nagrzewanie/mycie/suszenie. **Nie idealizujemy**.

**Przełącznik "Wygładź" na wykresie** -- opcja UI (moving average w Rechartach):

- **Surowe dane** (domyślnie) -- widać wszystkie skoki mocy.
- **Wygładzone** -- do ładnych screenshotów i podglądowego przeglądu.

Dane w InfluxDB pozostają zawsze surowe. Wygładzanie jest **tylko po stronie wyświetlania**.

## 7. Konfigurator urządzeń w UI (bez JSON-a)

Zastępujemy edytor JSON-a **kreatorem 4-etapowym**:

1. **Etap 1 -- Budynek**: wybór layoutu mieszkania (referencyjny 60m² + w przyszłości wybór z listy).
2. **Etap 2 -- Urządzenia**: drag & drop z palety do pokojów, kliknięcie → parametry.
3. **Etap 3 -- Harmonogramy**: wizualna oś czasu doby, rysowanie okresów ON/OFF.
4. **Etap 4 -- Parametry testu**: `durationDays`, `speedFactor`, `jitter` + podgląd wygenerowanego JSON-a.

**Bonus:** szablony konfiguracji zapisywane w bazie ("Zapisz jako szablon" / lista wyboru).

### Wybór technologiczny

- **dnd-kit** dla drag & drop.
- **react-hook-form** dla walidacji formularzy.
- SVG dla wizualnego edytora harmonogramu doby.

### Kompatybilność wsteczna

Endpoint `POST /api/v1/tests` **zostaje** i przyjmuje JSON. Klasyczne API działa nadal (Swagger, skrypty). Kreator jest **wygodną nakładką**, nie zastępstwem.

## 8. Batch experiments

Nowy endpoint `POST /api/v1/experiments` z jednym z trybów:

- **Prosta lista N konfiguracji** (klasyk).
- **Macierz parametryczna** -- template + zakresy zmiennych, backend generuje iloczyn kartezjański.
- **Powtórzenia z różnymi seedami** (`"repetitions": 10`) -- ta sama konfiguracja, wiele testów, analiza wariancji.

Nowe modele bazodanowe: `Experiment` (rodzic) + `ExperimentRun` (dziecko, 1:N).

Frontend -- strona **"Eksperymenty"**:

- Formularz budowy macierzy.
- Progres bar per eksperyment.
- Tabela porównawcza po zakończeniu.
- Wykresy porównawcze (box-plot, scatter).

Executor -- zwiększenie puli wątków (z 3 do np. 5-10), backend przechodzi przez zestaw sekwencyjnie.

## 9. Zależności technologiczne do dołożenia

| Biblioteka | Cel |
|------------|-----|
| `three` | Silnik WebGL |
| `@react-three/fiber` | React wrapper na Three.js |
| `@react-three/drei` | Komponenty pomocnicze (OrbitControls, Sky, PointLight) |
| `@dnd-kit/core` | Drag & drop w konfiguratorze |
| `react-hook-form` | Walidacja formularzy w konfiguratorze |

Backend -- bez nowych zależności (Spring już wszystko ma).

## 10. Kolejność implementacji (wysoki poziom)

Faktyczne kamienie milowe do rozpisania osobno, ale kolejność logiczna:

1. **Konfigurator urządzeń + koncepcja `Room` w modelu.**
   Bez tego reszta nie ma podstawy.
2. **Nowe 8 typów urządzeń.**
   Każdy: klasa symulatora + wpis w fabryce + wpis w DeviceTypeService + parametryzacja.
3. **Wizualizacja 3D mieszkania (wariant B).**
   Ściany, meble, urządzenia, animacje stanu, oświetlenie, skybox.
4. **Batch experiments -- endpoint + strona "Eksperymenty".**
5. **Playback + timeline scrubber, przełącznik "Wygładź", dodatki wow.**
6. **Rozstrzygnięcie kierunku badawczego** (osobna dyskusja).

## 11. Ograniczenia iteracji -- świadome pominięcia

Co **nie** wchodzi w zakres tej iteracji:

- Metryki kosztu (PLN, taryfa G12).
- Metryki komfortu użytkownika.
- Model termiczny budynku (RC).
- Integracja z API pogodowym.
- Analiza wrażliwości Sobola.
- ML / uczenie maszynowe.
- Bateria, fotowoltaika.
- Wielo-mieszkaniowe layouty (na razie jeden referencyjny 60m²).
- Uwierzytelnianie / role użytkowników.
- CI/CD (pozostaje deploy ręczny).

Wszystko powyższe **może wrócić** w kolejnych iteracjach -- jest na liście, ale nie w tej.

## 12. Otwarte kwestie do rozmowy

Poniższe wymagają jeszcze wspólnej decyzji przed rozpoczęciem implementacji lub w jej trakcie:

- **Konkretny kierunek badawczy pracy magisterskiej** -- Kierunek 1 (model termiczny + Sobol) jest jedną z propozycji, do ostatecznego wyboru z promotorem.
- **Widok ścian w wizualizacji 3D** -- top-down bez ścian vs. obcięte na 1.5m vs. przezroczyste.
- **Endpoint POST /api/v1/experiments** -- czy dopuszczamy wszystkie trzy tryby na start, czy tylko listę konfiguracji.
- **Domena + DNS + HTTPS** -- do zdecydowania w związku z GitHub Student Pack.

## 13. Punkty odniesienia z obecnego stanu projektu

Rzeczy, których nie zmieniamy, ale które trzeba mieć w pamięci:

- Backend Spring Boot 3.3 + Java 21 + Maven.
- PostgreSQL na Neon Cloud (metadane).
- InfluxDB Cloud (pomiary).
- Mosquitto broker MQTT (w kontenerze na EC2).
- Frontend React 18 + Vite + TypeScript + Tailwind + Recharts.
- Deployment: 4 kontenery Docker na EC2 t3.small.
- Caddy jako reverse proxy.
- Konwencja JSON w API: **camelCase** (a nie snake_case z docs -- rozbieżność do naprawienia w oddzielnym zadaniu przez `spring.jackson.property-naming-strategy: SNAKE_CASE`).

---

*Dokument aktualizowany w miarę uzgadniania kolejnych detali. Przed rozpoczęciem większej zmiany -- konsultować z tym plikiem.*
