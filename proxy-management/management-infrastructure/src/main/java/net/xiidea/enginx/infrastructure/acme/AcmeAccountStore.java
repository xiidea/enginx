package net.xiidea.enginx.infrastructure.acme;

import net.xiidea.enginx.application.certificate.SecretRewrapTarget;
import net.xiidea.enginx.domain.certificate.EncryptedSecret;
import net.xiidea.enginx.domain.certificate.SecretEncryption;
import net.xiidea.enginx.infrastructure.persistence.entity.AcmeAccountEntity;
import net.xiidea.enginx.infrastructure.persistence.repository.AcmeAccountJpaRepository;
import org.shredzone.acme4j.Account;
import org.shredzone.acme4j.AccountBuilder;
import org.shredzone.acme4j.Login;
import org.shredzone.acme4j.Session;
import org.shredzone.acme4j.exception.AcmeException;
import org.shredzone.acme4j.util.KeyPairUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URI;
import java.security.KeyPair;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The platform's account at an ACME authority.
 *
 * <p>The account key is persisted, encrypted with the same envelope scheme as certificate keys.
 * Generating a new one on every start would exhaust the authority's account-creation rate limit
 * within a day, and would abandon the authorisations the previous account had already earned, so
 * every issuance would revalidate from scratch.
 */
@Component
public class AcmeAccountStore implements SecretRewrapTarget {

    private static final Logger log = LoggerFactory.getLogger(AcmeAccountStore.class);

    private final AcmeAccountJpaRepository accounts;
    private final SecretEncryption encryption;
    private final AcmeProperties properties;

    public AcmeAccountStore(AcmeAccountJpaRepository accounts, SecretEncryption encryption,
                            AcmeProperties properties) {
        this.accounts = accounts;
        this.encryption = encryption;
        this.properties = properties;
    }

    /**
     * Logs in to the authority, registering an account the first time.
     *
     * <p>The account URL is stored with the key, so later logins bind to the existing account
     * rather than registering again — which some authorities count as a fresh account each time.
     */
    @Transactional
    public Login login(Session session) throws AcmeException {
        Optional<AcmeAccountEntity> stored = accounts.findByDirectoryUrl(properties.directoryUrl());

        if (stored.isPresent() && stored.get().getAccountUrl() != null) {
            AcmeAccountEntity entity = stored.get();
            try {
                Login login = session.login(URI.create(entity.getAccountUrl()).toURL(), decryptKeyPair(entity));
                // Prove the account still exists before relying on it. An authority can retire an
                // account, and a test server loses them whenever it restarts; without this check
                // every future issuance fails with "account not found" and the only fix is to
                // edit the database by hand.
                login.getAccount().fetch();
                return login;
            } catch (java.net.MalformedURLException e) {
                throw new AcmeException("The stored ACME account URL is not usable: " + entity.getAccountUrl(), e);
            } catch (AcmeException e) {
                log.warn("The stored ACME account at {} is no longer usable ({}). Registering a new one.",
                        entity.getAccountUrl(), e.getMessage());
                accounts.delete(entity);
                accounts.flush();
                stored = Optional.empty();
            }
        }

        KeyPair keyPair = stored.map(this::decryptKeyPair)
                .orElseGet(() -> KeyPairUtils.createKeyPair(properties.keySize()));

        AccountBuilder builder = new AccountBuilder().useKeyPair(keyPair);
        if (properties.acceptTermsOfService()) {
            builder.agreeToTermsOfService();
        }
        if (properties.contactEmail() != null && !properties.contactEmail().isBlank()) {
            builder.addEmail(properties.contactEmail());
        }

        // createLogin registers the account and hands back a Login in one step. Account.getLogin
        // is not public, so building the account first would leave no way to reach the session
        // binding the rest of the flow needs.
        Login login = builder.createLogin(session);
        Account account = login.getAccount();
        log.info("Registered ACME account with {} at {}", properties.directoryUrl(), account.getLocation());

        persist(stored.orElse(null), keyPair, account.getLocation().toString());
        return login;
    }

    private void persist(AcmeAccountEntity existing, KeyPair keyPair, String accountUrl) {
        if (existing != null) {
            existing.setAccountUrl(accountUrl);
            accounts.save(existing);
            return;
        }
        EncryptedSecret sealed = encryption.encrypt(serialise(keyPair));
        AcmeAccountEntity entity = new AcmeAccountEntity(UUID.randomUUID(), properties.directoryUrl(),
                properties.contactEmail(), sealed.ciphertext(), sealed.wrappedDataKey(), sealed.kekId(),
                sealed.cipher(), sealed.iv(), Instant.now());
        entity.setAccountUrl(accountUrl);
        accounts.save(entity);
    }

    @Override
    public String name() {
        return "ACME account keys";
    }

    /**
     * Moves the account key to the current key-encryption key.
     *
     * <p>Small in count and large in consequence: there is one row, and losing it means
     * registering a new account with the authority — which burns an account-creation rate limit
     * and abandons every authorisation the old account had cached.
     *
     * <p>REQUIRES_NEW, because the caller scans in a read-only transaction. Joining it would let
     * this method run, count what it changed, and have every write silently discarded at commit —
     * reporting a successful migration that did not happen, which is how an old key gets deleted
     * while something still needs it.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int rewrap(String currentKeyId) {
        int moved = 0;
        for (AcmeAccountEntity entity : accounts.findAll()) {
            if (currentKeyId.equals(entity.getKekId())) {
                continue;
            }
            EncryptedSecret resealed = encryption.encrypt(serialise(decryptKeyPair(entity)));
            entity.reseal(resealed.ciphertext(), resealed.wrappedDataKey(), resealed.kekId(),
                    resealed.cipher(), resealed.iv());
            accounts.save(entity);
            moved++;
        }
        return moved;
    }

    private KeyPair decryptKeyPair(AcmeAccountEntity entity) {
        EncryptedSecret sealed = new EncryptedSecret(entity.getCiphertext(), entity.getWrappedDek(),
                entity.getKekId(), entity.getCipher(), entity.getIv(), new byte[0]);
        return parse(encryption.decrypt(sealed));
    }

    private static String serialise(KeyPair keyPair) {
        StringWriter writer = new StringWriter();
        try {
            KeyPairUtils.writeKeyPair(keyPair, writer);
        } catch (IOException e) {
            throw new IllegalStateException("Could not serialise the ACME account key", e);
        }
        return writer.toString();
    }

    private static KeyPair parse(String pem) {
        try (StringReader reader = new StringReader(pem)) {
            return KeyPairUtils.readKeyPair(reader);
        } catch (IOException e) {
            throw new IllegalStateException("The stored ACME account key could not be read", e);
        }
    }
}
