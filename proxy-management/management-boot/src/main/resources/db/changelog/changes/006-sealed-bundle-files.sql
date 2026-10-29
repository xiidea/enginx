--liquibase formatted sql

--changeset enginx:006-010-sealed-bundle-files
--comment A rendered bundle is kept as rendered, so a redeploy or a rollback resends exactly what
--comment was validated. Until now that meant private keys, htpasswd hashes and gate keys sat here
--comment in the clear, beside the envelope-encrypted originals they were rendered from. Sensitive
--comment files are now sealed the same way: content is null and the five sealed parts are set.
--comment
--comment Existing rows cannot be sealed here, because the key that seals them is not the
--comment database's. The application seals them at startup (BundleFileSealer); the constraint is
--comment added NOT VALID so it binds every new and changed row from now on without failing on the
--comment ones still waiting for that.
ALTER TABLE config_bundle_files ALTER COLUMN content DROP NOT NULL;
ALTER TABLE config_bundle_files
    ADD COLUMN content_ciphertext  bytea,
    ADD COLUMN content_wrapped_dek bytea,
    ADD COLUMN content_kek_id      varchar(64),
    ADD COLUMN content_cipher      varchar(32),
    ADD COLUMN content_iv          bytea;
ALTER TABLE config_bundle_files
    ADD CONSTRAINT ck_bundle_file_content CHECK (
        (content IS NULL) = (content_ciphertext IS NOT NULL)
        AND (content_ciphertext IS NULL OR (content_wrapped_dek IS NOT NULL AND content_kek_id IS NOT NULL
                                            AND content_iv IS NOT NULL)));
ALTER TABLE config_bundle_files
    ADD CONSTRAINT ck_bundle_file_sensitive_sealed CHECK (NOT sensitive OR content IS NULL) NOT VALID;
--rollback ALTER TABLE config_bundle_files DROP CONSTRAINT ck_bundle_file_sensitive_sealed;
--rollback ALTER TABLE config_bundle_files DROP CONSTRAINT ck_bundle_file_content;
--rollback ALTER TABLE config_bundle_files DROP COLUMN content_ciphertext, DROP COLUMN content_wrapped_dek, DROP COLUMN content_kek_id, DROP COLUMN content_cipher, DROP COLUMN content_iv;
