package ru.taksi.geo;

/**
 * Зона спроса — ячейка квадратной сетки по координатам (слайд 11: «пины / машины по зонам»).
 */
public record GridCell(int latIdx, int lonIdx) {

    public static GridCell of(GeoPoint point, double cellSizeDeg) {
        return new GridCell(
                (int) Math.floor(point.lat() / cellSizeDeg),
                (int) Math.floor(point.lon() / cellSizeDeg));
    }

    /** Нижняя граница области «ячейка ± radius» в градусах широты. */
    public double minLat(int radius, double cellSizeDeg) {
        return (latIdx - radius) * cellSizeDeg;
    }

    public double maxLat(int radius, double cellSizeDeg) {
        return (latIdx + radius + 1) * cellSizeDeg;
    }

    public double minLon(int radius, double cellSizeDeg) {
        return (lonIdx - radius) * cellSizeDeg;
    }

    public double maxLon(int radius, double cellSizeDeg) {
        return (lonIdx + radius + 1) * cellSizeDeg;
    }
}
