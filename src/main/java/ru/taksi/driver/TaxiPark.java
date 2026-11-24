package ru.taksi.driver;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/** Таксопарк: подключает водителя и берёт свою долю с поездки (слайд 3, 9). */
@Entity
@Table(name = "taxi_park")
public class TaxiPark {

    @Id
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "commission_rate", nullable = false)
    private BigDecimal commissionRate;

    protected TaxiPark() {
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getCommissionRate() {
        return commissionRate;
    }
}
