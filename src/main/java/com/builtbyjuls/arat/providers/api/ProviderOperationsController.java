package com.builtbyjuls.arat.providers.api;

import com.builtbyjuls.arat.identity.api.CurrentActor;
import com.builtbyjuls.arat.providers.application.ProviderCreationService;
import com.builtbyjuls.arat.providers.application.ProviderVerificationDecisionService;
import com.builtbyjuls.arat.providers.domain.ProviderVerificationDecision;
import com.builtbyjuls.arat.web.ApiProblemResponse;
import com.builtbyjuls.arat.web.CorrelationIdFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/operations/providers")
@Validated
@Tag(name = "Provider Operations")
public class ProviderOperationsController {

    private final CurrentActor currentActor;
    private final ProviderVerificationDecisionService decisionService;

    public ProviderOperationsController(CurrentActor currentActor, ProviderVerificationDecisionService decisionService) {
        this.currentActor = currentActor;
        this.decisionService = decisionService;
    }

    @PostMapping("/{providerId}/verification-decisions")
    @Operation(operationId = "decideProviderVerification", summary = "Accept or reject a provider verification submission")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Provider verification decided", headers = @Header(name = "ETag", description = "Current provider version"), content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ProviderVerificationDecisionRepresentation.class))),
        @ApiResponse(responseCode = "400", description = "Missing or malformed request", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class))),
        @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class))),
        @ApiResponse(responseCode = "403", description = "Platform operator role required", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class))),
        @ApiResponse(responseCode = "404", description = "Private resource not found", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class))),
        @ApiResponse(responseCode = "409", description = "Provider state conflict or idempotency key reused", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class))),
        @ApiResponse(responseCode = "422", description = "Validation failed", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class)))
    })
    public ResponseEntity<ProviderVerificationDecisionRepresentation> decideVerification(
            @PathVariable UUID providerId,
            @Valid @RequestBody ProviderVerificationDecisionRequest request,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 255) String idempotencyKey,
            HttpServletRequest servletRequest) {
        var actor = currentActor.requireAuthenticatedActor();
        var decision = decisionService.decide(new DecideProviderVerificationCommand(
                actor.accountId(),
                actor.platformRoles(),
                providerId,
                request.submissionId(),
                idempotencyKey,
                request.decision(),
                request.note(),
                (String) servletRequest.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE)));
        return ResponseEntity.ok()
                .eTag(ProviderCreationService.etag(decision.providerVersion()))
                .body(decision);
    }

    public record ProviderVerificationDecisionRequest(
            @NotNull UUID submissionId,
            @NotNull ProviderVerificationDecision decision,
            @Size(max = 500) String note) {

        @AssertTrue(message = "note must contain from 1 to 500 characters after trimming")
        public boolean hasValidNote() {
            return note == null || (!note.strip().isEmpty() && note.strip().length() <= 500);
        }
    }
}
