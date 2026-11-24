-- Этап 1–2: справочники тарифов и опций, водители (для расчёта предложения),
-- пины спроса и зафиксированные предложения цены (quote).
-- Таблицы заказов и выплат добавятся отдельными миграциями на следующих этапах.

CREATE TABLE tariff (
    code             VARCHAR(32) PRIMARY KEY,
    name             VARCHAR(100)   NOT NULL,
    supply_price     NUMERIC(10, 2) NOT NULL CHECK (supply_price >= 0),    -- S: подача
    price_per_min    NUMERIC(10, 2) NOT NULL CHECK (price_per_min >= 0),   -- c_t
    price_per_km     NUMERIC(10, 2) NOT NULL CHECK (price_per_km >= 0),    -- c_d
    long_pickup_fee  NUMERIC(10, 2) NOT NULL CHECK (long_pickup_fee >= 0), -- доплата за дальнюю подачу
    active           BOOLEAN        NOT NULL DEFAULT TRUE
);

CREATE TABLE tariff_option (
    code    VARCHAR(32) PRIMARY KEY,
    name    VARCHAR(100)   NOT NULL,
    price   NUMERIC(10, 2) NOT NULL CHECK (price >= 0),
    active  BOOLEAN        NOT NULL DEFAULT TRUE
);

CREATE TABLE taxi_park (
    id    BIGSERIAL PRIMARY KEY,
    name  VARCHAR(200) NOT NULL UNIQUE
);

CREATE TABLE driver (
    id           BIGSERIAL PRIMARY KEY,
    full_name    VARCHAR(200)     NOT NULL,
    tariff_code  VARCHAR(32)      NOT NULL REFERENCES tariff (code),
    park_id      BIGINT           REFERENCES taxi_park (id),
    status       VARCHAR(16)      NOT NULL CHECK (status IN ('OFFLINE', 'FREE', 'BUSY')),
    lat          DOUBLE PRECISION CHECK (lat BETWEEN -90 AND 90),
    lon          DOUBLE PRECISION CHECK (lon BETWEEN -180 AND 180),
    -- свободный водитель обязан иметь координаты, иначе его нельзя учесть в спросе и подаче
    CONSTRAINT driver_free_has_location CHECK (status <> 'FREE' OR (lat IS NOT NULL AND lon IS NOT NULL))
);

CREATE INDEX driver_free_location_idx ON driver (lat, lon) WHERE status = 'FREE';

-- Пин — метка точки А при запросе цены, ещё до заказа (слайд 7).
CREATE TABLE demand_pin (
    id          BIGSERIAL PRIMARY KEY,
    cell_lat    INTEGER     NOT NULL,
    cell_lon    INTEGER     NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX demand_pin_cell_time_idx ON demand_pin (cell_lat, cell_lon, created_at);
CREATE INDEX demand_pin_time_idx ON demand_pin (created_at);

-- Предложение цены: цена фиксируется до поиска водителя (слайд 13).
CREATE TABLE price_quote (
    id                  UUID PRIMARY KEY,
    tariff_code         VARCHAR(32)      NOT NULL REFERENCES tariff (code),
    from_lat            DOUBLE PRECISION NOT NULL CHECK (from_lat BETWEEN -90 AND 90),
    from_lon            DOUBLE PRECISION NOT NULL CHECK (from_lon BETWEEN -180 AND 180),
    to_lat              DOUBLE PRECISION NOT NULL CHECK (to_lat BETWEEN -90 AND 90),
    to_lon              DOUBLE PRECISION NOT NULL CHECK (to_lon BETWEEN -180 AND 180),
    distance_km         NUMERIC(8, 2)    NOT NULL CHECK (distance_km >= 0),
    duration_min        NUMERIC(8, 1)    NOT NULL CHECK (duration_min >= 0),
    pins                INTEGER          NOT NULL CHECK (pins >= 0),
    free_cars           INTEGER          NOT NULL CHECK (free_cars >= 0),
    surge_k             NUMERIC(4, 2)    NOT NULL CHECK (surge_k BETWEEN 1 AND 3),
    pickup_km           NUMERIC(8, 2),
    pickup_min          NUMERIC(8, 1),
    supply_price        NUMERIC(10, 2)   NOT NULL,
    long_pickup_fee     NUMERIC(10, 2)   NOT NULL,
    ride_price          NUMERIC(10, 2)   NOT NULL,
    options_price       NUMERIC(10, 2)   NOT NULL,
    options             VARCHAR(32)[]    NOT NULL,
    total_exact         NUMERIC(12, 2)   NOT NULL,
    total               NUMERIC(12, 0)   NOT NULL CHECK (total >= 0),
    created_at          TIMESTAMPTZ      NOT NULL,
    expires_at          TIMESTAMPTZ      NOT NULL,
    CHECK (expires_at > created_at),
    CHECK (total_exact = supply_price + long_pickup_fee + ride_price + options_price)
);
