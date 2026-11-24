package ru.taksi.dispatch;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.ActiveProfiles;
import ru.taksi.support.TestClockConfig;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Регрессия (ревью этапа 6, находка 8): раунды подбора и очистка пинов идут по расписанию.
 * При одном потоке долгий раунд задерживает очистку, поэтому потоков должно быть не меньше двух.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestClockConfig.class)
class SchedulerPoolTest {

    @Autowired
    ThreadPoolTaskScheduler scheduler;

    @Test
    void schedulerHasAtLeastTwoThreads() {
        assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isGreaterThanOrEqualTo(2);
    }
}
