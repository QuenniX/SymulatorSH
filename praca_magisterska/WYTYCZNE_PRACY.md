# Wytyczne pracy magisterskiej — kompas przy pisaniu

Plik roboczy. Trzymam wszystkie ustalenia w jednym miejscu żeby przy każdej sesji
pisania mieć kontekst pod ręką.

---

## 1. Dane podstawowe

| Pole | Wartość |
|---|---|
| Autor | Igor Guła |
| Nr albumu | 169784 (EF-169784) |
| Tytuł PL | Model systemu Smart Home |
| Tytuł EN | Smart Home System Model (lub Smart Home Architecture) |
| Promotor | dr inż. Michał Markiewicz |
| Wydział | WEiI Politechnika Rzeszowska |
| Rodzaj | Magisterska (rodzajPracyNo = 2) |
| Rok obrony | 2026 |

---

## 2. Wymagania promotora — długość

**Całość: MAKS 60 stron.** Nie na siłę — lepiej 50-55.

| Rozdział | Docelowa długość |
|---|---|
| Wstęp | 1-2 strony |
| Część teoretyczna — technologia | ~5 stron (nie na siłę) |
| Wzory i metodologia obliczeń | maks 7 stron |
| Opis aplikacji + rysunek architektoniczny | (część praktyczna) |
| Metodyka testów i badań + analiza | (część praktyczna) |
| Zakończenie / podsumowanie | ~1,5 strony |

---

## 3. Wymagania promotora — struktura

### Wstęp (max 2 strony)
- Zagajenie do tematu (~pół strony)
- Cel pracy i zadania które zostaną wykonane
- Struktura pracy (streszczenie każdego rozdziału)

### Część teoretyczna
- Technologia (~5 stron, nie na siłę)
- Wzory jak się liczy (max 7 stron)
- Odwołanie do literatury (cytowania w tekście)

### Część praktyczna
- Opis aplikacji
- Jak po kolei tworze kolejne testy
- Rysunek architektoniczny
- Badania które wykonałem + analiza

### Zakończenie (max 1,5 strony)
- Cel pracy został zrealizowany
- Wykonane zadania (autor uważa za wkład własny)
- Możliwe dalsze kierunki rozwoju
- Wartość potencjalna zastosowania
- Wnioski
- **OBOWIĄZKOWO:** *"Autor za własny wkład pracy uważa: ..."*

---

## 4. Struktura rozdziałów którą ustaliliśmy

| # | Rozdział | Strony | Status |
|---|---|---|---|
| 1 | Wstęp | 2 | do napisania |
| 2 | Podstawy teoretyczne | 5 | do napisania |
| 3 | Metodologia obliczeń (wzory) | 5-7 | do napisania |
| 4 | Architektura systemu SymulatorSH | 5-6 | do napisania |
| 5 | Implementacja aplikacji | 8-10 | do napisania |
| 6 | Metodyka badań | 3-4 | do napisania |
| 7 | Wyniki badań i analiza | 6-7 | **GOTOWE** ✅ |
| 8 | Podsumowanie i wnioski | 1,5 | do napisania |
| — | Załączniki, bibliografia, streszczenie | — | do napisania |

**Razem: ~40-45 stron treści + strony formalne = ~55-60 stron.**

**Uwaga:** tytuł "Model systemu Smart Home" kładzie akcent na PLATFORMĘ, nie samą
analizę taryf. Więcej strony na rozdz. 4-5 (architektura + implementacja),
mniej na sam wynik analizy (rozdz. 7).

---

## 5. Styl pisania

### Forma
- **Bezosobowa w czasie przeszłym.**
  - TAK: "Przeprowadzono symulację...", "Uzyskano wyniki...", "Wykonano rekalkulację..."
  - NIE: "Widzimy że...", "Zrobiłem...", "Możemy zauważyć..."

- **Akademicka, spokojna forma.** Ma nie "krzyczeć że napisana przez AI".
  - Unikać: "kluczowa obserwacja", "warto zauważyć", "istotne jest", "co ciekawe",
    "zaskakujące że", nadmiernych pogrubień
  - Używać: proste zdania, konkretne liczby, odwołania do wykresów/tabel, wnioski
    wyprowadzane z danych bez dramatyzmu

- **Bez list bullet w prozie** — chyba że to lista wyliczeniowa (metody, założenia,
  etapy). W ciągłym opisie: normalny akapit.

- **Bez emoji** w tekście pracy.

### Konwencje

- **Separator dziesiętny to przecinek** (0,75 zł a nie 0.75 zł). Dotyczy też
  procentów (12,7% a nie 12.7%).

- **Odwołania Latexowe** przez `\ref{}` — nie ręcznie ("rysunek 2.1"), tylko
  "rysunek~\ref{Fig:heatmap-diagonal}".

- **Labele mają prefiksy:**
  - `Sec:xxx` — rozdziały
  - `Subsec:xxx` — podrozdziały
  - `Fig:xxx` — rysunki
  - `Tab:xxx` — tabele
  - `Eq:xxx` — wzory

- **Twarda spacja** (`~`) między liczbą a jednostką: `30~dni`, `1300~zł/MWh`,
  `5~kWh`.

- **Cytowania** przez `\cite{key}` na końcu akapitu który zaczerpnięto z literatury.

---

## 6. Wykresy i rysunki

### GOTOWE (wygenerowane w `analiza_wyniki.py`, folder `figures/`)

