package com.builtbyjuls.arat.planning.api;

public final class PlanVersionPrecondition {

    private PlanVersionPrecondition() {
    }

    public static long parseRequiredIfMatch(String ifMatch) {
        if (ifMatch == null) {
            throw new PlanVersionPreconditionException(PlanVersionPreconditionException.Reason.PRECONDITION_REQUIRED);
        }
        if (!ifMatch.matches("\"[1-9][0-9]*\"")) {
            throw new PlanVersionPreconditionException(PlanVersionPreconditionException.Reason.INVALID_PRECONDITION);
        }
        try {
            return Long.parseLong(ifMatch.substring(1, ifMatch.length() - 1));
        } catch (NumberFormatException exception) {
            throw new PlanVersionPreconditionException(PlanVersionPreconditionException.Reason.INVALID_PRECONDITION);
        }
    }
}
