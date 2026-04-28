package com.finsafe.idempotency.controller;

import com.finsafe.idempotency.dto.ErrorResponse;
import com.finsafe.idempotency.dto.PaymentRequest;
import com.finsafe.idempotency.dto.PaymentResponse;
import com.finsafe.idempotency.service.IdempotencyService;
import com.finsafe.idempotency.service.IdempotencyService.IdempotencyConflictException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST Controller for payment processing with idempotency support.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Payment Processing", description = "Idempotent payment processing API")
public class PaymentController {

    private final IdempotencyService idempotencyService;

    /**
     * Process a payment request with idempotency guarantees.
     * 
     * @param idempotencyKey Unique key to ensure idempotent processing
     * @param paymentRequest Payment details
     * @return Payment response (new or cached)
     */
    @PostMapping("/process-payment")
    @Operation(
            summary = "Process payment with idempotency",
            description = "Process a payment request. Duplicate requests with the same " +
                         "Idempotency-Key will return the cached response."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Payment processed successfully (or cached response returned)",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = PaymentResponse.class))
            ),
            @ApiResponse(
                    responseCode = "201",
                    description = "Payment created successfully",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = PaymentResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Bad request - missing or invalid parameters",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "Conflict - idempotency key used for different request body",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "422",
                    description = "Unprocessable Entity - idempotency key used for different request body",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ResponseEntity<PaymentResponse> processPayment(
            @Parameter(description = "Unique idempotency key for this request", 
                       required = true,
                       example = "unique-key-12345")
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            
            @Valid @RequestBody PaymentRequest paymentRequest) {
        
        log.info("Processing payment with idempotency key: {}", idempotencyKey);
        
        PaymentResponse response = idempotencyService.processPayment(idempotencyKey, paymentRequest);
        
        // Add cache hit header if response is cached
        if (Boolean.TRUE.equals(response.getCached())) {
            return ResponseEntity.ok()
                    .header("X-Cache-Hit", "true")
                    .body(response);
        }
        
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Global exception handler for idempotency conflicts
     */
    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyConflict(IdempotencyConflictException ex) {
        log.warn("Idempotency conflict: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ErrorResponse.unprocessable(ex.getMessage()));
    }

    /**
     * Global exception handler for validation errors
     */
    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationErrors(
            org.springframework.web.bind.MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");
        
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.badRequest(message));
    }

    /**
     * Global exception handler for missing idempotency key
     */
    @ExceptionHandler(org.springframework.web.bind.MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(
            org.springframework.web.bind.MissingRequestHeaderException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.badRequest("Missing required header: " + ex.getHeaderName()));
    }
}