package com.builtbyjuls.arat.marketplace.api;

public record RequestPublicationResponse(
        int status,
        String etag,
        String location,
        PublishedRequestRepresentation request) {
}
