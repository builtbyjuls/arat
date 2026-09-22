package com.builtbyjuls.arat.providers.api;

import com.builtbyjuls.arat.web.ApiProblemFactory;
import com.builtbyjuls.arat.web.ProblemResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {ProviderController.class, ProviderOperationsController.class})
class ProviderProfileProblemAdvice {

    private final ApiProblemFactory problemFactory;
    private final ProblemResponseWriter responseWriter;

    ProviderProfileProblemAdvice(ApiProblemFactory problemFactory, ProblemResponseWriter responseWriter) {
        this.problemFactory = problemFactory;
        this.responseWriter = responseWriter;
    }

    @ExceptionHandler(ProviderVersionPreconditionException.class)
    void preconditionFailure(
            ProviderVersionPreconditionException exception,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        switch (exception.reason()) {
            case PRECONDITION_REQUIRED -> write(request, response, HttpStatus.PRECONDITION_REQUIRED,
                    "PRECONDITION_REQUIRED", "Precondition required", "The If-Match header is required.");
            case INVALID_PRECONDITION -> write(request, response, HttpStatus.BAD_REQUEST,
                    "INVALID_PRECONDITION", "Invalid precondition", "The If-Match header must be a quoted positive integer.");
        }
    }

    @ExceptionHandler(ProviderProfileException.class)
    void profileFailure(
            ProviderProfileException exception,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        switch (exception.reason()) {
            case PRIVATE_RESOURCE_NOT_FOUND -> write(request, response, HttpStatus.NOT_FOUND,
                    "PRIVATE_RESOURCE_NOT_FOUND", "Private resource not found", "The requested private resource was not found.");
            case FORBIDDEN_ROLE -> write(request, response, HttpStatus.FORBIDDEN,
                    "FORBIDDEN_ROLE", "Forbidden role", "An administrator role is required.");
            case PRECONDITION_FAILED -> write(request, response, HttpStatus.PRECONDITION_FAILED,
                    "PRECONDITION_FAILED", "Precondition failed", "The provider version no longer matches If-Match.");
        }
    }

    @ExceptionHandler(ProviderVerificationException.class)
    void verificationFailure(
            ProviderVerificationException exception,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        switch (exception.reason()) {
            case PRIVATE_RESOURCE_NOT_FOUND -> write(request, response, HttpStatus.NOT_FOUND,
                    "PRIVATE_RESOURCE_NOT_FOUND", "Private resource not found", "The requested private resource was not found.");
            case FORBIDDEN_ROLE -> write(request, response, HttpStatus.FORBIDDEN,
                    "FORBIDDEN_ROLE", "Forbidden role", "An administrator role is required.");
            case INVALID_PROVIDER_STATE -> write(request, response, HttpStatus.CONFLICT,
                    "INVALID_PROVIDER_STATE", "Invalid provider state", "The provider cannot submit verification evidence in its current state.");
        }
    }

    @ExceptionHandler(ProviderVerificationDecisionException.class)
    void verificationDecisionFailure(
            ProviderVerificationDecisionException exception,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        switch (exception.reason()) {
            case FORBIDDEN_PLATFORM_ROLE -> write(request, response, HttpStatus.FORBIDDEN,
                    "FORBIDDEN_PLATFORM_ROLE", "Forbidden platform role", "A platform operator role is required.");
            case PRIVATE_RESOURCE_NOT_FOUND -> write(request, response, HttpStatus.NOT_FOUND,
                    "PRIVATE_RESOURCE_NOT_FOUND", "Private resource not found", "The requested private resource was not found.");
            case INVALID_PROVIDER_STATE -> write(request, response, HttpStatus.CONFLICT,
                    "INVALID_PROVIDER_STATE", "Invalid provider state", "The provider cannot be decided in its current state.");
        }
    }

    @ExceptionHandler(ProviderSuspensionException.class)
    void suspensionFailure(
            ProviderSuspensionException exception,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        switch (exception.reason()) {
            case FORBIDDEN_PLATFORM_ROLE -> write(request, response, HttpStatus.FORBIDDEN,
                    "FORBIDDEN_PLATFORM_ROLE", "Forbidden platform role", "A platform operator role is required.");
            case PRIVATE_RESOURCE_NOT_FOUND -> write(request, response, HttpStatus.NOT_FOUND,
                    "PRIVATE_RESOURCE_NOT_FOUND", "Private resource not found", "The requested private resource was not found.");
            case INVALID_PROVIDER_STATE -> write(request, response, HttpStatus.CONFLICT,
                    "INVALID_PROVIDER_STATE", "Invalid provider state", "The provider cannot be suspended in its current state.");
        }
    }

    @ExceptionHandler(ProviderRestorationException.class)
    void restorationFailure(
            ProviderRestorationException exception,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        switch (exception.reason()) {
            case FORBIDDEN_PLATFORM_ROLE -> write(request, response, HttpStatus.FORBIDDEN,
                    "FORBIDDEN_PLATFORM_ROLE", "Forbidden platform role", "A platform operator role is required.");
            case PRIVATE_RESOURCE_NOT_FOUND -> write(request, response, HttpStatus.NOT_FOUND,
                    "PRIVATE_RESOURCE_NOT_FOUND", "Private resource not found", "The requested private resource was not found.");
            case INVALID_PROVIDER_STATE -> write(request, response, HttpStatus.CONFLICT,
                    "INVALID_PROVIDER_STATE", "Invalid provider state", "The provider cannot be restored in its current state.");
        }
    }

    private void write(
            HttpServletRequest request,
            HttpServletResponse response,
            HttpStatus status,
            String code,
            String title,
            String detail) throws IOException {
        responseWriter.write(response, problemFactory.create(request, status, code, title, detail, List.of()));
    }
}
