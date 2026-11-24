package ru.taksi.api;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.http.ProblemDetail;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Общие описания ошибок для Swagger, чтобы не повторять одно и то же в каждом методе. */
public final class ApiErrors {

    public static final String PROBLEM_JSON = "application/problem+json";

    private ApiErrors() {
    }

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @ApiResponse(responseCode = "401",
            description = "UNAUTHENTICATED — нет заголовка с id; UNKNOWN_PASSENGER / UNKNOWN_DRIVER — id не найден",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public @interface Unauthorized {
    }

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @ApiResponse(responseCode = "404",
            description = "ORDER_NOT_FOUND — заказа нет или он принадлежит другому пользователю",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public @interface OrderNotFound {
    }

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @ApiResponse(responseCode = "409",
            description = "INVALID_STATE_TRANSITION — действие недопустимо в текущем статусе заказа",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public @interface InvalidTransition {
    }
}
