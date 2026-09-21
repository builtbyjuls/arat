package com.builtbyjuls.arat.planning.api;

import com.builtbyjuls.arat.identity.api.CurrentActor;
import com.builtbyjuls.arat.planning.application.PlanCreationService;
import com.builtbyjuls.arat.planning.application.PlanQueryService;
import com.builtbyjuls.arat.web.ApiProblemResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Plans")
public class PlanQueryController {

    private final CurrentActor currentActor;
    private final PlanQueryService planQueryService;

    public PlanQueryController(CurrentActor currentActor, PlanQueryService planQueryService) {
        this.currentActor = currentActor;
        this.planQueryService = planQueryService;
    }

    @GetMapping("/plans/{planId}")
    @Operation(operationId = "readPlan", summary = "Read private plan details")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Private plan details", headers = @Header(name = "ETag", description = "Current plan version"), content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = PlanDetailRepresentation.class))),
        @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class))),
        @ApiResponse(responseCode = "404", description = "Private resource not found", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class)))
    })
    public ResponseEntity<PlanDetailRepresentation> read(@PathVariable UUID planId) {
        var plan = planQueryService.findPrivate(planId, currentActor.requireAuthenticatedActor().accountId());
        return ResponseEntity.ok().eTag(PlanCreationService.etag(plan.version())).body(plan);
    }

    @GetMapping("/groups/{groupId}/plans")
    @Operation(operationId = "listPlans", summary = "List private group plans")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Private plan page", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = PlanPageRepresentation.class))),
        @ApiResponse(responseCode = "400", description = "Invalid cursor or limit", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class))),
        @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class))),
        @ApiResponse(responseCode = "404", description = "Private resource not found", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class)))
    })
    public PlanPageRepresentation list(@PathVariable UUID groupId, @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
        return planQueryService.listPrivate(groupId, currentActor.requireAuthenticatedActor().accountId(), cursor, limit);
    }
}
