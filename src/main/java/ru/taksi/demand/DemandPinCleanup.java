package ru.taksi.demand;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.taksi.config.TaksiProperties;

import java.time.Clock;
import java.time.Instant;

/** Не даёт таблице пинов расти бесконечно: удаляет пины старше pin-retention. */
@Component
public class DemandPinCleanup {

    private static final Logger log = LoggerFactory.getLogger(DemandPinCleanup.class);

    private final DemandPinRepository pins;
    private final TaksiProperties.Surge props;
    private final Clock clock;

    public DemandPinCleanup(DemandPinRepository pins, TaksiProperties properties, Clock clock) {
        this.pins = pins;
        this.props = properties.surge();
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT1M")
    @Transactional
    public void purgeOldPins() {
        int deleted = pins.deleteOlderThan(Instant.now(clock).minus(props.pinRetention()));
        if (deleted > 0) {
            log.info("Удалено устаревших пинов: {}", deleted);
        }
    }
}
