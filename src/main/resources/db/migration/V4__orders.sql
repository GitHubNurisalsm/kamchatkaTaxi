-- Этап 3: пассажиры, заказы и журнал переходов статусов.
-- Только новые таблицы: существующие строки не затрагиваются, блокировок старых таблиц нет
-- (кроме кратковременной при создании внешних ключей на пустые таблицы).

CREATE TABLE passenger (
    id          BIGSERIAL PRIMARY KEY,
    full_name   VARCHAR(200) NOT NULL,
    phone       VARCHAR(16)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT passenger_phone_format CHECK (phone ~ '^\+[1-9][0-9]{7,14}$'),   -- E.164
    CONSTRAINT passenger_phone_unique UNIQUE (phone)
);

CREATE TABLE ride_order (
    id               UUID PRIMARY KEY,
    passenger_id     BIGINT      NOT NULL REFERENCES passenger (id),
    quote_id         UUID        NOT NULL REFERENCES price_quote (id),
    idempotency_key  VARCHAR(64) NOT NULL,
    status           VARCHAR(20) NOT NULL,
    driver_id        BIGINT      REFERENCES driver (id),
    cancelled_by     VARCHAR(16),
    created_at       TIMESTAMPTZ NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL,
    completed_at     TIMESTAMPTZ,
    cancelled_at     TIMESTAMPTZ,
    version          BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT ride_order_status_valid CHECK (status IN (
        'SEARCHING', 'DRIVER_ASSIGNED', 'DRIVER_EN_ROUTE', 'DRIVER_ARRIVED',
        'IN_PROGRESS', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT ride_order_cancelled_by_valid CHECK (cancelled_by IN ('PASSENGER', 'SYSTEM')),
    -- Цена фиксируется один раз: один quote — один заказ.
    CONSTRAINT ride_order_quote_unique UNIQUE (quote_id),
    -- Повтор запроса с тем же ключом не создаёт второй заказ.
    CONSTRAINT ride_order_idempotency_unique UNIQUE (passenger_id, idempotency_key),
    -- В поиске водителя нет; в статусах после назначения он обязателен.
    CONSTRAINT ride_order_searching_no_driver CHECK (status <> 'SEARCHING' OR driver_id IS NULL),
    CONSTRAINT ride_order_assigned_has_driver CHECK (
        status NOT IN ('DRIVER_ASSIGNED', 'DRIVER_EN_ROUTE', 'DRIVER_ARRIVED', 'IN_PROGRESS', 'COMPLETED')
        OR driver_id IS NOT NULL),
    CONSTRAINT ride_order_completed_at CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL)),
    CONSTRAINT ride_order_cancelled_at CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL AND cancelled_by IS NOT NULL))
);

-- Один активный заказ на пассажира.
CREATE UNIQUE INDEX ride_order_one_active_per_passenger ON ride_order (passenger_id)
    WHERE status IN ('SEARCHING', 'DRIVER_ASSIGNED', 'DRIVER_EN_ROUTE', 'DRIVER_ARRIVED', 'IN_PROGRESS');

-- Один активный заказ на водителя.
CREATE UNIQUE INDEX ride_order_one_active_per_driver ON ride_order (driver_id)
    WHERE status IN ('DRIVER_ASSIGNED', 'DRIVER_EN_ROUTE', 'DRIVER_ARRIVED', 'IN_PROGRESS');

-- История заказов пассажира (пагинация по дате) и очередь поиска для диспетчера (этап 4).
CREATE INDEX ride_order_passenger_created_idx ON ride_order (passenger_id, created_at DESC, id);
CREATE INDEX ride_order_searching_idx ON ride_order (created_at) WHERE status = 'SEARCHING';

-- Журнал переходов: аудит и данные для диспетчера (кто уже отказался от заказа).
CREATE TABLE order_event (
    id           BIGSERIAL PRIMARY KEY,
    order_id     UUID        NOT NULL REFERENCES ride_order (id),
    action       VARCHAR(20) NOT NULL,
    actor        VARCHAR(16) NOT NULL CHECK (actor IN ('PASSENGER', 'DRIVER', 'SYSTEM')),
    from_status  VARCHAR(20),
    to_status    VARCHAR(20) NOT NULL,
    driver_id    BIGINT      REFERENCES driver (id),
    created_at   TIMESTAMPTZ NOT NULL
);

CREATE INDEX order_event_order_idx ON order_event (order_id, id);
CREATE INDEX order_event_decline_idx ON order_event (order_id, driver_id) WHERE action = 'DECLINE';
