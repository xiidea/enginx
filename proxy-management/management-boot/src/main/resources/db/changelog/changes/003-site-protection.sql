--liquibase formatted sql

--changeset enginx:003-010-site-protection
--comment How a site gates who may reach it. On proxy_sites beside ssl_enabled rather than in a
--comment side table, because it decides what is rendered, as ssl_enabled does. Every mode but NONE
--comment needs forced HTTPS so credentials and session cookies never cross plain HTTP; the domain
--comment enforces that, and so does ck_site_protection_https.
--comment
--comment gate_session_ttl_s is how long a sign-in through the host's gate lasts, set exactly for the
--comment two gate modes. oidc_any_user is set exactly for OIDC: true admits everyone who can sign in
--comment at the platform's identity provider, false only the users and roles listed below.
ALTER TABLE proxy_sites
    ADD COLUMN protection_mode    varchar(16) NOT NULL DEFAULT 'NONE',
    ADD COLUMN gate_session_ttl_s integer,
    ADD COLUMN oidc_any_user      boolean;
ALTER TABLE proxy_sites
    ADD CONSTRAINT ck_site_protection_mode CHECK (protection_mode IN ('NONE','BASIC','EMAIL_OTP','OIDC')),
    ADD CONSTRAINT ck_site_protection_https CHECK (protection_mode = 'NONE' OR (ssl_enabled AND force_https)),
    ADD CONSTRAINT ck_site_gate_session_ttl CHECK (
        (protection_mode IN ('EMAIL_OTP','OIDC')) = (gate_session_ttl_s IS NOT NULL)
        AND (gate_session_ttl_s IS NULL OR gate_session_ttl_s BETWEEN 300 AND 2592000)),
    ADD CONSTRAINT ck_site_oidc_any_user CHECK ((protection_mode = 'OIDC') = (oidc_any_user IS NOT NULL));
--rollback ALTER TABLE proxy_sites DROP CONSTRAINT ck_site_oidc_any_user, DROP CONSTRAINT ck_site_gate_session_ttl, DROP CONSTRAINT ck_site_protection_https, DROP CONSTRAINT ck_site_protection_mode;
--rollback ALTER TABLE proxy_sites DROP COLUMN oidc_any_user, DROP COLUMN gate_session_ttl_s, DROP COLUMN protection_mode;

--changeset enginx:003-020-proxy-site-basic-auth-users
--comment Each row becomes one line of the site's htpasswd file, username:hash. The constraints
--comment mirror BasicAuthUser: neither value may contain a colon, a newline or anything else
--comment that would split or add a line. The password is never stored, only its SHA-512-crypt.
CREATE TABLE proxy_site_basic_auth_users (
    id            uuid         PRIMARY KEY,
    proxy_site_id uuid         NOT NULL REFERENCES proxy_sites(id) ON DELETE CASCADE,
    username      varchar(64)  NOT NULL,
    password_hash varchar(160) NOT NULL,
    CONSTRAINT ck_basic_auth_username CHECK (username ~ '^[A-Za-z0-9._@-]{1,64}$'),
    CONSTRAINT ck_basic_auth_hash     CHECK (password_hash ~ '^\$6\$(rounds=[0-9]{4,9}\$)?[./A-Za-z0-9]{1,16}\$[./A-Za-z0-9]{86}$'),
    CONSTRAINT uq_basic_auth_user UNIQUE (proxy_site_id, username)
);
CREATE INDEX idx_basic_auth_users_site ON proxy_site_basic_auth_users (proxy_site_id);
--rollback DROP TABLE proxy_site_basic_auth_users;

--changeset enginx:003-030-proxy-site-allowed-emails
--comment Email sign-in's allow-list. Rendered into the gate's file as JSON strings and compared by
--comment the gate as text, so the constraint mirrors AllowedEmail: lower case, no quotes,
--comment backslashes or whitespace.
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

--changeset enginx:003-040-proxy-site-oidc-users
--comment Identity provider sign-in's users, matched against the token's email and
--comment preferred_username. Mirrors OidcAccessPolicy.
CREATE TABLE proxy_site_oidc_users (
    id            uuid         PRIMARY KEY,
    proxy_site_id uuid         NOT NULL REFERENCES proxy_sites(id) ON DELETE CASCADE,
    username      varchar(254) NOT NULL,
    CONSTRAINT ck_oidc_user CHECK (username ~ '^[a-z0-9._@+-]{1,254}$'),
    CONSTRAINT uq_oidc_user UNIQUE (proxy_site_id, username)
);
CREATE INDEX idx_oidc_users_site ON proxy_site_oidc_users (proxy_site_id);
--rollback DROP TABLE proxy_site_oidc_users;

