-- Этап 4: индексы для диспетчера. Схема данных не меняется.

-- Поиск просроченных предложений (DRIVER_ASSIGNED дольше offer-timeout).
-- updated_at в статусе DRIVER_ASSIGNED = момент назначения: до ответа водителя заказ не меняется.
CREATE INDEX ride_order_assigned_idx ON ride_order (updated_at) WHERE status = 'DRIVER_ASSIGNED';

-- Пары «заказ — водитель», которые больше не предлагаем: отказ и пропущенное предложение.
DROP INDEX order_event_decline_idx;
CREATE INDEX order_event_rejected_pair_idx ON order_event (order_id, driver_id)
    WHERE action IN ('DECLINE', 'OFFER_TIMEOUT');
