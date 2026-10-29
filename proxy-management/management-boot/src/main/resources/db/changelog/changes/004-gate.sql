--liquibase formatted sql

--changeset enginx:004-010-site-protection-email-otp
--comment Email sign-in through the host's gate. Like BASIC it needs forced HTTPS, which the
--comment existing ck_site_protection_https already requires of every mode but NONE.
ALTER TABLE proxy_sites DROP CONSTRAINT ck_site_protection_mode;
ALTER TABLE proxy_sites
    ADD CONSTRAINT ck_site_protection_mode CHECK (protection_mode IN ('NONE','BASIC','EMAIL_OTP'));
ALTER TABLE proxy_sites
    ADD COLUMN gate_session_ttl_s integer;
ALTER TABLE proxy_sites
    ADD CONSTRAINT ck_site_gate_session_ttl CHECK (
        (protection_mode = 'EMAIL_OTP') = (gate_session_ttl_s IS NOT NULL)
        AND (gate_session_ttl_s IS NULL OR gate_session_ttl_s BETWEEN 300 AND 2592000));
--rollback ALTER TABLE proxy_sites DROP CONSTRAINT ck_site_gate_session_ttl;
--rollback ALTER TABLE proxy_sites DROP COLUMN gate_session_ttl_s;
--rollback ALTER TABLE proxy_sites DROP CONSTRAINT ck_site_protection_mode;
--rollback ALTER TABLE proxy_sites ADD CONSTRAINT ck_site_protection_mode CHECK (protection_mode IN ('NONE','BASIC'));

--changeset enginx:004-020-proxy-site-allowed-emails
--comment Rendered into the gate's file as JSON strings and compared by the gate as text, so the
--comment constraint mirrors AllowedEmail: lower case, no quotes, backslashes or whitespace.
CREATE TABLE proxy_site_allowed_emails (
    id            uuid         PRIMARY KEY,
    proxy_site_id uuid         NOT NULL REFERENCES proxy_sites(id) ON DELETE CASCADE,
    email         varchar(254) NOT NULL,
    CONSTRAINT ck_allowed_email CHECK (email = lower(email) AND email !~ '["\\[:space:][:cntrl:]]'
                                       AND email ~ '^(\*|[^@]{1,64})@[a-z0-9.-]+\.[a-z]{2,63}$'),
    CONSTRAINT uq_allowed_email UNIQUE (proxy_site_id, email)
);
CREATE INDEX idx_allowed_emails_site ON proxy_site_allowed_emails (proxy_site_id);
--rollback DROP TABLE proxy_site_allowed_emails;

--changeset enginx:004-030-proxy-site-gate-secrets
--comment Each gated site's 32-byte key: it signs visitors' sessions and the gate's requests to
--comment have codes mailed. Envelope-encrypted like every other secret; all five parts or none.
--comment Kept after protection is turned off, so turning it back on does not sign out nobody
--comment in particular; rotating it is what signs everyone out.
CREATE TABLE proxy_site_gate_secrets (
    proxy_site_id   uuid        PRIMARY KEY REFERENCES proxy_sites(id) ON DELETE CASCADE,
    key_ciphertext  bytea       NOT NULL,
    key_wrapped_dek bytea       NOT NULL,
    key_kek_id      varchar(64) NOT NULL,
    key_cipher      varchar(32) NOT NULL,
    key_iv          bytea       NOT NULL,
    rotated_at      timestamptz NOT NULL DEFAULT now()
);
--rollback DROP TABLE proxy_site_gate_secrets;
