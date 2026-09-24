package com.builtbyjuls.arat.planning.api;

import com.builtbyjuls.arat.identity.api.CurrentActor;
import com.builtbyjuls.arat.planning.application.PlanCreationService;
import com.builtbyjuls.arat.planning.application.RequirementFinalizationService;
import com.builtbyjuls.arat.planning.application.RequirementFinalizationQueryService;
import com.builtbyjuls.arat.web.ApiProblemResponse;
import com.builtbyjuls.arat.web.CorrelationIdFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/plans")
@Validated
@Tag(name = "Plans")
public class RequirementFinalizationController {
    private final CurrentActor currentActor;
    private final RequirementFinalizationService finalizationService;
    private final RequirementFinalizationQueryService finalizationQueryService;
    public RequirementFinalizationController(
            CurrentActor currentActor,
            RequirementFinalizationService finalizationService,
            RequirementFinalizationQueryService finalizationQueryService) {
        this.currentActor = currentActor;
        this.finalizationService = finalizationService;
        this.finalizationQueryService = finalizationQueryService;
    }

    @GetMapping("/{planId}/requirement-finalizations")
    @Operation(operationId = "listRequirementFinalizations", summary = "List private requirement finalizations")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Requirement finalization history", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = RequirementFinalizationPageRepresentation.class))),
        @ApiResponse(responseCode = "400", description = "Invalid cursor or limit", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class))),
        @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class))),
        @ApiResponse(responseCode = "404", description = "Private resource not found", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class)))
    })
    public RequirementFinalizationPageRepresentation list(
            @PathVariable UUID planId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return finalizationQueryService.listHistory(
                planId, currentActor.requireAuthenticatedActor().accountId(), cursor, limit);
    }
    @PostMapping("/{planId}/requirement-finalization")
    @Operation(operationId = "finalizePlanRequirements", summary = "Finalize provider-publishable requirements")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Requirements finalized", headers = {@Header(name = "ETag", description = "Unchanged plan version"), @Header(name = "Location", description = "Finalization resource")}, content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = RequirementFinalizationRepresentation.class))),
        @ApiResponse(responseCode = "400", description = "Malformed request or precondition", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class))),
        @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class))),
        @ApiResponse(responseCode = "403", description = "Organizer role required", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class))),
        @ApiResponse(responseCode = "404", description = "Private resource not found", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class))),
        @ApiResponse(responseCode = "409", description = "Idempotency key reused or invalid plan state", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class))),
        @ApiResponse(responseCode = "412", description = "Plan version precondition failed", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class))),
        @ApiResponse(responseCode = "422", description = "Validation failed", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class))),
        @ApiResponse(responseCode = "428", description = "Plan version precondition required", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class)))})
    public ResponseEntity<RequirementFinalizationRepresentation> finalizeRequirements(
            @PathVariable UUID planId, @Valid @RequestBody FinalizeRequirementsRequest request,
            @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER, required = true) @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 255) String idempotencyKey,
            @Parameter(name = "If-Match", in = ParameterIn.HEADER, required = true) @RequestHeader(value = "If-Match", required = false) String ifMatch,
            HttpServletRequest servletRequest) {
        var actor = currentActor.requireAuthenticatedActor();
        var finalization = finalizationService.finalizeRequirements(new FinalizeRequirementsCommand(actor.accountId(), planId, PlanVersionPrecondition.parseRequiredIfMatch(ifMatch), request, idempotencyKey, (String) servletRequest.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE)));
        return ResponseEntity.created(URI.create("/api/v1/plans/" + planId + "/requirement-finalization/" + finalization.finalizationId()))
                .eTag(PlanCreationService.etag(finalization.basisPlanVersion())).body(finalization);
    }
}
