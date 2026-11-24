package ru.taksi.tariff;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.math.BigDecimal;

@Entity
@IdClass(CityScopedId.class)
@Table(name = "tariff_option")
public class TariffOption {

    @Id
    @Column(name = "city_code")
    private String cityCode;

    @Id
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private BigDecimal price;

    @Column(nullable = false)
    private boolean active;

    protected TariffOption() {
    }

    public String getCityCode() {
        return cityCode;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public boolean isActive() {
        return active;
    }
}
