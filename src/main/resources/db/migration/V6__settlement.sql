-- Этап 5: оплата и расчёт с водителем («куда идут деньги», слайды 9–10).
--
-- Влияние на существующие строки: новые колонки ставок добавляются nullable, заполняются
-- значениями по городам/тарифам и только потом становятся NOT NULL. Таблицы маленькие (справочники).
-- У заказов, назначенных до V6, km подачи неизвестны (NULL) — в расчёте считаются как 0.

-- 1. Комиссия сервиса — по тарифу города.
ALTER TABLE tariff ADD COLUMN service_commission_rate NUMERIC(6, 4);
-- Москва: Infull, 31.08.2026 (слайд 9). Бишкек: vb.kg — «Комфорт» 17%, остальные тарифы на 1% ниже.
UPDATE tariff SET service_commission_rate = CASE
    WHEN city_code = 'MOSCOW'  AND code = 'ECONOM'  THEN 0.2237
    WHEN city_code = 'MOSCOW'                       THEN 0.2542
    WHEN city_code = 'BISHKEK' AND code = 'COMFORT' THEN 0.1700
    WHEN city_code = 'BISHKEK'                      THEN 0.1600
    ELSE 0.2000 END;
ALTER TABLE tariff ALTER COLUMN service_commission_rate SET NOT NULL;
ALTER TABLE tariff ADD CONSTRAINT tariff_commission_range CHECK (service_commission_rate BETWEEN 0 AND 1);

-- 2. Налоги и стоимость километра — по городу.
ALTER TABLE city ADD COLUMN income_tax_name VARCHAR(60);
ALTER TABLE city ADD COLUMN income_tax_rate NUMERIC(6, 4);
ALTER TABLE city ADD COLUMN social_contribution_rate NUMERIC(6, 4);
ALTER TABLE city ADD COLUMN fuel_cost_per_km NUMERIC(8, 4);
-- Москва: НПД 4% с физлиц (слайд 9), топливо 70 ₽ / 13,5 км (слайд 10).
UPDATE city SET income_tax_name = 'НПД', income_tax_rate = 0.0400, social_contribution_rate = 0,
                fuel_cost_per_km = 5.1852
 WHERE code = 'MOSCOW';
-- Бишкек: с 01.09.2025 агрегатор удерживает 1% подоходного налога и 1% страховых взносов (Kaktus, Кабар).
-- Топливо 5,5 сом/км — условно (≈ 8 л/100 км).
UPDATE city SET income_tax_name = 'Подоходный налог', income_tax_rate = 0.0100, social_contribution_rate = 0.0100,
                fuel_cost_per_km = 5.5000
 WHERE code = 'BISHKEK';
UPDATE city SET income_tax_name = 'Налог', income_tax_rate = 0, social_contribution_rate = 0, fuel_cost_per_km = 0
 WHERE income_tax_name IS NULL;
ALTER TABLE city ALTER COLUMN income_tax_name SET NOT NULL;
ALTER TABLE city ALTER COLUMN income_tax_rate SET NOT NULL;
ALTER TABLE city ALTER COLUMN social_contribution_rate SET NOT NULL;
ALTER TABLE city ALTER COLUMN fuel_cost_per_km SET NOT NULL;
ALTER TABLE city ADD CONSTRAINT city_rates_range CHECK (
    income_tax_rate BETWEEN 0 AND 1 AND social_contribution_rate BETWEEN 0 AND 1 AND fuel_cost_per_km >= 0);

-- 3. Ставка таксопарка; при прямой работе (без парка) её нет (слайд 9).
ALTER TABLE taxi_park ADD COLUMN commission_rate NUMERIC(6, 4) NOT NULL DEFAULT 0.0300
    CHECK (commission_rate BETWEEN 0 AND 1);

-- 4. Км подачи фиксируются при назначении водителя (нужны для расхода топлива).
ALTER TABLE ride_order ADD COLUMN pickup_km NUMERIC(8, 2) CHECK (pickup_km >= 0);

-- 5. Расчёт по поездке. Ставки копируются: изменение ставок не меняет прошлые расчёты.
CREATE TABLE ride_settlement (
    order_id                  UUID PRIMARY KEY REFERENCES ride_order (id),
    driver_id                 BIGINT        NOT NULL REFERENCES driver (id),
    park_id                   BIGINT        REFERENCES taxi_park (id),
    currency                  CHAR(3)       NOT NULL,
    amount                    NUMERIC(12, 2) NOT NULL CHECK (amount >= 0),
    service_rate              NUMERIC(6, 4) NOT NULL,
    service_fee               NUMERIC(12, 2) NOT NULL,
    park_rate                 NUMERIC(6, 4) NOT NULL,
    park_fee                  NUMERIC(12, 2) NOT NULL,
    income_tax_name           VARCHAR(60)   NOT NULL,
    income_tax_rate           NUMERIC(6, 4) NOT NULL,
    income_tax                NUMERIC(12, 2) NOT NULL,
    social_rate               NUMERIC(6, 4) NOT NULL,
    social_fee                NUMERIC(12, 2) NOT NULL,
    fuel_km                   NUMERIC(8, 2) NOT NULL,
    fuel_cost_per_km          NUMERIC(8, 4) NOT NULL,
    fuel_cost                 NUMERIC(12, 2) NOT NULL,
    -- Может быть отрицательным: короткая дальняя подача съедает доход (это честный результат, а не ошибка).
    driver_net                NUMERIC(12, 2) NOT NULL,
    created_at                TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ride_settlement_balanced CHECK (
        amount = service_fee + park_fee + income_tax + social_fee + fuel_cost + driver_net)
);

CREATE INDEX ride_settlement_driver_time_idx ON ride_settlement (driver_id, created_at);
