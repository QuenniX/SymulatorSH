# Tabela zbiorcza - analiza opłacalności RDN

## Tryb diagonalny (24 realistyczne przypadki)

Harmonogram sezonowy urządzeń + ceny RDN z tego samego sezonu 2025.

| profil_kod   | profil_nazwa   | sezon_harmonogram   |   koszt_G11_pln |   koszt_G12_pln |   koszt_RDN_pln |   oszczednosc_RDN_vs_G11_pct |   oszczednosc_RDN_vs_G12_pct |   VaR95_RDN_pln |   CVaR95_RDN_pln |
|:---------------|:-----------------|:--------------------|----------------:|----------------:|----------------:|-----------------------------:|-----------------------------:|----------------:|-----------------:|
| A              | Singiel-biuro    | Jesien              |          573.88 |          574.57 |          623.81 |                        -8.70 |                        -8.57 |           26.12 |            27.78 |
| A              | Singiel-biuro    | Lato                |          781.92 |          774.17 |          778.47 |                         0.44 |                        -0.56 |           29.18 |            29.89 |
| A              | Singiel-biuro    | Wiosna              |          462.01 |          447.79 |          431.48 |                         6.61 |                         3.64 |           16.53 |            16.64 |
| A              | Singiel-biuro    | Zima                |         1228.31 |         1229.24 |         1315.78 |                        -7.12 |                        -7.04 |           53.27 |            60.10 |
| B              | Remote worker    | Jesien              |          753.06 |          729.42 |          780.32 |                        -3.62 |                        -6.98 |           32.74 |            34.26 |
| B              | Remote worker    | Lato                |          967.12 |          928.21 |          943.38 |                         2.45 |                        -1.63 |           35.31 |            36.22 |
| B              | Remote worker    | Wiosna              |          627.71 |          578.83 |          541.85 |                        13.68 |                         6.39 |           22.02 |            22.35 |
| B              | Remote worker    | Zima                |         1503.42 |         1457.48 |         1579.10 |                        -5.03 |                        -8.34 |           62.99 |            70.41 |
| C              | Rodzina 2+2      | Jesien              |         1031.18 |         1031.06 |         1110.15 |                        -7.66 |                        -7.67 |           47.42 |            50.23 |
| C              | Rodzina 2+2      | Lato                |         1269.47 |         1233.28 |         1306.64 |                        -2.93 |                        -5.95 |           50.37 |            50.70 |
| C              | Rodzina 2+2      | Wiosna              |          936.22 |          921.15 |          915.58 |                         2.20 |                         0.60 |           35.44 |            36.02 |
| C              | Rodzina 2+2      | Zima                |         1956.99 |         1910.87 |         2061.91 |                        -5.36 |                        -7.90 |           82.54 |            91.23 |
| D              | Senior samotny   | Jesien              |          635.92 |          612.12 |          634.30 |                         0.26 |                        -3.62 |           25.84 |            27.05 |
| D              | Senior samotny   | Lato                |          751.61 |          705.88 |          729.03 |                         3.00 |                        -3.28 |           26.86 |            27.42 |
| D              | Senior samotny   | Wiosna              |          427.58 |          411.22 |          349.07 |                        18.36 |                        15.11 |           14.54 |            14.77 |
| D              | Senior samotny   | Zima                |         1291.34 |         1190.46 |         1337.42 |                        -3.57 |                       -12.34 |           53.18 |            58.57 |
| E              | Studenci         | Jesien              |          674.96 |          566.22 |          663.39 |                         1.71 |                       -17.16 |           26.24 |            27.34 |
| E              | Studenci         | Lato                |          976.56 |          832.60 |          986.59 |                        -1.03 |                       -18.50 |           36.70 |            37.33 |
| E              | Studenci         | Wiosna              |          622.86 |          512.98 |          564.03 |                         9.45 |                        -9.95 |           21.39 |            21.63 |
| E              | Studenci         | Zima                |         1603.66 |         1349.46 |         1612.06 |                        -0.52 |                       -19.46 |           63.82 |            68.61 |
| F              | Para DINK        | Jesien              |          900.48 |          716.84 |          873.48 |                         3.00 |                       -21.85 |           34.54 |            35.69 |
| F              | Para DINK        | Lato                |          968.86 |          807.70 |          982.17 |                        -1.37 |                       -21.60 |           36.13 |            36.92 |
| F              | Para DINK        | Wiosna              |          639.08 |          512.46 |          589.86 |                         7.70 |                       -15.10 |           21.98 |            22.22 |
| F              | Para DINK        | Zima                |         1546.02 |         1242.11 |         1526.06 |                         1.29 |                       -22.86 |           60.05 |            63.97 |

## Agregaty

### Średnia per profil (diagonal)

| profil_kod   | profil_nazwa   |   koszt_G11_pln |   koszt_G12_pln |   koszt_RDN_pln |   oszczednosc_RDN_vs_G11_pct |   oszczednosc_RDN_vs_G12_pct |
|:---------------|:-----------------|----------------:|----------------:|----------------:|-----------------------------:|-----------------------------:|
| A              | Singiel-biuro    |          761.53 |          756.44 |          787.38 |                        -2.19 |                        -3.13 |
| B              | Remote worker    |          962.83 |          923.49 |          961.16 |                         1.87 |                        -2.64 |
| C              | Rodzina 2+2      |         1298.47 |         1274.09 |         1348.57 |                        -3.44 |                        -5.23 |
| D              | Senior samotny   |          776.61 |          729.92 |          762.45 |                         4.51 |                        -1.03 |
| E              | Studenci         |          969.51 |          815.32 |          956.52 |                         2.40 |                       -16.27 |
| F              | Para DINK        |         1013.61 |          819.78 |          992.89 |                         2.66 |                       -20.35 |

### Średnia per sezon cenowy (diagonal)

| sezon_harmonogram   |   koszt_G11_pln |   koszt_G12_pln |   koszt_RDN_pln |   oszczednosc_RDN_vs_G11_pct |   oszczednosc_RDN_vs_G12_pct |
|:--------------------|----------------:|----------------:|----------------:|-----------------------------:|-----------------------------:|
| Jesien              |          761.58 |          705.04 |          780.91 |                        -2.50 |                       -10.97 |
| Lato                |          952.59 |          880.31 |          954.38 |                         0.09 |                        -8.59 |
| Wiosna              |          619.24 |          564.07 |          565.31 |                         9.67 |                         0.12 |
| Zima                |         1521.62 |         1396.60 |         1572.06 |                        -3.39 |                       -12.99 |

### Podsumowanie ogólne (diagonal)

- **Średni koszt G11:** 963.76 zł
- **Średni koszt G12:** 886.50 zł
- **Średni koszt RDN:** 968.16 zł
- **Średnia oszczędność RDN vs G11:** 0.97%
- **Średnia oszczędność RDN vs G12:** -8.11%
