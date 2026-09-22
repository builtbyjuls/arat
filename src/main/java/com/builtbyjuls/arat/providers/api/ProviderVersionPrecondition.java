package com.builtbyjuls.arat.providers.api;

public final class ProviderVersionPrecondition {

    private ProviderVersionPrecondition() {
    }

    public static long parseRequiredIfMatch(String ifMatch) {
        if (ifMatch == null) {
            throw new ProviderVersionPreconditionException(ProviderVersionPreconditionException.Reason.PRECONDITION_REQUIRED);
        }
        if (!ifMatch.matches("\"[1-9][0-9]*\"")) {
            throw new ProviderVersionPreconditionException(ProviderVersionPreconditionException.Reason.INVALID_PRECONDITION);
        }
        try {
            return Long.parseLong(ifMatch.substring(1, ifMatch.length() - 1));
        } catch (NumberFormatException exception) {
            throw new ProviderVersionPreconditionException(ProviderVersionPreconditionException.Reason.INVALID_PRECONDITION);
        }
    }
}
