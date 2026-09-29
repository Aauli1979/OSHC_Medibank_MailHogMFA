package au.edu.oshc.smartguide;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Ensures unhandled backend errors are logged with a full stack trace and
 * return a JSON body with a real "message" field instead of Spring's blank
 * default error page. This makes future issues visible in the UI instead of
 * a generic "Request failed (500)".
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    ResponseEntity<?> handleStatus(org.springframework.web.server.ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode())
                .body(Map.of("message", ex.getReason() == null ? "Request failed." : ex.getReason()));
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    ResponseEntity<?> handleInvalidBody(Exception ex) {
        return ResponseEntity.badRequest().body(Map.of("message", "Invalid request body."));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<?> handleUnexpected(Exception ex) {
        log.error("Unhandled error while processing request", ex);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", "An unexpected server error occurred. Please try again."));
    }
}