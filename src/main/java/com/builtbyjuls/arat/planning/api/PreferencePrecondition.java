package com.builtbyjuls.arat.planning.api;

public final class PreferencePrecondition {

    private PreferencePrecondition() {
    }

    public static void requireIfNoneMatchStar(String ifNoneMatch) {
        if (ifNoneMatch == null) {
            throw new PreferencePreconditionException(PreferencePreconditionException.Reason.PRECONDITION_REQUIRED);
        }
        if (!"*".equals(ifNoneMatch)) {
            throw new PreferencePreconditionException(PreferencePreconditionException.Reason.INVALID_PRECONDITION);
        }
    }
}
