package com.builtbyjuls.arat.groups.api;

import com.builtbyjuls.arat.web.ApiProblemFactory;
import com.builtbyjuls.arat.web.ProblemResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = GroupController.class)
class GroupIndexProblemAdvice {

    private final ApiProblemFactory problemFactory;
    private final ProblemResponseWriter responseWriter;

    GroupIndexProblemAdvice(ApiProblemFactory problemFactory, ProblemResponseWriter responseWriter) {
        this.problemFactory = problemFactory;
        this.responseWriter = responseWriter;
    }

    @ExceptionHandler(GroupIndexException.class)
    void invalidCursor(HttpServletRequest request, HttpServletResponse response) throws IOException {
        responseWriter.write(response, problemFactory.create(
                request,
                HttpStatus.BAD_REQUEST,
                "INVALID_CURSOR",
                "Invalid cursor",
                "The cursor is invalid.",
                List.of()));
    }
}
