package com.builtbyjuls.arat.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.net.URI;
import java.util.List;

public record ApiProblemResponse(
        URI type,
        String title,
        int status,
        String detail,
        URI instance,
        @Schema(allowableValues = {
            "AUTHENTICATION_REQUIRED", "ACCESS_DENIED", "MALFORMED_REQUEST",
            "UNSUPPORTED_MEDIA_TYPE", "METHOD_NOT_ALLOWED", "ROUTE_NOT_FOUND",
            "VALIDATION_FAILED", "NOT_ACCEPTABLE", "INTERNAL_ERROR",
            "PRIVATE_RESOURCE_NOT_FOUND", "FORBIDDEN_ROLE", "FINAL_ORGANIZER_REQUIRED",
            "INVITATION_UNAVAILABLE", "ALREADY_MEMBER", "PRECONDITION_REQUIRED",
            "INVALID_PRECONDITION", "PRECONDITION_FAILED", "IDEMPOTENCY_KEY_REUSED",
            "INVALID_CURSOR", "INVALID_PLAN_STATE", "INVITATION_ALREADY_PENDING",
            "PREFERENCE_NOT_FOUND", "REQUIREMENT_VERSION_CHANGED", "ALREADY_ORGANIZER",
            "FORBIDDEN_PLATFORM_ROLE", "INVALID_PROVIDER_STATE",
            "FINALIZATION_VERSION_CHANGED", "NO_ELIGIBLE_PROVIDERS",
            "RECIPIENT_LIMIT_EXCEEDED", "REQUEST_DEADLINE_EXPIRED",
            "INVALID_REQUEST_STATE"
        })
        String code,
        String correlationId,
        List<ProblemViolation> violations) {
}
