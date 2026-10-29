--liquibase formatted sql

--changeset enginx:003-010-site-protection-mode
--comment How a site gates who may reach it. On proxy_sites beside ssl_enabled rather than in a
--comment side table, because it decides what is rendered, as ssl_enabled does. Protection needs
--comment forced HTTPS so credentials never cross plain HTTP; the domain enforces that, and so
--comment does this constraint.
ALTER TABLE proxy_sites
    ADD COLUMN protection_mode varchar(16) NOT NULL DEFAULT 'NONE';
ALTER TABLE proxy_sites
    ADD CONSTRAINT ck_site_protection_mode CHECK (protection_mode IN ('NONE','BASIC'));
ALTER TABLE proxy_sites
    ADD CONSTRAINT ck_site_protection_https CHECK (protection_mode = 'NONE' OR (ssl_enabled AND force_https));
--rollback ALTER TABLE proxy_sites DROP CONSTRAINT ck_site_protection_https;
--rollback ALTER TABLE proxy_sites DROP CONSTRAINT ck_site_protection_mode;
--rollback ALTER TABLE proxy_sites DROP COLUMN protection_mode;

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

--changeset enginx:003-030-config-bundle-file-mode
--comment The mode the agent writes a file with. 0640 is new: secrets the unprivileged NGINX
--comment workers read on each request, such as an htpasswd file. Existing rows take the mode
--comment their sensitive flag always implied.
ALTER TABLE config_bundle_files
    ADD COLUMN file_mode varchar(4) NOT NULL DEFAULT '0644';
UPDATE config_bundle_files SET file_mode = '0600' WHERE sensitive;
ALTER TABLE config_bundle_files
    ADD CONSTRAINT ck_bundle_file_mode CHECK (file_mode IN ('0644','0600','0640')
                                            AND (sensitive = (file_mode <> '0644')));
--rollback ALTER TABLE config_bundle_files DROP CONSTRAINT ck_bundle_file_mode;
--rollback ALTER TABLE config_bundle_files DROP COLUMN file_mode;
