package com.builtbyjuls.arat.marketplace.api;

import com.builtbyjuls.arat.identity.api.CurrentActor;
import com.builtbyjuls.arat.marketplace.application.ProviderPublishedRequestDetailService;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/providers")
@Tag(name = "Published requests")
public class ProviderPublishedRequestDetailController {

    private final CurrentActor currentActor;
    private final ProviderPublishedRequestDetailService detailService;

    public ProviderPublishedRequestDetailController(
            CurrentActor currentActor, ProviderPublishedRequestDetailService detailService) {
        this.currentActor = currentActor;
        this.detailService = detailService;
    }

    @GetMapping("/{providerId}/published-requests/{requestId}")
    @Operation(operationId = "readProviderPublishedRequest", summary = "Read a provider request")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Provider request",
                content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = @Schema(implementation = PublishedRequestRepresentation.class))),
        @ApiResponse(responseCode = "401", description = "Authentication required",
                content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                        schema = @Schema(implementation = ApiProblemResponse.class))),
        @ApiResponse(responseCode = "404", description = "Private resource not found",
                content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                        schema = @Schema(implementation = ApiProblemResponse.class)))
    })
    public PublishedRequestRepresentation detail(@PathVariable UUID providerId, @PathVariable UUID requestId) {
        return detailService.find(providerId, requestId, currentActor.requireAuthenticatedActor().accountId());
    }
}
