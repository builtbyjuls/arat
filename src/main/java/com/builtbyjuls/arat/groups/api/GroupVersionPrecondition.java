package com.builtbyjuls.arat.groups.api;

public final class GroupVersionPrecondition {

    private GroupVersionPrecondition() {
    }

    public static long parseRequiredIfMatch(String ifMatch) {
        if (ifMatch == null) {
            throw new GroupVersionPreconditionException(GroupVersionPreconditionException.Reason.PRECONDITION_REQUIRED);
        }
        if (!ifMatch.matches("\"[1-9][0-9]*\"")) {
            throw new GroupVersionPreconditionException(GroupVersionPreconditionException.Reason.INVALID_PRECONDITION);
        }
        try {
            return Long.parseLong(ifMatch.substring(1, ifMatch.length() - 1));
        } catch (NumberFormatException exception) {
            throw new GroupVersionPreconditionException(GroupVersionPreconditionException.Reason.INVALID_PRECONDITION);
        }
    }
}
