package pl.smarthome.platform.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Godzinowa cena energii - reprezentacja do REST API.
 * Pole pricePlnKwh policzone z pricePlnMwh dla wygody frontendu.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EnergyPriceDto {
    private String market;
    private LocalDate deliveryDate;
    private Integer hour;
    /** Cena z RDN w zl/MWh (jednostka rynkowa). */
    private BigDecimal pricePlnMwh;
    /** Ta sama cena przeliczona na zl/kWh (dla wygody UI). */
    private BigDecimal pricePlnKwh;
}
