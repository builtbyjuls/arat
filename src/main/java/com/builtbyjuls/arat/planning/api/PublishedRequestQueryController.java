package com.builtbyjuls.arat.planning.api;

import com.builtbyjuls.arat.identity.api.CurrentActor;
import com.builtbyjuls.arat.planning.application.PublishedRequestQueryService;
import com.builtbyjuls.arat.web.ApiProblemResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/plans")
@Tag(name = "Published requests")
public class PublishedRequestQueryController {

    private final CurrentActor currentActor;
    private final PublishedRequestQueryService queryService;

    public PublishedRequestQueryController(CurrentActor currentActor, PublishedRequestQueryService queryService) {
        this.currentActor = currentActor;
        this.queryService = queryService;
    }

    @GetMapping("/{planId}/published-requests/current")
    @Operation(operationId = "readCurrentGroupPublishedRequest", summary = "Read the current group provider request")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Current provider request", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = GroupPublishedRequestRepresentation.class))),
        @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class))),
        @ApiResponse(responseCode = "404", description = "Private resource not found", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class)))
    })
    public GroupPublishedRequestRepresentation current(@PathVariable UUID planId) {
        return queryService.findCurrent(planId, currentActor.requireAuthenticatedActor().accountId());
    }

    @GetMapping("/{planId}/published-requests/{requestId}")
    @Operation(operationId = "readGroupPublishedRequestHistoryItem", summary = "Read a group provider request version")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Provider request version", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = GroupPublishedRequestRepresentation.class))),
        @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class))),
        @ApiResponse(responseCode = "404", description = "Private resource not found", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class)))
    })
    public GroupPublishedRequestRepresentation detail(@PathVariable UUID planId, @PathVariable UUID requestId) {
        return queryService.findHistoryItem(planId, requestId, currentActor.requireAuthenticatedActor().accountId());
    }

    @GetMapping("/{planId}/published-requests")
    @Operation(operationId = "listGroupPublishedRequestHistory", summary = "List group provider request history")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Provider request history", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = PublishedRequestPageRepresentation.class))),
        @ApiResponse(responseCode = "400", description = "Invalid cursor or limit", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class))),
        @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class))),
        @ApiResponse(responseCode = "404", description = "Private resource not found", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class)))
    })
    public PublishedRequestPageRepresentation history(
            @PathVariable UUID planId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return queryService.listHistory(planId, currentActor.requireAuthenticatedActor().accountId(), cursor, limit);
    }
}
