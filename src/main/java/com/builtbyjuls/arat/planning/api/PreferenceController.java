package com.builtbyjuls.arat.planning.api;

import com.builtbyjuls.arat.identity.api.CurrentActor;
import com.builtbyjuls.arat.planning.application.PlanCreationService;
import com.builtbyjuls.arat.planning.application.PlanPreferenceService;
import com.builtbyjuls.arat.web.ApiProblemResponse;
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
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/plans")
@Tag(name = "Plans")
public class PreferenceController {

    private final CurrentActor currentActor;
    private final PlanPreferenceService preferenceService;

    public PreferenceController(CurrentActor currentActor, PlanPreferenceService preferenceService) {
        this.currentActor = currentActor;
        this.preferenceService = preferenceService;
    }

    @PutMapping("/{planId}/members/me/preference")
    @Operation(
            operationId = "putMemberPreference",
            summary = "Create or replace a member preference",
            description = "Use exactly one precondition: If-None-Match: * for creation or If-Match with the quoted preference ETag for replacement.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Preference replaced", headers = @Header(name = "ETag", description = "New preference version"), content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = PreferenceRepresentation.class))),
        @ApiResponse(responseCode = "201", description = "Preference created", headers = {
            @Header(name = "ETag", description = "Current preference version"),
            @Header(name = "Location", description = "Created preference location")
        }, content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = PreferenceRepresentation.class))),
        @ApiResponse(responseCode = "400", description = "Malformed request or precondition", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class))),
        @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class))),
        @ApiResponse(responseCode = "404", description = "Private resource not found", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class))),
        @ApiResponse(responseCode = "409", description = "Plan state or requirement version changed", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class))),
        @ApiResponse(responseCode = "412", description = "Preference version precondition failed", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class))),
        @ApiResponse(responseCode = "422", description = "Validation failed", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class))),
        @ApiResponse(responseCode = "428", description = "Exactly one preference precondition required", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class)))
    })
    public ResponseEntity<PreferenceRepresentation> create(
            @PathVariable UUID planId,
            @Valid @RequestBody CreatePreferenceRequest request,
            @Parameter(name = "If-None-Match", description = "Use * only when creating a preference.", in = ParameterIn.HEADER)
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch,
            @Parameter(name = "If-Match", description = "Use the quoted preference ETag only when replacing a preference.", in = ParameterIn.HEADER)
            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        var precondition = PreferencePrecondition.parse(ifNoneMatch, ifMatch);
        var actor = currentActor.requireAuthenticatedActor();
        var preference = precondition.isCreate()
                ? preferenceService.create(new CreatePreferenceCommand(actor.accountId(), planId, request))
                : preferenceService.replace(new ReplacePreferenceCommand(actor.accountId(), planId, precondition.expectedPreferenceVersion(), request));
        return precondition.isCreate()
                ? ResponseEntity.status(HttpStatus.CREATED)
                        .eTag(PlanCreationService.etag(preference.version()))
                        .header("Location", "/api/v1/plans/" + planId + "/members/me/preference")
                        .body(preference)
                : ResponseEntity.ok()
                        .eTag(PlanCreationService.etag(preference.version()))
                        .body(preference);
    }

    @GetMapping("/{planId}/members/me/preference")
    @Operation(operationId = "readOwnMemberPreference", summary = "Read own member preference")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Member preference", headers = @Header(name = "ETag", description = "Current preference version"), content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = PreferenceRepresentation.class))),
        @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class))),
        @ApiResponse(responseCode = "404", description = "Private resource or preference not found", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class)))
    })
    public ResponseEntity<PreferenceRepresentation> readOwn(@PathVariable UUID planId) {
        var preference = preferenceService.findOwn(planId, currentActor.requireAuthenticatedActor().accountId());
        return ResponseEntity.ok().eTag(PlanCreationService.etag(preference.version())).body(preference);
    }

    @GetMapping("/{planId}/preferences")
    @Operation(operationId = "listPlanPreferences", summary = "List group-visible plan preferences")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Group-visible preferences", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = PreferenceCollectionRepresentation.class))),
        @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class))),
        @ApiResponse(responseCode = "404", description = "Private resource not found", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class)))
    })
    public PreferenceCollectionRepresentation list(@PathVariable UUID planId) {
        return preferenceService.findAll(planId, currentActor.requireAuthenticatedActor().accountId());
    }
}
