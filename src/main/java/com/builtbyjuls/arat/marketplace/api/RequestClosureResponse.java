package com.builtbyjuls.arat.marketplace.api;

public record RequestClosureResponse(
        int status,
        String etag,
        PublishedRequestRepresentation request) {
}
