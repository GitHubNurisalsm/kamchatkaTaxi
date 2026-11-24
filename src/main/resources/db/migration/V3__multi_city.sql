-- Мультигородская модель: тарифы, опции, водители и предложения цены привязаны к городу.
-- Город определяется по точке А (попадание в границы зоны обслуживания).
--
-- Влияние на существующие строки: всё, что было создано до V3, относилось к Москве (цены в рублях),
-- поэтому существующие строки заполняются city_code = 'MOSCOW', currency = 'RUB'.
-- Объём данных на этом этапе — справочники и демо-данные, блокировки кратковременные.

CREATE TABLE city (
    code      VARCHAR(32) PRIMARY KEY,
    name      VARCHAR(100)     NOT NULL,
    currency  CHAR(3)          NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),   -- ISO 4217
    min_lat   DOUBLE PRECISION NOT NULL CHECK (min_lat BETWEEN -90 AND 90),
    max_lat   DOUBLE PRECISION NOT NULL CHECK (max_lat BETWEEN -90 AND 90),
    min_lon   DOUBLE PRECISION NOT NULL CHECK (min_lon BETWEEN -180 AND 180),
    max_lon   DOUBLE PRECISION NOT NULL CHECK (max_lon BETWEEN -180 AND 180),
    active    BOOLEAN          NOT NULL DEFAULT TRUE,
    CHECK (min_lat < max_lat AND min_lon < max_lon),
    -- Зоны обслуживания не пересекаются: любая точка относится не более чем к одному городу.
    CONSTRAINT city_area_no_overlap EXCLUDE USING gist (
        box(point(min_lon, min_lat), point(max_lon, max_lat)) WITH &&
    )
);

-- Границы — прямоугольники с запасом вокруг городской черты (учебное упрощение вместо полигонов).
INSERT INTO city (code, name, currency, min_lat, max_lat, min_lon, max_lon) VALUES
    ('MOSCOW',  'Москва',  'RUB', 55.49, 56.01, 37.29, 37.97),
    ('BISHKEK', 'Бишкек',  'KGS', 42.78, 42.95, 74.45, 74.75);

-- 1. Снимаем внешние ключи, которые ссылаются на старый первичный ключ тарифа.
ALTER TABLE driver      DROP CONSTRAINT driver_tariff_code_fkey;
ALTER TABLE price_quote DROP CONSTRAINT price_quote_tariff_code_fkey;

-- 2. Тариф: ключ (город, код). Код вроде ECONOM повторяется в разных городах с разными ценами.
ALTER TABLE tariff ADD COLUMN city_code VARCHAR(32);
UPDATE tariff SET city_code = 'MOSCOW';
ALTER TABLE tariff ALTER COLUMN city_code SET NOT NULL;
ALTER TABLE tariff ADD CONSTRAINT tariff_city_fkey FOREIGN KEY (city_code) REFERENCES city (code);
ALTER TABLE tariff DROP CONSTRAINT tariff_pkey;
ALTER TABLE tariff ADD CONSTRAINT tariff_pkey PRIMARY KEY (city_code, code);

-- 3. Опции: цена в валюте города, поэтому тоже по городам.
ALTER TABLE tariff_option ADD COLUMN city_code VARCHAR(32);
UPDATE tariff_option SET city_code = 'MOSCOW';
ALTER TABLE tariff_option ALTER COLUMN city_code SET NOT NULL;
ALTER TABLE tariff_option ADD CONSTRAINT tariff_option_city_fkey FOREIGN KEY (city_code) REFERENCES city (code);
ALTER TABLE tariff_option DROP CONSTRAINT tariff_option_pkey;
ALTER TABLE tariff_option ADD CONSTRAINT tariff_option_pkey PRIMARY KEY (city_code, code);

-- 4. Водитель работает в одном городе и по тарифу этого города.
ALTER TABLE driver ADD COLUMN city_code VARCHAR(32);
UPDATE driver SET city_code = 'MOSCOW';
ALTER TABLE driver ALTER COLUMN city_code SET NOT NULL;
ALTER TABLE driver ADD CONSTRAINT driver_tariff_fkey
    FOREIGN KEY (city_code, tariff_code) REFERENCES tariff (city_code, code);
CREATE INDEX driver_city_free_idx ON driver (city_code, tariff_code) WHERE status = 'FREE';

-- 5. Предложение цены хранит город и валюту на момент расчёта: история не «переедет» в другую валюту.
ALTER TABLE price_quote ADD COLUMN city_code VARCHAR(32);
ALTER TABLE price_quote ADD COLUMN currency CHAR(3);
UPDATE price_quote SET city_code = 'MOSCOW', currency = 'RUB';
ALTER TABLE price_quote ALTER COLUMN city_code SET NOT NULL;
ALTER TABLE price_quote ALTER COLUMN currency SET NOT NULL;
ALTER TABLE price_quote ADD CONSTRAINT price_quote_tariff_fkey
    FOREIGN KEY (city_code, tariff_code) REFERENCES tariff (city_code, code);

-- 6. Тарифы Бишкека, сом.
-- ECONOM: официальная страница Яндекс Go для Бишкека (посадка 48, до 3,7 сом/мин, до 10 сом/км),
-- дата действия на странице не указана. Остальные значения — условные (учебная модель).
INSERT INTO tariff (city_code, code, name, supply_price, price_per_min, price_per_km, long_pickup_fee) VALUES
    ('BISHKEK', 'ECONOM',  'Эконом',  48.00, 3.70, 10.00, 30.00),
    ('BISHKEK', 'COMFORT', 'Комфорт', 70.00, 5.00, 14.00, 30.00);

INSERT INTO tariff_option (city_code, code, name, price) VALUES
    ('BISHKEK', 'CHILD_SEAT', 'Детское кресло',    50.00),
    ('BISHKEK', 'PET',        'Перевоз животного', 50.00);
