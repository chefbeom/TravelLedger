package com.playdata.calen.common.embedding;

import com.playdata.calen.common.exception.ServiceUnavailableException;

/** Does not retain response bodies, API keys, document contents or a raw HTTP exception. */
public class EmbeddingApiException extends ServiceUnavailableException {

    private final Integer httpStatus;
    private final boolean retryable;

    public EmbeddingApiException(String message, Integer httpStatus, boolean retryable) {
        super(message);
        this.httpStatus = httpStatus;
        this.retryable = retryable;
    }

    public Integer getHttpStatus() { return httpStatus; }
    public boolean isRetryable() { return retryable; }
}
