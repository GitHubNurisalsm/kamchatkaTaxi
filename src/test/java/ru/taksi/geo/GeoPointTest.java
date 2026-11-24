package ru.taksi.geo;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class GeoPointTest {

    /** Риск: ошибка в формуле расстояния исказит D и, значит, цену. */
    @Test
    void moscowToSaintPetersburgIsAbout634Km() {
        GeoPoint moscow = new GeoPoint(55.7558, 37.6173);
        GeoPoint spb = new GeoPoint(59.9343, 30.3351);
        assertThat(moscow.distanceKm(spb)).isCloseTo(634, within(5.0));
    }

    @Test
    void samePointIsZero() {
        GeoPoint p = new GeoPoint(55.75, 37.61);
        assertThat(p.distanceKm(p)).isZero();
    }

    /** Риск: отрицательные координаты попадают не в ту ячейку (floor, а не усечение к нулю). */
    @Test
    void gridCellUsesFloorForNegativeCoordinates() {
        GridCell cell = GridCell.of(new GeoPoint(-0.005, -0.005), 0.01);
        assertThat(cell.latIdx()).isEqualTo(-1);
        assertThat(cell.lonIdx()).isEqualTo(-1);
    }
}
