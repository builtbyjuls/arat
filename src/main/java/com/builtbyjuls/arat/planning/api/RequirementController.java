package com.builtbyjuls.arat.planning.api;

import com.builtbyjuls.arat.identity.api.CurrentActor;
import com.builtbyjuls.arat.planning.application.PlanCreationService;
import com.builtbyjuls.arat.planning.application.RequirementReplacementService;
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
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/plans")
@Tag(name = "Plans")
public class RequirementController {

    private final CurrentActor currentActor;
    private final RequirementReplacementService requirementReplacementService;

    public RequirementController(CurrentActor currentActor, RequirementReplacementService requirementReplacementService) {
        this.currentActor = currentActor;
        this.requirementReplacementService = requirementReplacementService;
    }

    @PutMapping("/{planId}/requirements")
    @Operation(operationId = "replacePlanRequirements", summary = "Replace collaborative plan requirements")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Requirements replaced", headers = @Header(name = "ETag", description = "Current plan version"), content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = RequirementRepresentation.class))),
        @ApiResponse(responseCode = "400", description = "Malformed request or precondition", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class))),
        @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class))),
        @ApiResponse(responseCode = "403", description = "Organizer role required", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class))),
        @ApiResponse(responseCode = "404", description = "Private resource not found", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class))),
        @ApiResponse(responseCode = "409", description = "Plan is not collaborating", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class))),
        @ApiResponse(responseCode = "412", description = "Plan version precondition failed", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class))),
        @ApiResponse(responseCode = "422", description = "Validation failed", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class))),
        @ApiResponse(responseCode = "428", description = "Plan version precondition required", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class)))
    })
    public ResponseEntity<RequirementRepresentation> replace(
            @PathVariable UUID planId,
            @Valid @RequestBody RequirementReplacementRequest request,
            @Parameter(name = "If-Match", in = ParameterIn.HEADER, required = true)
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            HttpServletRequest servletRequest) {
        var actor = currentActor.requireAuthenticatedActor();
        var version = PlanVersionPrecondition.parseRequiredIfMatch(ifMatch);
        var requirements = requirementReplacementService.replace(new ReplaceRequirementsCommand(
                actor.accountId(),
                planId,
                version,
                request,
                (String) servletRequest.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE)));
        return ResponseEntity.ok().eTag(PlanCreationService.etag(version + 1)).body(requirements);
    }
}
