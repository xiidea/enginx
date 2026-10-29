--liquibase formatted sql

--changeset enginx:005-010-site-protection-oidc
--comment Sign-in through the platform's identity provider, by the same gate as email sign-in, so
--comment the session length column now serves both gate modes. oidc_any_user is set exactly when
--comment the mode is OIDC: true admits everyone who can sign in there, false only the users and
--comment roles listed below.
ALTER TABLE proxy_sites DROP CONSTRAINT ck_site_protection_mode;
ALTER TABLE proxy_sites
    ADD CONSTRAINT ck_site_protection_mode CHECK (protection_mode IN ('NONE','BASIC','EMAIL_OTP','OIDC'));
ALTER TABLE proxy_sites DROP CONSTRAINT ck_site_gate_session_ttl;
ALTER TABLE proxy_sites
    ADD CONSTRAINT ck_site_gate_session_ttl CHECK (
        (protection_mode IN ('EMAIL_OTP','OIDC')) = (gate_session_ttl_s IS NOT NULL)
        AND (gate_session_ttl_s IS NULL OR gate_session_ttl_s BETWEEN 300 AND 2592000));
ALTER TABLE proxy_sites ADD COLUMN oidc_any_user boolean;
ALTER TABLE proxy_sites
    ADD CONSTRAINT ck_site_oidc_any_user CHECK ((protection_mode = 'OIDC') = (oidc_any_user IS NOT NULL));
--rollback ALTER TABLE proxy_sites DROP CONSTRAINT ck_site_oidc_any_user;
--rollback ALTER TABLE proxy_sites DROP COLUMN oidc_any_user;

--changeset enginx:005-020-proxy-site-oidc-users
--comment Matched against the token's email and preferred_username. Mirrors OidcAccessPolicy.
CREATE TABLE proxy_site_oidc_users (
    id            uuid         PRIMARY KEY,
    proxy_site_id uuid         NOT NULL REFERENCES proxy_sites(id) ON DELETE CASCADE,
    username      varchar(254) NOT NULL,
    CONSTRAINT ck_oidc_user CHECK (username ~ '^[a-z0-9._@+-]{1,254}$'),
    CONSTRAINT uq_oidc_user UNIQUE (proxy_site_id, username)
);
CREATE INDEX idx_oidc_users_site ON proxy_site_oidc_users (proxy_site_id);
--rollback DROP TABLE proxy_site_oidc_users;

--changeset enginx:005-030-proxy-site-oidc-roles
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
