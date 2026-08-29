package net.xiidea.enginx.domain.certificate;

import net.xiidea.enginx.domain.shared.DomainException;

/** A certificate could not be obtained. */
public class CertificateIssuanceException extends DomainException {

    private final boolean retryable;

    public CertificateIssuanceException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public CertificateIssuanceException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    /**
     * Whether trying again could plausibly work.
     *
     * <p>This matters more than usual with ACME: authorities apply hard rate limits, and Let's
     * Encrypt's are counted per week. Retrying a request that failed because the domain does not
     * point here would burn the allowance and lock the account out of issuing anything at all.
     */
    public boolean isRetryable() {
        return retryable;
    }
}
