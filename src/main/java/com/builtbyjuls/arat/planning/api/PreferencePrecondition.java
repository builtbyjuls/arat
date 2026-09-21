package com.builtbyjuls.arat.planning.api;

public final class PreferencePrecondition {

    private PreferencePrecondition() {
    }

    public static Precondition parse(String ifNoneMatch, String ifMatch) {
        if (ifNoneMatch == null && ifMatch == null) {
            throw new PreferencePreconditionException(PreferencePreconditionException.Reason.PRECONDITION_REQUIRED);
        }
        if (ifNoneMatch != null && ifMatch != null) {
            throw new PreferencePreconditionException(PreferencePreconditionException.Reason.INVALID_PRECONDITION);
        }
        if (ifNoneMatch != null) {
            if (!"*".equals(ifNoneMatch)) {
                throw new PreferencePreconditionException(PreferencePreconditionException.Reason.INVALID_PRECONDITION);
            }
            return Precondition.create();
        }
        if (!ifMatch.matches("\"[1-9][0-9]*\"")) {
            throw new PreferencePreconditionException(PreferencePreconditionException.Reason.INVALID_PRECONDITION);
        }
        try {
            return Precondition.replace(Long.parseLong(ifMatch.substring(1, ifMatch.length() - 1)));
        } catch (NumberFormatException exception) {
            throw new PreferencePreconditionException(PreferencePreconditionException.Reason.INVALID_PRECONDITION);
        }
    }

    public record Precondition(Long expectedPreferenceVersion) {

        private static Precondition create() {
            return new Precondition(null);
        }

        private static Precondition replace(long expectedPreferenceVersion) {
            return new Precondition(expectedPreferenceVersion);
        }

        public boolean isCreate() {
            return expectedPreferenceVersion == null;
        }
    }
}
