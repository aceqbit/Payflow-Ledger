package com.payflow.ledger.api;

import com.payflow.ledger.ledger.LedgerException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.sql.SQLException;

/** RFC 9457 problem details. DB errors map to a retryable problem, never swallowed. */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(LedgerException.class)
    ResponseEntity<ProblemDetail> ledger(LedgerException e) {
        return problem(e.httpStatus(), e.code(), e.getMessage());
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ProblemDetail> db(DataAccessException e) {
        String state = sqlState(e);
        return switch (state == null ? "" : state) {
            case "55P03" -> problem(503, "LOCK_TIMEOUT", "could not acquire row locks in time, retry with the same key");
            case "40P01" -> problem(503, "DEADLOCK", "deadlock detected, retry with the same key");
            case "40001" -> problem(503, "SERIALIZATION_FAILURE", "serialization failure, retry with the same key");
            default -> throw e;
        };
    }

    static String sqlState(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SQLException s && s.getSQLState() != null) return s.getSQLState();
        }
        return null;
    }

    private static ResponseEntity<ProblemDetail> problem(int status, String code, String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(status), detail);
        p.setTitle(code);
        p.setProperty("code", code);
        return ResponseEntity.status(status).body(p);
    }
}
