package com.builtbyjuls.arat.providers.api;

import com.builtbyjuls.arat.identity.api.CurrentActor;
import com.builtbyjuls.arat.providers.application.ProviderCreationService;
import com.builtbyjuls.arat.providers.application.ProviderProfileService;
import com.builtbyjuls.arat.providers.domain.ProviderCategory;
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
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
    private final ProviderProfileService providerProfileService;

    public ProviderController(
            CurrentActor currentActor,
            ProviderCreationService providerCreationService,
            ProviderProfileService providerProfileService) {
        this.currentActor = currentActor;
        this.providerCreationService = providerCreationService;
        this.providerProfileService = providerProfileService;
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

    @GetMapping("/{providerId}")
    @Operation(operationId = "readProvider", summary = "Read a provider organization")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Provider organization", headers = @Header(name = "ETag", description = "Current provider version"), content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ProviderRepresentation.class))),
        @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class))),
        @ApiResponse(responseCode = "404", description = "Private resource not found", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class)))
    })
    public ResponseEntity<ProviderRepresentation> read(@PathVariable UUID providerId) {
        var actor = currentActor.requireAuthenticatedActor();
        var provider = providerProfileService.read(providerId, actor.accountId());
        return ResponseEntity.ok().eTag(ProviderCreationService.etag(provider.version())).body(provider);
    }

    @PutMapping("/{providerId}/profile")
    @Operation(operationId = "replaceProviderProfile", summary = "Replace a provider organization profile")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Provider profile replaced", headers = @Header(name = "ETag", description = "Current provider version"), content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ProviderRepresentation.class))),
        @ApiResponse(responseCode = "400", description = "Malformed request or precondition", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class))),
        @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class))),
        @ApiResponse(responseCode = "403", description = "Administrator role required", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class))),
        @ApiResponse(responseCode = "404", description = "Private resource not found", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class))),
        @ApiResponse(responseCode = "412", description = "Provider version precondition failed", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class))),
        @ApiResponse(responseCode = "422", description = "Validation failed", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class))),
        @ApiResponse(responseCode = "428", description = "Provider version precondition required", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiProblemResponse.class)))
    })
    public ResponseEntity<ProviderRepresentation> replace(
            @PathVariable UUID providerId,
            @Valid @RequestBody CreateProviderRequest request,
            @Parameter(name = "If-Match", in = ParameterIn.HEADER, required = true)
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            HttpServletRequest servletRequest) {
        var actor = currentActor.requireAuthenticatedActor();
        var version = ProviderVersionPrecondition.parseRequiredIfMatch(ifMatch);
        var provider = providerProfileService.replace(new ReplaceProviderProfileCommand(
                actor.accountId(),
                providerId,
                version,
                request.displayName(),
                request.supportedCategories(),
                request.serviceAreaCodes(),
                (String) servletRequest.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE)));
        return ResponseEntity.ok().eTag(ProviderCreationService.etag(provider.version())).body(provider);
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
