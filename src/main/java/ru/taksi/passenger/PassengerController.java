package ru.taksi.passenger;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.taksi.api.DomainException;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;

@RestController
@RequestMapping("/api/v1/passengers")
@Tag(name = "Пассажиры")
public class PassengerController {

    private final PassengerRepository passengers;
    private final Clock clock;

    public PassengerController(PassengerRepository passengers, Clock clock) {
        this.passengers = passengers;
        this.clock = clock;
    }

    @PostMapping
    @Transactional
    @Operation(summary = "Зарегистрировать пассажира",
            description = "Учебный MVP: полученный id передаётся в заголовке X-Passenger-Id. "
                    + "Это не аутентификация — настоящий вход появится отдельным этапом.")
    @ApiResponse(responseCode = "201", description = "Пассажир создан")
    @ApiResponse(responseCode = "400", description = "Ошибка валидации (имя, формат телефона E.164)",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "PHONE_TAKEN — телефон уже зарегистрирован",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<PassengerResponse> register(@Valid @RequestBody RegisterRequest request) {
        if (passengers.existsByPhone(request.phone())) {
            throw phoneTaken();
        }
        Passenger p;
        try {
            p = passengers.saveAndFlush(new Passenger(request.fullName().strip(), request.phone(), Instant.now(clock)));
        } catch (DataIntegrityViolationException e) {
            // одновременная регистрация того же телефона: проверку выше обогнал другой запрос
            throw phoneTaken();
        }
        return ResponseEntity.created(URI.create("/api/v1/passengers/" + p.getId()))
                .body(new PassengerResponse(p.getId(), p.getFullName(), p.getPhone(), p.getCreatedAt()));
    }

    private static DomainException phoneTaken() {
        return new DomainException(HttpStatus.CONFLICT, "PHONE_TAKEN", "Телефон уже зарегистрирован");
    }

    @Schema(example = """
            {"fullName":"Айгуль Асанова","phone":"+996555123456"}""")
    public record RegisterRequest(
            @NotBlank @Size(max = 200) String fullName,
            @NotBlank @Pattern(regexp = "^\\+[1-9][0-9]{7,14}$", message = "телефон в формате E.164, например +996555123456")
            String phone
    ) {
    }

    public record PassengerResponse(Long id, String fullName, String phone, Instant createdAt) {
    }
}