--changeset enginx:003-050-proxy-site-oidc-roles
--comment A realm role when client_id is null, otherwise a role on that client. Mirrors OidcRole.
CREATE TABLE proxy_site_oidc_roles (
    id            uuid         PRIMARY KEY,
    proxy_site_id uuid         NOT NULL REFERENCES proxy_sites(id) ON DELETE CASCADE,
    client_id     varchar(128),
    role_name     varchar(128) NOT NULL,
    CONSTRAINT ck_oidc_role_client CHECK (client_id IS NULL OR client_id ~ '^[A-Za-z0-9._@/-]{1,128}$'),
    CONSTRAINT ck_oidc_role_name CHECK (role_name ~ '^[A-Za-z0-9._@/-]{1,128}$')
);
CREATE UNIQUE INDEX uq_oidc_role ON proxy_site_oidc_roles (proxy_site_id, coalesce(client_id, ''), role_name);
CREATE INDEX idx_oidc_roles_site ON proxy_site_oidc_roles (proxy_site_id);
--rollback DROP TABLE proxy_site_oidc_roles;

--changeset enginx:003-060-proxy-site-gate-secrets
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

--changeset enginx:003-070-config-bundle-files
--comment Two changes to how a stored bundle holds its files.
--comment
--comment file_mode is the mode the agent writes a file with. 0640 is new: secrets the unprivileged
--comment NGINX workers read on each request, such as an htpasswd file. Existing rows take the mode
--comment their sensitive flag always implied.
--comment
--comment Sensitive files are sealed. A bundle is kept as rendered so a redeploy or a rollback
--comment resends exactly what was validated, which until now meant private keys sat here in the
--comment clear. Now content is null and the five sealed parts are set. Existing rows cannot be
--comment sealed here, because the key that seals them is not the database's: the application seals
--comment them at startup (BundleFileSecrets). ck_bundle_file_sensitive_sealed is added NOT VALID so
--comment it binds every new and changed row from now on without failing on the ones still waiting,
--comment and the application validates it once they are sealed.
ALTER TABLE config_bundle_files
    ADD COLUMN file_mode varchar(4) NOT NULL DEFAULT '0644';
UPDATE config_bundle_files SET file_mode = '0600' WHERE sensitive;
ALTER TABLE config_bundle_files ALTER COLUMN content DROP NOT NULL;
ALTER TABLE config_bundle_files
    ADD COLUMN content_ciphertext  bytea,
    ADD COLUMN content_wrapped_dek bytea,
    ADD COLUMN content_kek_id      varchar(64),
    ADD COLUMN content_cipher      varchar(32),
    ADD COLUMN content_iv          bytea;
ALTER TABLE config_bundle_files
    ADD CONSTRAINT ck_bundle_file_mode CHECK (file_mode IN ('0644','0600','0640')
                                            AND (sensitive = (file_mode <> '0644'))),
    ADD CONSTRAINT ck_bundle_file_content CHECK (
        (content IS NULL) = (content_ciphertext IS NOT NULL)
        AND (content_ciphertext IS NULL OR (content_wrapped_dek IS NOT NULL AND content_kek_id IS NOT NULL
                                            AND content_iv IS NOT NULL)));
ALTER TABLE config_bundle_files
    ADD CONSTRAINT ck_bundle_file_sensitive_sealed CHECK (NOT sensitive OR content IS NULL) NOT VALID;
--rollback ALTER TABLE config_bundle_files DROP CONSTRAINT ck_bundle_file_sensitive_sealed, DROP CONSTRAINT ck_bundle_file_content, DROP CONSTRAINT ck_bundle_file_mode;
--rollback ALTER TABLE config_bundle_files DROP COLUMN content_iv, DROP COLUMN content_cipher, DROP COLUMN content_kek_id, DROP COLUMN content_wrapped_dek, DROP COLUMN content_ciphertext, DROP COLUMN file_mode;
--rollback ALTER TABLE config_bundle_files ALTER COLUMN content SET NOT NULL;

--changeset enginx:003-080-basic-login-page
--comment Basic authentication on the gate's login page rather than the browser's prompt: the same
--comment users, through the gate, with sessions. A sign-in through the gate has a length, so
--comment gate_session_ttl_s is now set for a basic-auth site with a login page too.
ALTER TABLE proxy_sites ADD COLUMN basic_login_page boolean NOT NULL DEFAULT false;
ALTER TABLE proxy_sites DROP CONSTRAINT ck_site_gate_session_ttl;
ALTER TABLE proxy_sites
    ADD CONSTRAINT ck_site_basic_login_page CHECK (NOT basic_login_page OR protection_mode = 'BASIC'),
    ADD CONSTRAINT ck_site_gate_session_ttl CHECK (
        (protection_mode IN ('EMAIL_OTP','OIDC') OR basic_login_page) = (gate_session_ttl_s IS NOT NULL)
        AND (gate_session_ttl_s IS NULL OR gate_session_ttl_s BETWEEN 300 AND 2592000));
--rollback ALTER TABLE proxy_sites DROP CONSTRAINT ck_site_gate_session_ttl, DROP CONSTRAINT ck_site_basic_login_page;
--rollback ALTER TABLE proxy_sites ADD CONSTRAINT ck_site_gate_session_ttl CHECK ((protection_mode IN ('EMAIL_OTP','OIDC')) = (gate_session_ttl_s IS NOT NULL) AND (gate_session_ttl_s IS NULL OR gate_session_ttl_s BETWEEN 300 AND 2592000));
--rollback ALTER TABLE proxy_sites DROP COLUMN basic_login_page;
