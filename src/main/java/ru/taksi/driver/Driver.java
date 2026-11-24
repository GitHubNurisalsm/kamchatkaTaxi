package ru.taksi.driver;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import ru.taksi.geo.GeoPoint;

/**
 * Водитель. Инвариант (держит сервисный слой): BUSY ⇔ у водителя есть активный заказ.
 * Статус меняется только под блокировкой строки (см. DriverRepository#findForUpdate).
 */
@Entity
@Table(name = "driver")
public class Driver {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "city_code", nullable = false)
    private String cityCode;

    @Column(name = "tariff_code", nullable = false)
    private String tariffCode;

    @Column(name = "park_id")
    private Long parkId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DriverStatus status;

    private Double lat;

    private Double lon;

    protected Driver() {
    }

    void goOnline(GeoPoint location) {
        moveTo(location);
        status = DriverStatus.FREE;
    }

    void goOffline() {
        status = DriverStatus.OFFLINE;
    }

    public void moveTo(GeoPoint location) {
        lat = location.lat();
        lon = location.lon();
    }

    /** Водитель получил заказ. */
    public void markBusy() {
        if (status != DriverStatus.FREE) {
            throw new IllegalStateException("driver " + id + " is not FREE: " + status);
        }
        status = DriverStatus.BUSY;
    }

    /** Заказ завершён, отменён или водитель отказался — снова на линии. */
    public void markFree() {
        status = DriverStatus.FREE;
    }

    public Long getId() {
        return id;
    }

    public String getFullName() {
        return fullName;
    }

    public String getCityCode() {
        return cityCode;
    }

    public String getTariffCode() {
        return tariffCode;
    }

    public Long getParkId() {
        return parkId;
    }

    public DriverStatus getStatus() {
        return status;
    }

    public Double getLat() {
        return lat;
    }

    public Double getLon() {
        return lon;
    }
}
