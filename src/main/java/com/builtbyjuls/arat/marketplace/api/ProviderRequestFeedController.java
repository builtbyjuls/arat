package com.builtbyjuls.arat.marketplace.api;

import com.builtbyjuls.arat.identity.api.CurrentActor;
import com.builtbyjuls.arat.marketplace.application.ProviderRequestFeedService;
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
@RequestMapping("/api/v1/providers")
@Tag(name = "Published requests")
public class ProviderRequestFeedController {

    private final CurrentActor currentActor;
    private final ProviderRequestFeedService feedService;

    public ProviderRequestFeedController(CurrentActor currentActor, ProviderRequestFeedService feedService) {
        this.currentActor = currentActor;
        this.feedService = feedService;
    }

    @GetMapping("/{providerId}/request-feed")
    @Operation(operationId = "listProviderRequestFeed", summary = "List provider requests")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Provider request feed",
                content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = @Schema(implementation = ProviderRequestFeedRepresentation.class))),
        @ApiResponse(responseCode = "400", description = "Invalid cursor or limit",
                content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                        schema = @Schema(implementation = ApiProblemResponse.class))),
        @ApiResponse(responseCode = "401", description = "Authentication required",
                content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                        schema = @Schema(implementation = ApiProblemResponse.class))),
        @ApiResponse(responseCode = "404", description = "Private resource not found",
                content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                        schema = @Schema(implementation = ApiProblemResponse.class)))
    })
    public ProviderRequestFeedRepresentation list(
            @PathVariable UUID providerId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return feedService.list(providerId, currentActor.requireAuthenticatedActor().accountId(), cursor, limit);
    }
}
