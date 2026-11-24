-- Справочные значения учебной модели. Числа условные (слайды 6, 10).
INSERT INTO tariff (code, name, supply_price, price_per_min, price_per_km, long_pickup_fee) VALUES
    ('ECONOM',  'Эконом',  189.00, 13.00, 13.00, 150.00),
    ('COMFORT', 'Комфорт', 249.00, 17.00, 18.00, 150.00);

INSERT INTO tariff_option (code, name, price) VALUES
    ('CHILD_SEAT', 'Детское кресло',   150.00),
    ('PET',        'Перевоз животного', 150.00);
