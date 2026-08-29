package net.xiidea.enginx.infrastructure.crypto;

import net.xiidea.enginx.domain.certificate.SecretDecryptionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.List;

/**
 * Chooses which provider wraps, and which one unwraps.
 *
 * <p>Writing goes to the configured provider. Reading goes to whichever provider recognises the key
 * id the secret records — which is what makes a provider change something an estate can survive.
 * Switching from a configured key to Vault otherwise makes every certificate private key and the
 * ACME account key unreadable the moment the application restarts, and the failure is not obvious:
 * the platform starts, reports healthy, and then cannot deploy anything with SSL.
 *
 * <p>Migration is therefore: keep the old configuration in place, point {@code provider} at the new
 * one, restart, and re-wrap. Old secrets stay readable throughout, and the old key can be removed
 * once nothing names it.
 */
@Component
@Primary
public class RoutingKekProvider implements KekProvider {

    private static final Logger log = LoggerFactory.getLogger(RoutingKekProvider.class);

    private final KekProvider writer;
    private final List<KekProvider> readers;

    public RoutingKekProvider(List<KekProvider> providers,
                              @Value("${enginx.crypto.provider:environment}") String configured) {
        // Itself excluded, or wrapping would recurse.
        this.readers = providers.stream().filter(p -> !(p instanceof RoutingKekProvider)).toList();
        this.writer = select(readers, configured);

        log.info("Data keys are wrapped by {} and can be unwrapped by {}",
                writer.getClass().getSimpleName(),
                readers.stream().map(p -> p.getClass().getSimpleName()).toList());
    }

    private static KekProvider select(List<KekProvider> providers, String configured) {
        String wanted = "vault".equalsIgnoreCase(configured)
                ? VaultTransitKekProvider.class.getSimpleName()
                : EnvironmentKekProvider.class.getSimpleName();

        return providers.stream()
                .filter(provider -> provider.getClass().getSimpleName().equals(wanted))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "enginx.crypto.provider is '" + configured + "' but no such provider is configured. "
                                + "For 'vault', set enginx.crypto.vault.address and .token."));
    }

    @Override
    public String currentKeyId() {
        return writer.currentKeyId();
    }

    @Override
    public WrappedKey wrap(SecretKey dataKey) {
        return writer.wrap(dataKey);
    }

    @Override
    public boolean canUnwrap(String kekId) {
        return readers.stream().anyMatch(provider -> provider.canUnwrap(kekId));
    }

    @Override
    public SecretKey unwrap(String kekId, byte[] wrapped) {
        return readers.stream()
                .filter(provider -> provider.canUnwrap(kekId))
                .findFirst()
                .orElseThrow(() -> new SecretDecryptionException(
                        "No configured key provider recognises key id '" + kekId + "'. "
                                + "If the provider was changed, keep the previous one configured until "
                                + "every secret has been re-wrapped."))
                .unwrap(kekId, wrapped);
    }
}
