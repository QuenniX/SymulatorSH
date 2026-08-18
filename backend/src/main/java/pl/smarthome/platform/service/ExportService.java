package pl.smarthome.platform.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import pl.smarthome.platform.api.dto.ProjectedCostBreakdown;
import pl.smarthome.platform.api.dto.TestResponse;
import pl.smarthome.platform.influx.InfluxQueryService;
import pl.smarthome.platform.tariff.CostCalculatorService;
import pl.smarthome.platform.tariff.Tariff;
import pl.smarthome.platform.tariff.TariffParams;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Eksport wynikow testu do pliku CSV lub XLSX.
 *
 * <p>Godzinowe zuzycie energii + koszty w 3 taryfach (G11/G12/RDN) - dokladnie to
 * co potrzeba do analizy Pythonem/Excelem dla pracy magisterskiej.</p>
 *
 * <p>Format CSV: nagłowek + wiersze {@code date;hour;kwh;cost_g11;cost_g12;cost_rdn;rdn_price}.
 * Separator srednik (';') zeby Excel PL rozumial. Nagłowki po polsku.</p>
 *
 * <p>Format XLSX: 3 arkusze - "Godziny" (dane), "Podsumowanie" (sumy per taryfa
 * + oszczednosc %), "Konfiguracja" (metadata testu).</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExportService {

    private final InfluxQueryService influxQueryService;
    private final CostCalculatorService costCalculatorService;
    private final EnergyPriceService energyPriceService;
    private final TestService testService;

    /**
     * Eksport godzinowych danych do CSV. Kolumny: data, godzina, kWh, G11, G12, RDN, cena RDN.
     * Zwraca bajty pliku.
     */
    public byte[] exportCsv(UUID testId) {
        TestResponse test = testService.getTest(testId);
        Map<LocalDateTime, BigDecimal> hourlyKwh = influxQueryService.getHourlyEnergyKwh(testId);

        if (hourlyKwh.isEmpty()) {
            throw new IllegalStateException("Test nie ma pomiarow do eksportu (brak danych godzinowych w InfluxDB)");
        }

        // Sort po czasie
        Map<LocalDateTime, BigDecimal> sorted = new TreeMap<>(hourlyKwh);
        int year = sorted.keySet().iterator().next().getYear();

        DateTimeFormatter dateFmt = DateTimeFormatter.ISO_LOCAL_DATE;
        StringBuilder sb = new StringBuilder(4096);

        // Naglowek CSV (separator srednik dla Excel PL)
        sb.append("data;godzina;zuzycie_kWh;koszt_G11_zl;koszt_G12_zl;koszt_RDN_zl;cena_RDN_zl_kWh\n");

        for (Map.Entry<LocalDateTime, BigDecimal> e : sorted.entrySet()) {
            LocalDateTime ldt = e.getKey();
            LocalDate date = ldt.toLocalDate();
            int hour = ldt.getHour();
            BigDecimal kwh = e.getValue();

            BigDecimal costG11 = kwh.multiply(TariffParams.g11Price(year));
            BigDecimal costG12 = kwh.multiply(TariffParams.g12PriceForHour(year, date, hour));

            // Cena RDN z bazy
            BigDecimal rdnPrice = energyPriceService.getPriceForHour("RDN", date, hour)
                    .orElse(BigDecimal.ZERO);
            BigDecimal costRdn = rdnPrice.compareTo(BigDecimal.ZERO) > 0
                    ? kwh.multiply(TariffParams.rdnFinalPrice(year, rdnPrice.divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP)))
                    : costG11;  // fallback do G11 jesli brak ceny RDN

            sb.append(date.format(dateFmt)).append(';')
              .append(String.format("%02d:00", hour)).append(';')
              .append(fmtNumber(kwh, 4)).append(';')
              .append(fmtNumber(costG11, 4)).append(';')
              .append(fmtNumber(costG12, 4)).append(';')
              .append(fmtNumber(costRdn, 4)).append(';')
              .append(fmtNumber(rdnPrice, 2))  // cena w zl/MWh
              .append('\n');
        }

        log.info("Eksport CSV testu {}: {} wierszy godzinowych", testId, sorted.size());
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Eksport do XLSX - 3 arkusze: Godziny (dane), Podsumowanie (sumy + oszczedność),
     * Konfiguracja (metadata testu).
     */
    public byte[] exportXlsx(UUID testId) {
        TestResponse test = testService.getTest(testId);
        Map<LocalDateTime, BigDecimal> hourlyKwh = influxQueryService.getHourlyEnergyKwh(testId);

        if (hourlyKwh.isEmpty()) {
            throw new IllegalStateException("Test nie ma pomiarow do eksportu");
        }

        try (Workbook wb = new XSSFWorkbook()) {
            // Styl dla nagłowkow - bold + tło
            CellStyle headerStyle = wb.createCellStyle();
            Font boldFont = wb.createFont();
            boldFont.setBold(true);
            headerStyle.setFont(boldFont);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            // === Arkusz 1: Godziny ===
            Sheet hoursSheet = wb.createSheet("Godziny");
            String[] hoursHeaders = {"Data", "Godzina", "Zużycie [kWh]", "Koszt G11 [zł]",
                                     "Koszt G12 [zł]", "Koszt RDN [zł]", "Cena RDN [zł/MWh]"};
            createHeaderRow(hoursSheet, hoursHeaders, headerStyle);

            Map<LocalDateTime, BigDecimal> sorted = new TreeMap<>(hourlyKwh);
            int year = sorted.keySet().iterator().next().getYear();

            BigDecimal totalKwh = BigDecimal.ZERO;
            BigDecimal totalG11 = BigDecimal.ZERO;
            BigDecimal totalG12 = BigDecimal.ZERO;
            BigDecimal totalRdn = BigDecimal.ZERO;

            int rowIdx = 1;
            for (Map.Entry<LocalDateTime, BigDecimal> e : sorted.entrySet()) {
                LocalDateTime ldt = e.getKey();
                LocalDate date = ldt.toLocalDate();
                int hour = ldt.getHour();
                BigDecimal kwh = e.getValue();

                BigDecimal costG11 = kwh.multiply(TariffParams.g11Price(year));
                BigDecimal costG12 = kwh.multiply(TariffParams.g12PriceForHour(year, date, hour));
                BigDecimal rdnPrice = energyPriceService.getPriceForHour("RDN", date, hour)
                        .orElse(BigDecimal.ZERO);
                BigDecimal costRdn = rdnPrice.compareTo(BigDecimal.ZERO) > 0
                        ? kwh.multiply(TariffParams.rdnFinalPrice(year, rdnPrice.divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP)))
                        : costG11;

                Row row = hoursSheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(date.toString());
                row.createCell(1).setCellValue(String.format("%02d:00", hour));
                row.createCell(2).setCellValue(kwh.doubleValue());
                row.createCell(3).setCellValue(costG11.doubleValue());
                row.createCell(4).setCellValue(costG12.doubleValue());
                row.createCell(5).setCellValue(costRdn.doubleValue());
                row.createCell(6).setCellValue(rdnPrice.doubleValue());

                totalKwh = totalKwh.add(kwh);
                totalG11 = totalG11.add(costG11);
                totalG12 = totalG12.add(costG12);
                totalRdn = totalRdn.add(costRdn);
            }
            for (int i = 0; i < hoursHeaders.length; i++) hoursSheet.autoSizeColumn(i);

            // === Arkusz 2: Podsumowanie ===
            Sheet sumSheet = wb.createSheet("Podsumowanie");
            createHeaderRow(sumSheet, new String[]{"Metryka", "Wartosc"}, headerStyle);

            int r = 1;
            addSumRow(sumSheet, r++, "Nazwa testu", test.getName());
            addSumRow(sumSheet, r++, "Test ID", test.getTestId().toString());
            addSumRow(sumSheet, r++, "Dni symulacji", String.valueOf(test.getDurationDays()));
            addSumRow(sumSheet, r++, "Speed factor", "×" + test.getSpeedFactor());
            addSumRow(sumSheet, r++, "Liczba godzin z pomiarami", String.valueOf(sorted.size()));
            addSumRow(sumSheet, r++, "Rok cen taryf", String.valueOf(year));
            r++;  // pusta linia
            addSumRow(sumSheet, r++, "Sumaryczne zuzycie [kWh]", fmtNumber(totalKwh, 2));
            addSumRow(sumSheet, r++, "Sredni dzienny [kWh]", fmtNumber(totalKwh.divide(BigDecimal.valueOf(Math.max(1, sorted.size() / 24)), 2, RoundingMode.HALF_UP), 2));
            r++;
            addSumRow(sumSheet, r++, "Koszt G11 [zł]", fmtNumber(totalG11, 2));
            addSumRow(sumSheet, r++, "Koszt G12 [zł]", fmtNumber(totalG12, 2));
            addSumRow(sumSheet, r++, "Koszt RDN [zł]", fmtNumber(totalRdn, 2));
            r++;
            // Oszczednosc RDN vs G11 w procentach
            if (totalG11.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal savingsRdn = totalG11.subtract(totalRdn)
                        .divide(totalG11, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));
                addSumRow(sumSheet, r++, "Oszczędność RDN vs G11 [%]", fmtNumber(savingsRdn, 2));
                Tariff cheapest = totalRdn.compareTo(totalG11) < 0 && totalRdn.compareTo(totalG12) < 0
                        ? Tariff.RDN
                        : (totalG12.compareTo(totalG11) < 0 ? Tariff.G12 : Tariff.G11);
                addSumRow(sumSheet, r++, "Najtańsza taryfa", cheapest.name());
            }
            sumSheet.autoSizeColumn(0);
            sumSheet.autoSizeColumn(1);

            // === Arkusz 3: Konfiguracja ===
            Sheet cfgSheet = wb.createSheet("Konfiguracja");
            createHeaderRow(cfgSheet, new String[]{"Klucz", "Wartosc"}, headerStyle);
            cfgSheet.createRow(1).createCell(0).setCellValue("Konfiguracja JSON testu:");
            cfgSheet.createRow(2).createCell(0).setCellValue(String.valueOf(test.getConfig()));
            cfgSheet.autoSizeColumn(0);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            log.info("Eksport XLSX testu {}: {} godzin, {} bajtow", testId, sorted.size(), out.size());
            return out.toByteArray();

        } catch (IOException e) {
            throw new RuntimeException("Blad generowania XLSX: " + e.getMessage(), e);
        }
    }

    // ==========================================================
    //  Helpery
    // ==========================================================

    private String fmtNumber(BigDecimal value, int scale) {
        return value.setScale(scale, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }

    private void createHeaderRow(Sheet sheet, String[] headers, CellStyle style) {
        Row row = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = row.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(style);
        }
    }

    private void addSumRow(Sheet sheet, int rowIdx, String key, String value) {
        Row row = sheet.createRow(rowIdx);
        row.createCell(0).setCellValue(key);
        row.createCell(1).setCellValue(value);
    }
}
