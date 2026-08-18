# Tabela zbiorcza - analiza opłacalności RDN

## Tryb diagonalny (24 realistyczne przypadki)

Harmonogram sezonowy urządzeń + ceny RDN z tego samego sezonu 2025.

| profil_kod   | profil_nazwa     | sezon_harmonogram   |   udzial_strefy_nocnej_pct |   koszt_G11_pln |   koszt_G12_pln |   koszt_RDN_pln |   roznica_RDN_vs_G11_pct |   roznica_RDN_vs_G12_pct |   VaR95_RDN_pln |   CVaR95_RDN_pln |
|:-------------|:-----------------|:--------------------|---------------------------:|----------------:|----------------:|----------------:|-------------------------:|-------------------------:|----------------:|-----------------:|
| A            | Singiel-biuro    | Jesien              |                      23.56 |          555.45 |          555.27 |          631.87 |                   -13.76 |                   -13.79 |           26.15 |            27.90 |
| A            | Singiel-biuro    | Lato                |                      38.07 |          687.35 |          629.23 |          769.31 |                   -11.92 |                   -22.26 |           30.16 |            30.86 |
| A            | Singiel-biuro    | Wiosna              |                      27.85 |          335.76 |          327.28 |          369.80 |                   -10.14 |                   -12.99 |           14.39 |            14.79 |
| A            | Singiel-biuro    | Zima                |                      27.79 |         1185.42 |         1155.89 |         1322.73 |                   -11.58 |                   -14.43 |           52.18 |            56.89 |
| B            | Pracownik zdalny | Jesien              |                      29.09 |          772.41 |          747.34 |          832.05 |                    -7.72 |                   -11.33 |           32.94 |            34.52 |
| B            | Pracownik zdalny | Lato                |                      33.09 |          905.37 |          854.99 |          945.24 |                    -4.40 |                   -10.56 |           35.26 |            35.74 |
| B            | Pracownik zdalny | Wiosna              |                      30.66 |          529.37 |          507.38 |          485.66 |                     8.26 |                     4.28 |           19.49 |            19.77 |
| B            | Pracownik zdalny | Zima                |                      32.60 |         1407.53 |         1333.16 |         1569.08 |                   -11.48 |                   -17.70 |           61.75 |            68.04 |
| C            | Rodzina 2+2      | Jesien              |                      24.39 |         1065.17 |         1059.68 |         1208.28 |                   -13.44 |                   -14.02 |           49.75 |            52.67 |
| C            | Rodzina 2+2      | Lato                |                      33.41 |         1282.52 |         1208.78 |         1406.93 |                    -9.70 |                   -16.39 |           53.57 |            54.04 |
| C            | Rodzina 2+2      | Wiosna              |                      29.35 |          921.93 |          890.63 |          959.60 |                    -4.09 |                    -7.74 |           36.64 |            37.24 |
| C            | Rodzina 2+2      | Zima                |                      27.03 |         1785.05 |         1748.45 |         2004.73 |                   -12.31 |                   -14.66 |           79.42 |            87.41 |
| D            | Senior samotny   | Jesien              |                      20.39 |          605.94 |          616.88 |          653.30 |                    -7.81 |                    -5.90 |           26.22 |            27.40 |
| D            | Senior samotny   | Lato                |                      34.69 |          747.21 |          698.66 |          775.53 |                    -3.79 |                   -11.00 |           28.20 |            28.81 |
| D            | Senior samotny   | Wiosna              |                      22.30 |          401.24 |          404.05 |          362.77 |                     9.59 |                    10.22 |           14.71 |            14.92 |
| D            | Senior samotny   | Zima                |                      25.53 |         1238.46 |         1223.90 |         1385.77 |                   -11.89 |                   -13.23 |           54.68 |            60.15 |
| E            | Studenci         | Jesien              |                      43.35 |          752.34 |          665.64 |          811.79 |                    -7.90 |                   -21.96 |           31.94 |            33.55 |
| E            | Studenci         | Lato                |                      50.67 |          915.09 |          770.79 |          987.50 |                    -7.91 |                   -28.12 |           36.65 |            37.30 |
| E            | Studenci         | Wiosna              |                      50.59 |          534.15 |          450.15 |          531.04 |                     0.58 |                   -17.97 |           19.79 |            20.28 |
| E            | Studenci         | Zima                |                      36.75 |         1387.10 |         1280.46 |         1529.08 |                   -10.24 |                   -19.42 |           60.09 |            65.35 |
| F            | Para bez dzieci  | Jesien              |                      25.58 |          866.95 |          856.48 |          969.77 |                   -11.86 |                   -13.23 |           39.55 |            41.83 |
| F            | Para bez dzieci  | Lato                |                      34.67 |          942.89 |          881.74 |         1066.87 |                   -13.15 |                   -21.00 |           42.16 |            43.36 |
| F            | Para bez dzieci  | Wiosna              |                      27.95 |          579.16 |          564.19 |          631.63 |                    -9.06 |                   -11.95 |           24.34 |            25.01 |
| F            | Para bez dzieci  | Zima                |                      29.39 |         1551.12 |         1498.06 |         1720.98 |                   -10.95 |                   -14.88 |           67.74 |            73.58 |

