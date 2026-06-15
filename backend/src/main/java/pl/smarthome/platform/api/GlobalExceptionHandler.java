package pl.smarthome.platform.api;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", "BAD_REQUEST");
        body.put("message", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", "VALIDATION_ERROR");
        body.put("message", "Błędna konfiguracja testu");
        var fieldErrors = new HashMap<String, String>();
        e.getBindingResult().getFieldErrors().forEach(err ->
                fieldErrors.put(err.getField(), err.getDefaultMessage()));
        body.put("fields", fieldErrors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception e) {
        log.error("Niespodziewany błąd", e);
        Map<String, Object> body = new HashMap<>();
        body.put("error", "INTERNAL_ERROR");
        body.put("message", e.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
