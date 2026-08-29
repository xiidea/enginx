package net.xiidea.enginx.domain.proxy;

import net.xiidea.enginx.domain.shared.ValidationException;

public record ProxyTimeouts(int connectSeconds, int readSeconds, int sendSeconds, long maxBodySizeBytes) {

    private static final long MAX_BODY_CEILING = 10L * 1024 * 1024 * 1024;

    public ProxyTimeouts {
        checkSeconds("connectSeconds", connectSeconds);
        checkSeconds("readSeconds", readSeconds);
        checkSeconds("sendSeconds", sendSeconds);
        if (maxBodySizeBytes < 0 || maxBodySizeBytes > MAX_BODY_CEILING) {
            throw new ValidationException("maxBodySizeBytes", "maxBodySizeBytes must be between 0 and " + MAX_BODY_CEILING);
        }
    }

    private static void checkSeconds(String field, int value) {
        if (value < 1 || value > 3600) {
            throw new ValidationException(field, field + " must be between 1 and 3600 seconds");
        }
    }

    public static ProxyTimeouts defaults() {
        return new ProxyTimeouts(60, 60, 60, 1_048_576L);
    }
}
