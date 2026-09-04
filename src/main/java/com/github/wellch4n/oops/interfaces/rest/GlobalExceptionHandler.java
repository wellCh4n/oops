package com.github.wellch4n.oops.interfaces.rest;

import com.github.wellch4n.oops.shared.exception.BizException;
import com.github.wellch4n.oops.interfaces.dto.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public Result<Void> handleBizException(BizException exception) {
        return Result.failure(exception.getMessage());
    }

    /**
     * A browser closing an {@code EventSource} mid-stream — leaving the pipeline page, switching to
     * another step's log — reaches here as the container's error dispatch for the SSE request.
     * Nothing is wrong and nobody is listening: the catch-all below would log it as an error and
     * then fail again trying to write a JSON envelope onto a {@code text/event-stream} response.
     * Returning nothing marks the response as handled, which for a dead connection is exactly right.
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleDisconnectedClient(AsyncRequestNotUsableException exception) {
        log.debug("Client disconnected from a streaming response: {}", exception.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception exception) {
        log.error("Unhandled exception", exception);
        return Result.failure("Internal server error");
    }
}
