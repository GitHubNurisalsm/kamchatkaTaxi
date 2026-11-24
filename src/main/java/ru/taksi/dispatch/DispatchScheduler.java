package ru.taksi.dispatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;
import ru.taksi.config.TaksiProperties;

/**
 * Буфер (слайд 8): заказы копятся в течение interval, затем распределяются одним раундом.
 * Выключается свойством taksi.dispatch.enabled=false (в тестах раунды запускаются вручную).
 */
@Component
@ConditionalOnProperty(prefix = "taksi.dispatch", name = "enabled", havingValue = "true")
public class DispatchScheduler implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(DispatchScheduler.class);

    private final DispatchService dispatch;
    private final TaksiProperties.Dispatch props;

    public DispatchScheduler(DispatchService dispatch, TaksiProperties properties) {
        this.dispatch = dispatch;
        this.props = properties.dispatch();
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(() -> {
            try {
                dispatch.runRound();
            } catch (RuntimeException e) {
                // ошибка одного раунда не должна останавливать подбор навсегда
                log.error("Раунд подбора завершился ошибкой", e);
            }
        }, props.interval());
    }
}
