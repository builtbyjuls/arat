package com.builtbyjuls.arat.groups.api;

import com.builtbyjuls.arat.web.ApiProblemFactory;
import com.builtbyjuls.arat.web.ProblemResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = GroupController.class)
class InvitationProblemAdvice {

    private final ApiProblemFactory problemFactory;
    private final ProblemResponseWriter responseWriter;

    InvitationProblemAdvice(ApiProblemFactory problemFactory, ProblemResponseWriter responseWriter) {
        this.problemFactory = problemFactory;
        this.responseWriter = responseWriter;
    }

    @ExceptionHandler(InvitationException.class)
    void invitationFailure(
            InvitationException exception, HttpServletRequest request, HttpServletResponse response) throws IOException {
        switch (exception.reason()) {
            case PRIVATE_RESOURCE_NOT_FOUND -> write(request, response, HttpStatus.NOT_FOUND,
                    "PRIVATE_RESOURCE_NOT_FOUND", "Private resource not found",
                    "The requested private resource was not found.");
            case FORBIDDEN_ROLE -> write(request, response, HttpStatus.FORBIDDEN,
                    "FORBIDDEN_ROLE", "Forbidden role", "An organizer role is required.");
            case VALIDATION_FAILED -> write(request, response, HttpStatus.UNPROCESSABLE_ENTITY,
                    "VALIDATION_FAILED", "Validation failed", "One or more fields are invalid.");
            case ALREADY_MEMBER -> write(request, response, HttpStatus.CONFLICT,
                    "ALREADY_MEMBER", "Already a member", "The account is already an active group member.");
            case INVITATION_ALREADY_PENDING -> write(request, response, HttpStatus.CONFLICT,
                    "INVITATION_ALREADY_PENDING", "Invitation already pending",
                    "An unexpired invitation is already pending for this account.");
            case INVITATION_UNAVAILABLE -> write(request, response, HttpStatus.NOT_FOUND,
                    "INVITATION_UNAVAILABLE", "Invitation unavailable", "The invitation is unavailable.");
        }
    }

    private void write(
            HttpServletRequest request, HttpServletResponse response, HttpStatus status,
            String code, String title, String detail) throws IOException {
        responseWriter.write(response, problemFactory.create(request, status, code, title, detail, List.of()));
    }
}
