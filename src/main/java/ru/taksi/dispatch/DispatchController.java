package ru.taksi.dispatch;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.taksi.dispatch.DispatchService.DispatchReport;

@RestController
@RequestMapping("/api/v1/dispatch")
@Tag(name = "Диспетчер", description = "Шаг 4: буферный подбор водителей венгерским алгоритмом (слайд 8)")
public class DispatchController {

    private final DispatchService dispatch;

    public DispatchController(DispatchService dispatch) {
        this.dispatch = dispatch;
    }

    @PostMapping("/run")
    @Operation(summary = "Запустить раунд подбора сейчас",
            description = "Для демонстрации: обычно раунды идут сами каждые taksi.dispatch.interval. "
                    + "Отчёт показывает сумму подач по венгерскому алгоритму и для сравнения — жадного выбора. "
                    + "Учебный MVP: в реальной системе это служебный метод только для администратора.")
    @ApiResponse(responseCode = "200", description = "Отчёт раунда")
    public DispatchReport run() {
        return dispatch.runRound();
    }
}
