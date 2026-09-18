package com.builtbyjuls.arat.groups.api;

import com.builtbyjuls.arat.groups.application.GroupCreationService;
import com.builtbyjuls.arat.identity.api.CurrentActor;
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
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/groups")
@Validated
@Tag(name = "Groups")
public class GroupController {

    private final CurrentActor currentActor;
    private final GroupCreationService groupCreationService;

    public GroupController(CurrentActor currentActor, GroupCreationService groupCreationService) {
        this.currentActor = currentActor;
        this.groupCreationService = groupCreationService;
    }

    @PostMapping
    @Operation(operationId = "createGroup", summary = "Create a private group")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
        @ApiResponse(
                responseCode = "201",
                description = "Group created",
                headers = {
                    @Header(name = "ETag", description = "Current group version"),
                    @Header(name = "Location", description = "Created group location")
                },
                content = @Content(schema = @Schema(implementation = GroupRepresentation.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Missing or malformed request",
                content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class))),
        @ApiResponse(
                responseCode = "401",
                description = "Authentication required",
                content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class))),
        @ApiResponse(
                responseCode = "409",
                description = "Idempotency key reused",
                content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class))),
        @ApiResponse(
                responseCode = "422",
                description = "Validation failed",
                content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class)))
    })
    public ResponseEntity<GroupRepresentation> create(
            @Valid @RequestBody CreateGroupRequest request,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 255) String idempotencyKey,
            HttpServletRequest servletRequest) {
        var actor = currentActor.requireAuthenticatedActor();
        var group = groupCreationService.create(new CreateGroupCommand(
                actor.accountId(),
                idempotencyKey,
                request.name(),
                request.description(),
                (String) servletRequest.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE)));
        return ResponseEntity.created(URI.create(GroupCreationService.location(group.groupId())))
                .eTag(GroupCreationService.etag(group.version()))
                .body(group);
    }

    public record CreateGroupRequest(
            @NotBlank @Size(max = 80) String name,
            @Size(max = 500) String description) {
    }

}