## Agregaty

### Średnia per profil (diagonal)

| profil_kod   | profil_nazwa     |   koszt_G11_pln |   koszt_G12_pln |   koszt_RDN_pln |   roznica_RDN_vs_G11_pct |   roznica_RDN_vs_G12_pct |
|:-------------|:-----------------|----------------:|----------------:|----------------:|-------------------------:|-------------------------:|
| A            | Singiel-biuro    |          691.00 |          666.92 |          773.43 |                   -11.85 |                   -15.87 |
| B            | Pracownik zdalny |          903.67 |          860.72 |          958.01 |                    -3.83 |                    -8.83 |
| C            | Rodzina 2+2      |         1263.67 |         1226.88 |         1394.88 |                    -9.88 |                   -13.20 |
| D            | Senior samotny   |          748.21 |          735.87 |          794.34 |                    -3.48 |                    -4.98 |
| E            | Studenci         |          897.17 |          791.76 |          964.85 |                    -6.37 |                   -21.87 |
| F            | Para bez dzieci  |          985.03 |          950.12 |         1097.31 |                   -11.25 |                   -15.27 |

### Średnia per sezon cenowy (diagonal)

| sezon_harmonogram   |   koszt_G11_pln |   koszt_G12_pln |   koszt_RDN_pln |   roznica_RDN_vs_G11_pct |   roznica_RDN_vs_G12_pct |
|:--------------------|----------------:|----------------:|----------------:|-------------------------:|-------------------------:|
| Jesien              |          769.71 |          750.22 |          851.18 |                   -10.41 |                   -13.37 |
| Lato                |          913.41 |          840.70 |          991.90 |                    -8.48 |                   -18.22 |
| Wiosna              |          550.27 |          523.95 |          556.75 |                    -0.81 |                    -6.02 |
| Zima                |         1425.78 |         1373.32 |         1588.73 |                   -11.41 |                   -15.72 |

### Podsumowanie ogólne (diagonal)

- **Średni koszt G11:** 914.79 zł
- **Średni koszt G12:** 872.05 zł
- **Średni koszt RDN:** 997.14 zł
- **Średnia różnica kosztu RDN vs G11:** -7.78%
- **Średnia różnica kosztu RDN vs G12:** -13.33%

### Ujęcie kwotowe (iloraz średnich, ważone zużyciem)

- **Suma kosztów:** G11 = 21954.98 zł, G12 = 20929.08 zł, RDN = 23931.31 zł
- **RDN vs G11:** -9.00% (średnia z procentów: -7.78%)
- **RDN vs G12:** -14.34% (średnia z procentów: -13.33%)
- **G12 vs G11:** 4.67%

### Próg opłacalności G12

- **Próg udziału strefy nocnej:** τ = 23.50%
- **Próg ceny hurtowej dla RDN vs G11:** 398 zł/MWh
- **Przypadki, w których G11 jest tańsza od G12:** D-Jesien, D-Wiosna
- **Kontrola:** zbiór przewidziany progiem zgodny ze zbiorem wyznaczonym kosztowo

### Przypadki nierozstrzygnięte (udział strefy nocnej w granicach ±1 p.p. od progu)

| przypadek | udział strefy nocnej | G11 | G12 | różnica |
|:---|---:|---:|---:|---:|
| A-Jesien | 23.56% | 555.45 zł | 555.27 zł | -0.18 zł |
| C-Jesien | 24.39% | 1065.17 zł | 1059.68 zł | -5.49 zł |

W tych przypadkach wskazanie tańszej taryfy nie ma znaczenia praktycznego i nie powinno być raportowane jako rozstrzygnięcie.
