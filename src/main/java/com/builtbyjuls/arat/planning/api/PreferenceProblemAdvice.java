package com.builtbyjuls.arat.planning.api;

import com.builtbyjuls.arat.web.ApiProblemFactory;
import com.builtbyjuls.arat.web.ProblemResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = PreferenceController.class)
class PreferenceProblemAdvice {

    private final ApiProblemFactory problemFactory;
    private final ProblemResponseWriter responseWriter;

    PreferenceProblemAdvice(ApiProblemFactory problemFactory, ProblemResponseWriter responseWriter) {
        this.problemFactory = problemFactory;
        this.responseWriter = responseWriter;
    }

    @ExceptionHandler(PreferencePreconditionException.class)
    void preconditionFailure(PreferencePreconditionException exception, HttpServletRequest request, HttpServletResponse response) throws IOException {
        switch (exception.reason()) {
            case PRECONDITION_REQUIRED -> write(request, response, HttpStatus.PRECONDITION_REQUIRED,
                    "PRECONDITION_REQUIRED", "Precondition required", "Exactly one of If-None-Match or If-Match is required.");
            case INVALID_PRECONDITION -> write(request, response, HttpStatus.BAD_REQUEST,
                    "INVALID_PRECONDITION", "Invalid precondition", "Use If-None-Match: * or If-Match with a quoted positive integer, but not both.");
        }
    }

    @ExceptionHandler(PreferenceException.class)
    void preferenceFailure(PreferenceException exception, HttpServletRequest request, HttpServletResponse response) throws IOException {
        switch (exception.reason()) {
            case PRIVATE_RESOURCE_NOT_FOUND -> write(request, response, HttpStatus.NOT_FOUND,
                    "PRIVATE_RESOURCE_NOT_FOUND", "Private resource not found", "The requested private resource was not found.");
            case PREFERENCE_NOT_FOUND -> write(request, response, HttpStatus.NOT_FOUND,
                    "PREFERENCE_NOT_FOUND", "Preference not found", "No preference exists for this member on the plan.");
            case PRECONDITION_FAILED -> write(request, response, HttpStatus.PRECONDITION_FAILED,
                    "PRECONDITION_FAILED", "Precondition failed", "The preference no longer matches its supplied precondition.");
            case REQUIREMENT_VERSION_CHANGED -> write(request, response, HttpStatus.CONFLICT,
                    "REQUIREMENT_VERSION_CHANGED", "Requirement version changed", "The supplied basisPlanVersion no longer matches the plan.");
            case INVALID_PLAN_STATE -> write(request, response, HttpStatus.CONFLICT,
                    "INVALID_PLAN_STATE", "Invalid plan state", "Preferences can be changed only while collaborating or open for offers.");
            case VALIDATION_FAILED -> write(request, response, HttpStatus.UNPROCESSABLE_ENTITY,
                    "VALIDATION_FAILED", "Validation failed", "One or more fields are invalid.");
        }
    }

    private void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String code, String title, String detail) throws IOException {
        responseWriter.write(response, problemFactory.create(request, status, code, title, detail, List.of()));
    }
}
