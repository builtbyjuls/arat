package com.builtbyjuls.arat.web;

import java.net.URI;
import java.util.List;

public record ApiProblemResponse(
        URI type,
        String title,
        int status,
        String detail,
        URI instance,
        String code,
        String correlationId,
        List<ProblemViolation> violations) {
}
