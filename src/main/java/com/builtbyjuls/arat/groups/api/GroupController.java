package com.builtbyjuls.arat.groups.api;

import com.builtbyjuls.arat.groups.application.GroupCreationService;
import com.builtbyjuls.arat.groups.application.GroupQueryService;
import com.builtbyjuls.arat.identity.api.CurrentActor;
import com.builtbyjuls.arat.web.ApiProblemFactory;
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
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    private final GroupQueryService groupQueryService;
    private final ApiProblemFactory apiProblemFactory;

    public GroupController(
            CurrentActor currentActor,
            GroupCreationService groupCreationService,
            GroupQueryService groupQueryService,
            ApiProblemFactory apiProblemFactory) {
        this.currentActor = currentActor;
        this.groupCreationService = groupCreationService;
        this.groupQueryService = groupQueryService;
        this.apiProblemFactory = apiProblemFactory;
    }

    @GetMapping("/{groupId}")
    @Operation(operationId = "readGroup", summary = "Read private group details")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Private group details",
                headers = @Header(name = "ETag", description = "Current group version"),
                content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = GroupDetailRepresentation.class))),
        @ApiResponse(
                responseCode = "401",
                description = "Authentication required",
                content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class))),
        @ApiResponse(
                responseCode = "404",
                description = "Private resource not found",
                content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class)))
    })
    public ResponseEntity<?> read(
            @PathVariable UUID groupId,
            HttpServletRequest servletRequest) {
        var actor = currentActor.requireAuthenticatedActor();
        var group = groupQueryService.findPrivate(groupId, actor.accountId());
        if (group.isEmpty()) {
            ProblemDetail problem = apiProblemFactory.create(
                    servletRequest,
                    HttpStatus.NOT_FOUND,
                    "PRIVATE_RESOURCE_NOT_FOUND",
                    "Private resource not found",
                    "The requested private resource was not found.",
                    List.of());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(problem);
        }
        return ResponseEntity.ok()
                .eTag(GroupCreationService.etag(group.get().version()))
                .body(group.get());
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
