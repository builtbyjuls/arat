package com.builtbyjuls.arat.providers.api;

import com.builtbyjuls.arat.identity.api.CurrentActor;
import com.builtbyjuls.arat.providers.application.ProviderCreationService;
import com.builtbyjuls.arat.providers.domain.ProviderCategory;
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
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/providers")
@Validated
@Tag(name = "Providers")
public class ProviderController {

    private final CurrentActor currentActor;
    private final ProviderCreationService providerCreationService;

    public ProviderController(CurrentActor currentActor, ProviderCreationService providerCreationService) {
        this.currentActor = currentActor;
        this.providerCreationService = providerCreationService;
    }

    @PostMapping
    @Operation(operationId = "createProvider", summary = "Create a provider organization")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
        @ApiResponse(
                responseCode = "201",
                description = "Provider organization created",
                headers = {
                    @Header(name = "ETag", description = "Current provider version"),
                    @Header(name = "Location", description = "Created provider location")
                },
                content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ProviderRepresentation.class))),
        @ApiResponse(responseCode = "400", description = "Missing or malformed request", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class))),
        @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class))),
        @ApiResponse(responseCode = "409", description = "Idempotency key reused", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class))),
        @ApiResponse(responseCode = "422", description = "Validation failed", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class)))
    })
    public ResponseEntity<ProviderRepresentation> create(
            @Valid @RequestBody CreateProviderRequest request,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 255) String idempotencyKey,
            HttpServletRequest servletRequest) {
        var actor = currentActor.requireAuthenticatedActor();
        var provider = providerCreationService.create(new CreateProviderCommand(
                actor.accountId(),
                idempotencyKey,
                request.displayName(),
                request.supportedCategories(),
                request.serviceAreaCodes(),
                (String) servletRequest.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE)));
        return ResponseEntity.created(URI.create(ProviderCreationService.location(provider.providerId())))
                .eTag(ProviderCreationService.etag(provider.version()))
                .body(provider);
    }

    public record CreateProviderRequest(
            @NotBlank String displayName,
            @NotEmpty @Size(max = 10) List<@NotNull ProviderCategory> supportedCategories,
            @NotEmpty @Size(max = 20) List<@NotBlank String> serviceAreaCodes) {

        @AssertTrue(message = "displayName must be at most 120 characters after trimming")
        public boolean hasValidDisplayNameLength() {
            return displayName == null || displayName.trim().length() <= 120;
        }

        @AssertTrue(message = "supportedCategories must be unique")
        public boolean hasUniqueSupportedCategories() {
            return supportedCategories == null || supportedCategories.stream().distinct().count() == supportedCategories.size();
        }

        @AssertTrue(message = "serviceAreaCodes must be unique after trimming")
        public boolean hasUniqueServiceAreaCodes() {
            return serviceAreaCodes == null || serviceAreaCodes.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(String::trim)
                    .distinct()
                    .count() == serviceAreaCodes.size();
        }

        @AssertTrue(message = "serviceAreaCodes must be at most 64 characters after trimming")
        public boolean hasValidServiceAreaCodeLengths() {
            return serviceAreaCodes == null || serviceAreaCodes.stream()
                    .filter(java.util.Objects::nonNull)
                    .allMatch(areaCode -> areaCode.trim().length() <= 64);
        }
    }
}
