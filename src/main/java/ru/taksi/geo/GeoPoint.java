package ru.taksi.geo;

/**
 * Точка на карте. Диапазоны координат проверяются в DTO и в CHECK-ограничениях БД.
 */
public record GeoPoint(double lat, double lon) {

    private static final double EARTH_RADIUS_KM = 6371.0;

    /** Расстояние по прямой (формула гаверсинусов), км. */
    public double distanceKm(GeoPoint other) {
        double dLat = Math.toRadians(other.lat - lat);
        double dLon = Math.toRadians(other.lon - lon);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(other.lat))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }
}
