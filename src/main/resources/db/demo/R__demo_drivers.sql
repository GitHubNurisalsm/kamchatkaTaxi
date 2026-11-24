-- Демо-данные: подключаются только в профиле "demo" (см. application-demo.yml).
--
-- Repeatable-миграция: Flyway перезапускает её при каждом изменении файла, поэтому она обязана
-- работать поверх уже существующих данных:
--   * водителей НЕ удаляем — на них ссылаются заказы, журнал и расчёты (внешние ключи);
--   * отсутствующих демо-водителей добавляем, существующих — обновляем по имени;
--   * занятых (BUSY) не трогаем: у них активный заказ, сброс статуса нарушил бы инвариант
--     «BUSY ⇔ есть активный заказ»;
--   * водитель, удалённый из этого файла, остаётся в БД (удалять вручную, если на него нет ссылок).
INSERT INTO taxi_park (name) VALUES ('Демо-парк') ON CONFLICT (name) DO NOTHING;
INSERT INTO taxi_park (name) VALUES ('Демо-парк Бишкек') ON CONFLICT (name) DO NOTHING;

DROP TABLE IF EXISTS demo_driver_seed;
CREATE TEMPORARY TABLE demo_driver_seed (
    full_name   VARCHAR(200),
    city_code   VARCHAR(32),
    tariff_code VARCHAR(32),
    park_name   VARCHAR(200),
    status      VARCHAR(16),
    lat         DOUBLE PRECISION,
    lon         DOUBLE PRECISION
);

INSERT INTO demo_driver_seed VALUES
    -- Бишкек
    ('Демо Бишкек 1',   'BISHKEK', 'ECONOM',  'Демо-парк Бишкек', 'FREE',    42.8765, 74.6037),  -- площадь Ала-Тоо
    ('Демо Бишкек 2',   'BISHKEK', 'ECONOM',  'Демо-парк Бишкек', 'FREE',    42.8760, 74.6142),  -- ЦУМ
    ('Демо Бишкек 3',   'BISHKEK', 'COMFORT', 'Демо-парк Бишкек', 'FREE',    42.8746, 74.5698),  -- Ошский базар
    ('Демо Бишкек 4',   'BISHKEK', 'ECONOM',  'Демо-парк Бишкек', 'OFFLINE', 42.8559, 74.5860),  -- Asia Mall
    ('Демо Бишкек 5',   'BISHKEK', 'ECONOM',  'Демо-парк Бишкек', 'FREE',    42.9050, 74.6600),  -- Аламедин: дальняя подача
    -- Москва
    ('Демо Водитель 1', 'MOSCOW',  'ECONOM',  'Демо-парк',        'FREE',    55.7560, 37.6170),
    ('Демо Водитель 2', 'MOSCOW',  'ECONOM',  'Демо-парк',        'FREE',    55.7610, 37.6090),
    ('Демо Водитель 3', 'MOSCOW',  'COMFORT', 'Демо-парк',        'FREE',    55.7520, 37.6230),
    ('Демо Водитель 4', 'MOSCOW',  'ECONOM',  'Демо-парк',        'OFFLINE', 55.7580, 37.6200),
    ('Демо Водитель 5', 'MOSCOW',  'ECONOM',  'Демо-парк',        'FREE',    55.8000, 37.6500);

-- Существующих и не занятых — вернуть к значениям из файла.
UPDATE driver d
SET city_code   = s.city_code,
    tariff_code = s.tariff_code,
    park_id     = p.id,
    status      = s.status,
    lat         = s.lat,
    lon         = s.lon
FROM demo_driver_seed s
JOIN taxi_park p ON p.name = s.park_name
WHERE d.full_name = s.full_name
  AND d.status <> 'BUSY';

-- Отсутствующих — добавить.
INSERT INTO driver (full_name, city_code, tariff_code, park_id, status, lat, lon)
SELECT s.full_name, s.city_code, s.tariff_code, p.id, s.status, s.lat, s.lon
FROM demo_driver_seed s
JOIN taxi_park p ON p.name = s.park_name
WHERE NOT EXISTS (SELECT 1 FROM driver d WHERE d.full_name = s.full_name);

DROP TABLE demo_driver_seed;
