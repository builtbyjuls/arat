package com.builtbyjuls.arat.marketplace.api;

public class ProviderPublishedRequestDetailException extends RuntimeException {

    public ProviderPublishedRequestDetailException() {
        super("PRIVATE_RESOURCE_NOT_FOUND");
    }
}
