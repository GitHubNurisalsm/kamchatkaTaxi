package ru.taksi.demand;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Пин спроса — метка точки А при запросе цены (слайд 7). */
@Entity
@Table(name = "demand_pin")
public class DemandPin {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cell_lat", nullable = false)
    private int cellLat;

    @Column(name = "cell_lon", nullable = false)
    private int cellLon;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected DemandPin() {
    }

    public DemandPin(int cellLat, int cellLon, Instant createdAt) {
        this.cellLat = cellLat;
        this.cellLon = cellLon;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }
}