- `wykres_1_heatmap.png` — Macierz oszczędności RDN vs G11 (6×4 diagonal)
- `wykres_2_ranking_arch.png` — Ranking profili gospodarstw
- `wykres_3_boxplot.png` — Rozkład oszczędności per sezon (box plot)
- `wykres_4_scatter_risk.png` — Trade-off zysk vs ryzyko (scatter CVaR)
- `wykres_5_bar_taryfy.png` — Porównanie 3 taryf per profil (bar chart)
- `wykres_6_ranking_sezon.png` — Ranking sezonów cenowych
- `wykres_7_sensitivity.png` — Sensitivity 24×4 = 96 kombinacji

### DO ZROBIENIA

- **Diagram architektoniczny systemu** (rozdz. 4.1) — najważniejszy rysunek.
  Narzędzie: draw.io / excalidraw → export PNG. Pokazuje: frontend → Caddy →
  nginx → backend Spring Boot → Postgres/InfluxDB/MQTT.

- **Screenshoty aplikacji** (rozdz. 5):
  - Kreator z listą urządzeń
  - Lista testów pogrupowana per batch
  - Szczegóły testu z wykresem mocy
  - Batch Runner z siatką profili
  - Zakładka Koszt (G11/G12/RDN)
  - Swagger UI (dowód udokumentowanego API)

- **Diagram sekwencji API** (rozdz. 4.4) — jak zewnętrzny klient używa endpointów.
  Narzędzie: PlantUML albo mermaid.

- **Diagram profilu dobowego** dla jednego profilu (rozdz. 6.1) — pokazuje
  jak wygląda 24-godzinny wzorzec zużycia dla np. Singiel-biuro Zima.

---

## 7. Wyniki badawcze do wyeksponowania

Klucze wnioski, do których się odwołujemy w Podsumowaniu i Streszczeniu:

- **Średnia oszczędność RDN vs G11:** +0,97% (marginalna)
- **G12 wygrywa:** RDN średnio o 8,11% droższy od G12
- **Najlepszy sezon dla RDN:** Wiosna (+9,67%, dzięki ujemnym cenom PV w środku dnia)
- **Najgorszy sezon dla RDN:** Zima (-3,39%, wieczorne peaki cenowe)
- **Najlepszy profil:** D Senior samotny (+4,51%)
- **Najgorszy profil:** C Rodzina 2+2 (-3,44%)
- **Motywacja dla smart home:** RDN bez zarządzania obciążeniem jest ryzykiem;
  narzędzia automatyzacji pozwalają przesunąć zużycie do doliny cenowej.

---

## 8. Metodologia — kluczowe zastrzeżenia (do metodologicznej sekcji)

Uczciwe ograniczenia do zaznaczenia w pracy:

1. **Ceny 2025 × parametry taryf 2026** — hipotetyczny scenariusz "gdyby w 2026
   wzorce cenowe RDN były jak w 2025". Uzasadnione: parametry 2026 z URE,
   wzorce RDN 2025 dostępne w PSE.

2. **Profile zakładają ogrzewanie elektryczne** — reprezentują ~15-20% polskich
   gospodarstw (bez centralnego ogrzewania miejskiego). Intencjonalny wybór
   segmentu z potencjałem RDN.

3. **Rok cen bazowy 2025** — po tarczy mrozeniowej, ale przed pełnym uwolnieniem
   rynku. Pojedynczy rok, brak analizy inflacyjnej trendów wieloletnich.

4. **Symulator generuje syntetyczny profil dobowy** — nie kalibrowany na danych
   rzeczywistych z liczników smart. Walidacja teoretyczna przez porównanie sum
   dobowych z typowymi wartościami polskich gospodarstw.

5. **Wykryty i naprawiony bug w symulatorze** (opcjonalnie w metodologii) —
   początkowa iteracja skryptu analizy wykryła niezgodność między teoretycznym
   zużyciem a wynikami symulacji, prowadząc do wykrycia i naprawy bugu warstwy
   stemplowania czasu pomiarów. To ATUT pracy, pokazuje że narzędzie faktycznie
   wykrywa błędy w danych.

---

## 9. Kolejność pisania

Rekomendowana kolejność (od najłatwiejszego dla nas):

1. ✅ Rozdz. 7 — Wyniki (mamy dane, wykresy, tabele)
2. Rozdz. 6 — Metodyka badań (uzupełnia rozdz. 7)
3. Rozdz. 3 — Metodologia obliczeń (wzory G11/G12/RDN + VaR/CVaR)
4. Rozdz. 4 — Architektura systemu
5. Rozdz. 5 — Implementacja aplikacji (kod, moduły)
6. Rozdz. 2 — Podstawy teoretyczne (rynek energii + technologie)
7. Rozdz. 1 — Wstęp (na końcu bo znasz cały kontekst)
8. Rozdz. 8 — Podsumowanie (naturalnie na końcu)
9. Bibliografia, załączniki, streszczenie PL/EN

---

## 10. Praca w Overleaf

- Projekt Overleaf: załadowany, kompiluje się bez błędów
- Wersja obecna: strona tytułowa + spis treści + Wstęp (pusty) + Wyniki (rozdz. 2)
- Dodane w preamble: `\usepackage{float}`, `\usetikzlibrary{positioning, arrows.meta, shapes.geometric}`
- Folder `figures/` załadowany 7 wykresami

### Ostatnia poprawka do zrobienia
Zdanie na końcu rozdz. Wyniki: `"omówione w rozdziale ??"` → zmienić na
`"omówione w rozdziale końcowym pracy."` do czasu dodania rozdziału Podsumowanie
z labelem `\label{Sec:podsumowanie}`.

---

_Ostatnia aktualizacja: 2026-08-16, po napisaniu rozdz. 7 (Wyniki)_
