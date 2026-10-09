--liquibase formatted sql

-- The complete schema, as one baseline.
--
-- The incremental changelogs that built this up were collapsed before the first release. Nothing
-- had been published, so there was no deployed database whose history needed preserving, and the
-- step-by-step record was describing a past no running system had lived through. The comments here
-- explain why the schema is shaped the way it is; they no longer narrate how it got here.
--
-- PostgreSQL DDL rather than Liquibase's database-agnostic tags. The platform targets PostgreSQL
-- only, and this schema depends on partial indexes, check constraints, prefix-matching operator
-- classes and declarative partitioning — none of which survive an abstraction layer.

--changeset enginx:baseline-010-nginx-instances
--comment Managed NGINX hosts, addressed through their agent. The management server never opens an
--comment SSH session; what it holds about a host is how the two reach each other, and nothing more.
--comment
--comment connectivity_mode is which way round. PUSH: the management server dials agent_base_url,
--comment which needs a port on the host reachable from the management plane. PULL: the agent
--comment registers itself with a token and long-polls for work, so nothing dials the host at all —
--comment the model for a host behind NAT or in another network. Both are supported per instance.
--comment
--comment push_transport is how a PUSH host is told the caller is the platform. MTLS: a client
--comment certificate, with the agent's own certificate fingerprint pinned here. HTTP_TOKEN: a
--comment pre-shared token, for a host behind a proxy that terminates TLS and so cannot pass a
--comment client certificate through. The token is sealed under the same envelope encryption as
--comment private keys — the agent_token_* columns — because it is a credential that can push
--comment configuration, keys included, to the host.
--comment
--comment ck_instance_mode_fields is what keeps the fields from being merely optional: each mode and
--comment transport requires exactly its own fields and refuses the others', so a row can neither
--comment describe a host nobody can reach nor carry two identities that disagree about which is used.
CREATE TABLE nginx_instances (
    id                      uuid         PRIMARY KEY,
    name                    varchar(64)  NOT NULL,
    hostname                varchar(253) NOT NULL,
    connectivity_mode       varchar(8)   NOT NULL DEFAULT 'PUSH',
    push_transport          varchar(16)  NOT NULL DEFAULT 'MTLS',
    agent_base_url          varchar(512),
    agent_cert_fingerprint  varchar(64),
    -- All five set together or all null; see ck_instance_agent_token_sealed.
    agent_token_ciphertext  bytea,
    agent_token_wrapped_dek bytea,
    agent_token_kek_id      varchar(64),
    agent_token_cipher      varchar(32),
    agent_token_iv          bytea,
    environment             varchar(32)  NOT NULL DEFAULT 'PRODUCTION',
    status                  varchar(16)  NOT NULL DEFAULT 'UNKNOWN',
    nginx_version           varchar(32),
    agent_version           varchar(32),
    last_seen_at            timestamptz,
    -- Set once config_bundles exists; see the deferred links changeset at the end of this file.
    created_at              timestamptz  NOT NULL DEFAULT now(),
    updated_at              timestamptz  NOT NULL DEFAULT now(),
    version                 bigint       NOT NULL DEFAULT 0,
    CONSTRAINT uq_nginx_instance_name CHECK (name = lower(name)),
    CONSTRAINT ck_nginx_instance_status CHECK (status IN ('ONLINE','OFFLINE','DEGRADED','UNKNOWN')),
    CONSTRAINT ck_nginx_instance_fingerprint CHECK (agent_cert_fingerprint ~ '^[A-F0-9]{64}$'),
    CONSTRAINT ck_instance_connectivity_mode CHECK (connectivity_mode IN ('PUSH', 'PULL')),
    CONSTRAINT ck_instance_push_transport CHECK (push_transport IN ('MTLS', 'HTTP_TOKEN')),
    -- A ciphertext without the key that wrapped it is unreadable, and one with only some of its
    -- parts would fail at the moment of a deployment rather than here.
    CONSTRAINT ck_instance_agent_token_sealed CHECK (
        (agent_token_ciphertext IS NULL
            AND agent_token_wrapped_dek IS NULL
            AND agent_token_kek_id IS NULL
            AND agent_token_iv IS NULL)
        OR (agent_token_ciphertext IS NOT NULL
            AND agent_token_wrapped_dek IS NOT NULL
            AND agent_token_kek_id IS NOT NULL
            AND agent_token_iv IS NOT NULL)),
    CONSTRAINT ck_instance_mode_fields CHECK (
        (connectivity_mode = 'PUSH'
            AND push_transport = 'MTLS'
            AND agent_base_url IS NOT NULL
            AND agent_cert_fingerprint IS NOT NULL
            AND agent_token_ciphertext IS NULL)
        OR (connectivity_mode = 'PUSH'
            AND push_transport = 'HTTP_TOKEN'
            AND agent_base_url IS NOT NULL
            AND agent_cert_fingerprint IS NULL
            AND agent_token_ciphertext IS NOT NULL)
        OR (connectivity_mode = 'PULL'
            AND agent_base_url IS NULL
            AND agent_cert_fingerprint IS NULL
            AND agent_token_ciphertext IS NULL))
);
CREATE UNIQUE INDEX uq_nginx_instances_name ON nginx_instances (name);
--rollback DROP TABLE nginx_instances;

--changeset enginx:baseline-015-agent-registration-tokens
--comment The credential an operator hands to a new host so it can enrol itself.
--comment
--comment Stored as a SHA-256 digest rather than bcrypt, and the difference matters: a bcrypt hash
--comment carries a random salt, so it cannot be looked up -- finding which token was presented
--comment would mean comparing against every row. Slow hashing exists to defend low-entropy secrets
--comment that can be guessed; these are 256 bits from a CSPRNG and cannot. A digest is the right
--comment tool and is the only one that supports a lookup.
CREATE TABLE agent_registration_tokens (
    id          uuid         PRIMARY KEY,
    -- Hex SHA-256 of the token. The token itself is shown once, at creation, and never stored.
    token_hash  varchar(64)  NOT NULL,
    description varchar(256),
    -- Null means it never expires, which is a deliberate choice an operator has to make.
    expires_at  timestamptz,
    -- Null means unlimited. One is the safe default for enrolling a single known host.
    max_uses    integer,
    uses        integer      NOT NULL DEFAULT 0,
    revoked_at  timestamptz,
    created_by  varchar(128) NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_agent_reg_token_max_uses CHECK (max_uses IS NULL OR max_uses > 0),
    CONSTRAINT ck_agent_reg_token_uses CHECK (uses >= 0)
);

CREATE UNIQUE INDEX ux_agent_registration_tokens_hash ON agent_registration_tokens (token_hash);
--rollback DROP TABLE agent_registration_tokens;

--changeset enginx:baseline-016-agent-tokens
--comment The long-lived credential a host uses on every poll, issued once at registration.
--comment
--comment One row per instance rather than a column on nginx_instances: a credential has a lifecycle
--comment of its own -- issued, used, revoked, reissued -- and putting it in the aggregate's table
--comment would mean every read of an instance carries a secret it does not need.
--comment
--comment This token is what stands between an attacker and every site's private key, since a
--comment configuration bundle contains them (risk R1). It is hashed at rest for the same reason a
--comment password is, and every use is stamped so a dormant credential is visible.
CREATE TABLE agent_tokens (
    id                uuid        PRIMARY KEY,
    nginx_instance_id uuid        NOT NULL,
    token_hash        varchar(64) NOT NULL,
    issued_at         timestamptz NOT NULL DEFAULT now(),
    last_used_at      timestamptz,
    revoked_at        timestamptz,
    CONSTRAINT fk_agent_tokens_instance FOREIGN KEY (nginx_instance_id)
        REFERENCES nginx_instances (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX ux_agent_tokens_hash ON agent_tokens (token_hash);

-- One live token per instance. Reissuing revokes the previous one rather than adding a second,
-- so a stolen credential cannot be kept alive alongside its replacement.
CREATE UNIQUE INDEX ux_agent_tokens_active_instance ON agent_tokens (nginx_instance_id)
    WHERE revoked_at IS NULL;
--rollback DROP TABLE agent_tokens;

--changeset enginx:baseline-020-certificates
--comment certificate_pem holds the full chain, leaf first, which is the order NGINX requires. A
--comment separate chain column invited the two halves to drift apart and be assembled in the wrong
--comment order at deployment time — a failure that shows up only when a client validates.
CREATE TABLE certificates (
    id                 uuid         PRIMARY KEY,
    name               varchar(128) NOT NULL,
    provider           varchar(16)  NOT NULL,
    issuer             varchar(256),
    subject            varchar(256),
    serial_number      varchar(128),
    fingerprint_sha256 varchar(64),
    certificate_pem    text,
    not_before         timestamptz,
    issued_at          timestamptz,
    expires_at         timestamptz,
    revoked_at         timestamptz,
    status             varchar(16)  NOT NULL DEFAULT 'ERROR',
    auto_renew         boolean      NOT NULL DEFAULT true,
    renew_before_days  integer      NOT NULL DEFAULT 30,
    last_error         text,
    created_by         varchar(128) NOT NULL,
    created_at         timestamptz  NOT NULL DEFAULT now(),
    updated_at         timestamptz  NOT NULL DEFAULT now(),
    version            bigint       NOT NULL DEFAULT 0,
    CONSTRAINT ck_certificate_provider CHECK (provider IN ('ACME','MANUAL')),
    CONSTRAINT ck_certificate_status CHECK (status IN ('VALID','EXPIRING_SOON','EXPIRED','REVOKED','ERROR')),
    CONSTRAINT ck_certificate_renew_days CHECK (renew_before_days BETWEEN 1 AND 89)
);
CREATE INDEX idx_certificates_expiry ON certificates (expires_at)
    WHERE status IN ('VALID','EXPIRING_SOON') AND auto_renew;
-- The monitoring sweep's predicate: due for renewal, or with a stored status that has drifted
-- from what the dates say.
CREATE INDEX idx_certificates_renewal ON certificates (expires_at)
    WHERE revoked_at IS NULL AND expires_at IS NOT NULL;
--rollback DROP TABLE certificates;

--changeset enginx:baseline-021-certificate-domains
--comment Recorded from the issued certificate's subject alternative names, not from what was
--comment requested. If an authority issues something narrower, the platform must know what it
--comment actually holds or it will attach the certificate to a site it does not cover.
CREATE TABLE certificate_domains (
    id             uuid         PRIMARY KEY,
    certificate_id uuid         NOT NULL REFERENCES certificates(id) ON DELETE CASCADE,
    domain         varchar(255) NOT NULL,
    wildcard       boolean      NOT NULL DEFAULT false,
    CONSTRAINT uq_certificate_domain UNIQUE (certificate_id, domain),
    CONSTRAINT ck_certificate_domain_lower CHECK (domain = lower(domain))
);
CREATE INDEX idx_certificate_domains_domain ON certificate_domains (domain);
--rollback DROP TABLE certificate_domains;

--changeset enginx:baseline-022-certificate-secrets
--comment Private keys, under envelope encryption. A separate table from certificates so that
--comment writing certificate metadata can never rewrite or blank the key, and so the common read
--comment path does not load key material at all.
CREATE TABLE certificate_secrets (
    certificate_id  uuid        PRIMARY KEY REFERENCES certificates(id) ON DELETE CASCADE,
    ciphertext      bytea       NOT NULL,
    wrapped_dek     bytea       NOT NULL,
    -- Which key-encryption key wrapped the data key. Recorded per row so a rotation can proceed
    -- gradually instead of requiring every secret to be rewritten at once.
    kek_id          varchar(64) NOT NULL,
    cipher          varchar(32) NOT NULL DEFAULT 'AES-256-GCM',
    iv              bytea       NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);
--rollback DROP TABLE certificate_secrets;

--changeset enginx:baseline-023-certificate-orders
--comment One row per issuance attempt, successful or not. ACME failures are the kind that need
--comment explaining days later, and rate limits make the history of attempts operationally
--comment important in its own right.
CREATE TABLE certificate_orders (
    id               uuid         PRIMARY KEY,
    certificate_id   uuid         NOT NULL REFERENCES certificates(id) ON DELETE CASCADE,
    directory_url    varchar(512) NOT NULL,
    account_url      varchar(512),
    order_url        varchar(512),
    challenge_type   varchar(16)  NOT NULL,
    status           varchar(24)  NOT NULL,
    error_message    text,
    started_at       timestamptz  NOT NULL DEFAULT now(),
    completed_at     timestamptz,
    CONSTRAINT ck_order_challenge CHECK (challenge_type IN ('HTTP-01','DNS-01')),
    CONSTRAINT ck_order_status CHECK (status IN ('PENDING','VALIDATING','ISSUED','FAILED'))
);
CREATE INDEX idx_certificate_orders_cert ON certificate_orders (certificate_id, started_at DESC);
--rollback DROP TABLE certificate_orders;

--changeset enginx:baseline-024-acme-accounts
--comment The ACME account key, encrypted the same way certificate keys are. It identifies the
--comment platform to the authority and must survive restarts: registering a new account on every
--comment boot would burn the authority's account-creation rate limit within a day.
CREATE TABLE acme_accounts (
    id             uuid         PRIMARY KEY,
    directory_url  varchar(512) NOT NULL,
    account_url    varchar(512),
    contact_email  varchar(256),
    ciphertext     bytea        NOT NULL,
    wrapped_dek    bytea        NOT NULL,
    kek_id         varchar(64)  NOT NULL,
    cipher         varchar(32)  NOT NULL DEFAULT 'AES-256-GCM',
    iv             bytea        NOT NULL,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_acme_account_directory UNIQUE (directory_url)
);
--rollback DROP TABLE acme_accounts;

--changeset enginx:baseline-030-proxy-sites
--comment The aggregate root. admin_state records intent, status records observation (AD-6): an
--comment operator disabling a site and a site expiring are different facts and are stored apart.
CREATE TABLE proxy_sites (
    id                  uuid         PRIMARY KEY,
    name                varchar(128) NOT NULL,
    domain              varchar(253) NOT NULL,
    domain_reversed     varchar(253) NOT NULL,
    nginx_instance_id   uuid         NOT NULL REFERENCES nginx_instances(id) ON DELETE RESTRICT,
    admin_state         varchar(16)  NOT NULL DEFAULT 'ENABLED',
    status              varchar(16)  NOT NULL DEFAULT 'PENDING',
    active_from         timestamptz,
    expires_at          timestamptz,
    ssl_enabled         boolean      NOT NULL DEFAULT false,
    force_https         boolean      NOT NULL DEFAULT false,
    hsts_enabled        boolean      NOT NULL DEFAULT false,
    websocket_enabled   boolean      NOT NULL DEFAULT false,
    ssl_certificate_id  uuid         REFERENCES certificates(id) ON DELETE RESTRICT,
    lb_method           varchar(24)  NOT NULL DEFAULT 'ROUND_ROBIN',
    connect_timeout_s   integer      NOT NULL DEFAULT 60,
    read_timeout_s      integer      NOT NULL DEFAULT 60,
    send_timeout_s      integer      NOT NULL DEFAULT 60,
    max_body_size_bytes bigint       NOT NULL DEFAULT 1048576,
    created_by          varchar(128) NOT NULL,
    created_at          timestamptz  NOT NULL DEFAULT now(),
    updated_by          varchar(128) NOT NULL,
    updated_at          timestamptz  NOT NULL DEFAULT now(),
    version             bigint       NOT NULL DEFAULT 0,
    CONSTRAINT ck_site_admin_state CHECK (admin_state IN ('ENABLED','DISABLED')),
    CONSTRAINT ck_site_status      CHECK (status IN ('PENDING','ACTIVE','DISABLED','EXPIRED','ERROR')),
    CONSTRAINT ck_site_lb_method   CHECK (lb_method IN ('ROUND_ROBIN','LEAST_CONN','IP_HASH')),
    CONSTRAINT ck_site_domain_lower CHECK (domain = lower(domain)),
    CONSTRAINT ck_site_window      CHECK (active_from IS NULL OR expires_at IS NULL OR active_from < expires_at),
    CONSTRAINT ck_site_ssl         CHECK (NOT ssl_enabled OR ssl_certificate_id IS NOT NULL),
    CONSTRAINT ck_site_timeouts    CHECK (connect_timeout_s BETWEEN 1 AND 3600
                                      AND read_timeout_s    BETWEEN 1 AND 3600
                                      AND send_timeout_s    BETWEEN 1 AND 3600),
    CONSTRAINT ck_site_body_size   CHECK (max_body_size_bytes BETWEEN 0 AND 10737418240)
);

-- A domain is unique per instance, not globally: the same name may legitimately be served by
-- two independent NGINX estates.
CREATE UNIQUE INDEX uq_proxy_sites_instance_domain ON proxy_sites (nginx_instance_id, domain);

-- Partial indexes for the lifecycle sweep: it scans exactly these two predicates, so the sweep
-- costs one index range scan per run however many sites exist.
CREATE INDEX idx_proxy_sites_expiring ON proxy_sites (expires_at)
    WHERE status = 'ACTIVE' AND expires_at IS NOT NULL;
CREATE INDEX idx_proxy_sites_pending ON proxy_sites (active_from)
    WHERE status = 'PENDING' AND active_from IS NOT NULL;

CREATE INDEX idx_proxy_sites_instance ON proxy_sites (nginx_instance_id, status);

-- text_pattern_ops turns the wildcard permission match into an indexed prefix scan.
CREATE INDEX idx_proxy_sites_domain_reversed ON proxy_sites (domain_reversed text_pattern_ops);
--rollback DROP TABLE proxy_sites;

--changeset enginx:baseline-031-proxy-site-upstreams
--comment Scheme, host and port are stored apart so a URL can never reach proxy_pass whole.
CREATE TABLE proxy_site_upstreams (
    id             uuid         PRIMARY KEY,
    proxy_site_id  uuid         NOT NULL REFERENCES proxy_sites(id) ON DELETE CASCADE,
    scheme         varchar(8)   NOT NULL DEFAULT 'http',
    host           varchar(253) NOT NULL,
    port           integer      NOT NULL,
    weight         integer      NOT NULL DEFAULT 1,
    max_fails      integer      NOT NULL DEFAULT 3,
    fail_timeout_s integer      NOT NULL DEFAULT 10,
    backup         boolean      NOT NULL DEFAULT false,
    sort_order     integer      NOT NULL DEFAULT 0,
    CONSTRAINT ck_upstream_scheme CHECK (scheme IN ('http','https')),
    CONSTRAINT ck_upstream_port   CHECK (port BETWEEN 1 AND 65535),
    CONSTRAINT ck_upstream_weight CHECK (weight BETWEEN 1 AND 100),
    CONSTRAINT ck_upstream_fails  CHECK (max_fails BETWEEN 0 AND 100),
    CONSTRAINT ck_upstream_ftime  CHECK (fail_timeout_s BETWEEN 1 AND 3600),
    CONSTRAINT uq_upstream UNIQUE (proxy_site_id, scheme, host, port)
);
CREATE INDEX idx_upstreams_site ON proxy_site_upstreams (proxy_site_id);
--rollback DROP TABLE proxy_site_upstreams;

--changeset enginx:baseline-032-proxy-site-headers
--comment The value constraint mirrors the domain invariant in ProxySiteHeader. Enforcing it in
--comment both places is intentional: a bypass here would be remote code execution on the host.
CREATE TABLE proxy_site_headers (
    id            uuid          PRIMARY KEY,
    proxy_site_id uuid          NOT NULL REFERENCES proxy_sites(id) ON DELETE CASCADE,
    direction     varchar(16)   NOT NULL,
    header_name   varchar(128)  NOT NULL,
    header_value  varchar(1024) NOT NULL,
    CONSTRAINT ck_header_direction CHECK (direction IN ('REQUEST','RESPONSE')),
    CONSTRAINT ck_header_name  CHECK (header_name ~ '^[A-Za-z0-9!#$%&''*+._|~-]{1,128}$'),
    CONSTRAINT ck_header_value CHECK (header_value !~ '[\r\n;{}"\\$]'),
    CONSTRAINT uq_header UNIQUE (proxy_site_id, direction, header_name)
);
CREATE INDEX idx_headers_site ON proxy_site_headers (proxy_site_id);
--rollback DROP TABLE proxy_site_headers;

--changeset enginx:baseline-033-proxy-site-locations
CREATE TABLE proxy_site_locations (
    id            uuid         PRIMARY KEY,
    proxy_site_id uuid         NOT NULL REFERENCES proxy_sites(id) ON DELETE CASCADE,
    path_pattern  varchar(256) NOT NULL,
    match_type    varchar(16)  NOT NULL DEFAULT 'PREFIX',
    sort_order    integer      NOT NULL DEFAULT 0,
    CONSTRAINT ck_location_match CHECK (match_type IN ('PREFIX','EXACT')),
    CONSTRAINT ck_location_path  CHECK (path_pattern ~ '^/[A-Za-z0-9._~/*-]{0,255}$'),
    CONSTRAINT uq_location UNIQUE (proxy_site_id, match_type, path_pattern)
);
CREATE INDEX idx_locations_site ON proxy_site_locations (proxy_site_id);
--rollback DROP TABLE proxy_site_locations;

--changeset enginx:baseline-034-proxy-site-notifications
--comment Who to tell about one site, and whether to tell them at all.
--comment
--comment A table of its own rather than columns on proxy_sites, and that is a design decision
--comment rather than tidiness. proxy_sites holds the desired configuration -- the input to the
--comment renderer, and the thing an audit trail describes as "the site changed". Who receives an
--comment expiry warning is neither. Putting it there would mean adding an address shows up as a
--comment configuration change, takes the site's optimistic lock, and needs the authority to alter
--comment routing.
--comment
--comment Defaults to enabled, because that is what every existing site already does: the operator
--comment addresses are told about everything. This adds an opt-out and a way to widen the list,
--comment not a new requirement to configure something before it works.
CREATE TABLE proxy_site_notifications (
    proxy_site_id  uuid         PRIMARY KEY,
    expiry_enabled boolean      NOT NULL DEFAULT true,
    updated_by     varchar(128),
    updated_at     timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT fk_site_notifications_site FOREIGN KEY (proxy_site_id)
        REFERENCES proxy_sites (id) ON DELETE CASCADE
);

--rollback DROP TABLE proxy_site_notifications;

--changeset enginx:baseline-035-proxy-site-notification-subscribers
--comment Addresses told about this site in addition to the operator list.
--comment
--comment A child table rather than a delimited column: an address is a value the platform sends
--comment mail to, and a list that has to be split on a separator is one where a stray comma
--comment silently produces an address nobody will ever receive at.
--comment
--comment Lowercased by the application before it arrives, so the primary key is what stops the
--comment same person being added twice under different capitalisation and then told twice.
CREATE TABLE proxy_site_notification_subscribers (
    proxy_site_id uuid         NOT NULL,
    email         varchar(256) NOT NULL,
    PRIMARY KEY (proxy_site_id, email),
    CONSTRAINT ck_site_subscriber_email CHECK (email = lower(email) AND email LIKE '%@%'),
    CONSTRAINT fk_site_subscribers_site FOREIGN KEY (proxy_site_id)
        REFERENCES proxy_sites (id) ON DELETE CASCADE
);
--rollback DROP TABLE proxy_site_notification_subscribers;

--changeset enginx:baseline-040-domain-groups
--comment Hierarchy is a materialised path in varchar rather than an ltree. Descendants are a
--comment prefix scan and ancestors are computed by splitting the string, so the extension buys
--comment nothing here and would need privileges a managed database may not grant.
CREATE TABLE domain_groups (
    id          uuid         PRIMARY KEY,
    parent_id   uuid         REFERENCES domain_groups(id) ON DELETE RESTRICT,
    name        varchar(128) NOT NULL,
    path        varchar(520) NOT NULL,
    description varchar(512),
    created_by  varchar(128) NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    version     bigint       NOT NULL DEFAULT 0,
    CONSTRAINT ck_group_path_shape CHECK (path ~ '^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?){0,7}$')
);
CREATE UNIQUE INDEX uq_domain_groups_path ON domain_groups (path);
-- text_pattern_ops so `path LIKE 'production.%'` uses the index instead of scanning.
CREATE INDEX idx_domain_groups_path_prefix ON domain_groups (path text_pattern_ops);
CREATE INDEX idx_domain_groups_parent ON domain_groups (parent_id);
--rollback DROP TABLE domain_groups;

--changeset enginx:baseline-041-domain-group-members
CREATE TABLE domain_group_members (
    id              uuid PRIMARY KEY,
    domain_group_id uuid NOT NULL REFERENCES domain_groups(id) ON DELETE CASCADE,
    proxy_site_id   uuid NOT NULL REFERENCES proxy_sites(id)   ON DELETE CASCADE,
    added_by        varchar(128) NOT NULL,
    added_at        timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_domain_group_member UNIQUE (domain_group_id, proxy_site_id)
);
CREATE INDEX idx_group_members_site  ON domain_group_members (proxy_site_id);
CREATE INDEX idx_group_members_group ON domain_group_members (domain_group_id);
--rollback DROP TABLE domain_group_members;

--changeset enginx:baseline-042-identity-mirror
--comment A mirror of Keycloak, for grant authoring and display only. Authorization never reads
--comment it: group membership comes from the access token on every request, so a stale mirror
--comment cannot widen anyone's access.
CREATE TABLE app_users (
    id               uuid         PRIMARY KEY,
    keycloak_subject varchar(128) NOT NULL,
    username         varchar(128) NOT NULL,
    email            varchar(256),
    display_name     varchar(256),
    enabled          boolean      NOT NULL DEFAULT true,
    last_login_at    timestamptz,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_app_users_subject ON app_users (keycloak_subject);
CREATE INDEX idx_app_users_username ON app_users (username);

CREATE TABLE app_groups (
    id                  uuid         PRIMARY KEY,
    keycloak_group_path varchar(512) NOT NULL,
    name                varchar(128) NOT NULL,
    last_seen_at        timestamptz  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_app_groups_path ON app_groups (keycloak_group_path);
--rollback DROP TABLE app_users; DROP TABLE app_groups;

--changeset enginx:baseline-043-permission-grants
--comment subject_ref holds a Keycloak identifier: the `sub` claim for a USER grant, the group
--comment path for a GROUP grant. Keying grants by a mirrored row id instead would make every
--comment authorization decision depend on the mirror being fresh, which is exactly the staleness
--comment the token-based membership rule exists to avoid.
CREATE TABLE permission_grants (
    id               uuid         PRIMARY KEY,
    subject_type     varchar(16)  NOT NULL,
    subject_ref      varchar(512) NOT NULL,
    scope_type       varchar(24)  NOT NULL,
    scope_group_id   uuid         REFERENCES domain_groups(id) ON DELETE CASCADE,
    scope_site_id    uuid         REFERENCES proxy_sites(id)   ON DELETE CASCADE,
    domain_pattern   varchar(255),
    pattern_reversed varchar(255),
    permission_level varchar(16)  NOT NULL,
    granted_by       varchar(128) NOT NULL,
    granted_at       timestamptz  NOT NULL DEFAULT now(),
    expires_at       timestamptz,
    CONSTRAINT ck_grant_subject_type CHECK (subject_type IN ('USER','GROUP')),
    CONSTRAINT ck_grant_scope_type   CHECK (scope_type IN ('GLOBAL','DOMAIN_GROUP','DOMAIN_PATTERN','SITE')),
    CONSTRAINT ck_grant_level        CHECK (permission_level IN ('READ','OPERATE','MANAGE','ADMIN')),
    -- Exactly one scope reference, matching the scope type. Enforced here as well as in the
    -- aggregate: a malformed grant is a silent authorization bug, not a validation nuisance.
    CONSTRAINT ck_grant_scope_reference CHECK (
        (scope_type = 'GLOBAL'
             AND scope_group_id IS NULL AND scope_site_id IS NULL AND pattern_reversed IS NULL) OR
        (scope_type = 'DOMAIN_GROUP'
             AND scope_group_id IS NOT NULL AND scope_site_id IS NULL AND pattern_reversed IS NULL) OR
        (scope_type = 'SITE'
             AND scope_site_id IS NOT NULL AND scope_group_id IS NULL AND pattern_reversed IS NULL) OR
        (scope_type = 'DOMAIN_PATTERN'
             AND pattern_reversed IS NOT NULL AND domain_pattern IS NOT NULL
             AND scope_group_id IS NULL AND scope_site_id IS NULL))
);

-- One grant per subject and scope. A second grant on the same scope would be a silent duplicate
-- whose only effect is whichever level is higher, which is confusing to audit.
CREATE UNIQUE INDEX uq_grant_global  ON permission_grants (subject_type, subject_ref)
    WHERE scope_type = 'GLOBAL';
CREATE UNIQUE INDEX uq_grant_group   ON permission_grants (subject_type, subject_ref, scope_group_id)
    WHERE scope_type = 'DOMAIN_GROUP';
CREATE UNIQUE INDEX uq_grant_site    ON permission_grants (subject_type, subject_ref, scope_site_id)
    WHERE scope_type = 'SITE';
CREATE UNIQUE INDEX uq_grant_pattern ON permission_grants (subject_type, subject_ref, pattern_reversed)
    WHERE scope_type = 'DOMAIN_PATTERN';

-- The hot path: every request loads this caller's grants by subject reference.
CREATE INDEX idx_grants_subject_ref ON permission_grants (subject_ref);
CREATE INDEX idx_grants_scope_group ON permission_grants (scope_group_id) WHERE scope_group_id IS NOT NULL;
CREATE INDEX idx_grants_scope_site  ON permission_grants (scope_site_id)  WHERE scope_site_id IS NOT NULL;
--rollback DROP TABLE permission_grants;

--changeset enginx:baseline-044-local-users
--comment Accounts the platform authenticates itself, for deployments that do not want to stand up
--comment an identity provider.
--comment
--comment Deliberately a separate table from app_users. That one mirrors Keycloak for grant
--comment authoring and display and is authoritative for nothing; this one holds credentials and is
--comment authoritative for everything about the account. Merging them would put a password hash in
--comment a table a synchronisation job overwrites.
CREATE TABLE local_users (
    id            uuid         PRIMARY KEY,
    username      varchar(128) NOT NULL,
    -- BCrypt, which carries its own salt and cost factor in the string. Never the password.
    password_hash varchar(255) NOT NULL,
    email         varchar(256),
    display_name  varchar(256),
    enabled       boolean      NOT NULL DEFAULT true,
    -- Forces a change at next login. Set for the bootstrap account, whose password came from
    -- configuration and has therefore been readable by anything that can read configuration.
    must_change_password boolean NOT NULL DEFAULT false,
    last_login_at timestamptz,
    created_by    varchar(128) NOT NULL,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now(),
    version       bigint       NOT NULL DEFAULT 0,
    CONSTRAINT ck_local_user_username CHECK (username = lower(username) AND length(username) >= 3)
);

-- Case-insensitivity is enforced by the check constraint above rather than by a functional index,
-- so two accounts cannot differ only by case — which is the shape of a convincing impersonation.
CREATE UNIQUE INDEX uq_local_users_username ON local_users (username);
--rollback DROP TABLE local_users;

--changeset enginx:baseline-045-local-user-roles
--comment Global roles, the same set Keycloak realm roles map onto. A local account and a
--comment federated one are therefore indistinguishable to every authorization decision, which is
--comment what keeps one permission model rather than two.
CREATE TABLE local_user_roles (
    local_user_id uuid        NOT NULL REFERENCES local_users(id) ON DELETE CASCADE,
    role          varchar(32) NOT NULL,
    CONSTRAINT pk_local_user_roles PRIMARY KEY (local_user_id, role),
    CONSTRAINT ck_local_user_role CHECK (role IN ('SUPER_ADMIN','ADMIN','OPERATOR','READ_ONLY'))
);
--rollback DROP TABLE local_user_roles;

--changeset enginx:baseline-046-local-user-groups
--comment Group membership, so a grant made to a group reaches local accounts too. The value is a
--comment group path in the same form Keycloak emits, because permission_grants stores that path
--comment for a GROUP grant and the evaluator compares strings.
CREATE TABLE local_user_groups (
    local_user_id uuid         NOT NULL REFERENCES local_users(id) ON DELETE CASCADE,
    group_path    varchar(512) NOT NULL,
    CONSTRAINT pk_local_user_groups PRIMARY KEY (local_user_id, group_path)
);
CREATE INDEX idx_local_user_groups_path ON local_user_groups (group_path);
--rollback DROP TABLE local_user_groups;

--changeset enginx:baseline-050-config-bundles
--comment The deployment unit is the whole instance (AD-3), so a bundle is the complete intended
--comment configuration for one host. content_hash makes it content-addressed: a deployment whose
--comment hash already matches what is running is a no-op, which is what makes redeploys idempotent.
CREATE TABLE config_bundles (
    id                uuid         PRIMARY KEY,
    nginx_instance_id uuid         NOT NULL REFERENCES nginx_instances(id) ON DELETE CASCADE,
    sequence          bigint       NOT NULL,
    content_hash      varchar(64)  NOT NULL,
    render_status     varchar(16)  NOT NULL DEFAULT 'RENDERED',
    site_ids_snapshot jsonb        NOT NULL,
    created_by        varchar(128) NOT NULL,
    created_at        timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_bundle_status CHECK (render_status IN ('RENDERED','VALIDATED','ACTIVE','SUPERSEDED','FAILED')),
    CONSTRAINT uq_bundle_sequence UNIQUE (nginx_instance_id, sequence),
    CONSTRAINT uq_bundle_hash     UNIQUE (nginx_instance_id, content_hash)
);
CREATE INDEX idx_bundles_instance ON config_bundles (nginx_instance_id, sequence DESC);

-- At most one active bundle per instance. Enforced by the database rather than by convention,
-- because "which configuration is this host serving?" must have exactly one answer.
CREATE UNIQUE INDEX uq_bundle_active_per_instance ON config_bundles (nginx_instance_id)
    WHERE render_status = 'ACTIVE';
--rollback DROP TABLE config_bundles;

--changeset enginx:baseline-051-config-bundle-files
CREATE TABLE config_bundle_files (
    id               uuid         PRIMARY KEY,
    config_bundle_id uuid         NOT NULL REFERENCES config_bundles(id) ON DELETE CASCADE,
    relative_path    varchar(512) NOT NULL,
    content          text         NOT NULL,
    sha256           varchar(64)  NOT NULL,
    -- Private key material. Never returned by a read endpoint and never written to a log.
    sensitive        boolean      NOT NULL DEFAULT false,
    CONSTRAINT uq_bundle_file UNIQUE (config_bundle_id, relative_path)
);
CREATE INDEX idx_bundle_files_bundle ON config_bundle_files (config_bundle_id);
--rollback DROP TABLE config_bundle_files;

--changeset enginx:baseline-052-deployments
CREATE TABLE deployments (
    id                 uuid         PRIMARY KEY,
    nginx_instance_id  uuid         NOT NULL REFERENCES nginx_instances(id) ON DELETE CASCADE,
    -- Nullable until the dispatcher renders. The bundle is deliberately NOT built when the
    -- deployment is requested: two edits committed seconds apart would each snapshot the state
    -- they saw, and applying them out of order would silently revert the later one
    -- (architecture risk R2). The dispatcher renders from current state under a per-instance
    -- lock, so what is applied is always what the database says right now.
    config_bundle_id   uuid         REFERENCES config_bundles(id) ON DELETE RESTRICT,
    previous_bundle_id uuid         REFERENCES config_bundles(id) ON DELETE SET NULL,
    trigger_type       varchar(24)  NOT NULL,
    status             varchar(16)  NOT NULL DEFAULT 'PENDING',
    attempt            integer      NOT NULL DEFAULT 0,
    -- The deployment id. Every retry reuses it, so a response lost in flight cannot make the
    -- agent apply the same bundle twice.
    idempotency_key    varchar(64)  NOT NULL UNIQUE,
    nginx_test_output  text,
    error_message      text,
    started_at         timestamptz,
    finished_at        timestamptz,
    created_by         varchar(128) NOT NULL,
    created_at         timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_deployment_trigger CHECK (trigger_type IN
        ('MANUAL','EXPIRATION','ACTIVATION','CERT_RENEWAL','ROLLBACK','RECONCILE')),
    CONSTRAINT ck_deployment_status CHECK (status IN
        ('PENDING','IN_PROGRESS','SUCCESS','FAILED','CANCELLED'))
);
CREATE INDEX idx_deployments_instance ON deployments (nginx_instance_id, created_at DESC);
CREATE INDEX idx_deployments_unfinished ON deployments (nginx_instance_id)
    WHERE status IN ('PENDING','IN_PROGRESS');
--rollback DROP TABLE deployments;

--changeset enginx:baseline-053-deployment-events
--comment One row per phase, so a failure says where it happened rather than only that it did.
--comment VERIFY is the post-reload loopback probe: it can record FAILURE without failing the
--comment deployment, because the configuration is loaded either way.
CREATE TABLE deployment_events (
    id            uuid        PRIMARY KEY,
    deployment_id uuid        NOT NULL REFERENCES deployments(id) ON DELETE CASCADE,
    phase         varchar(16) NOT NULL,
    result        varchar(16) NOT NULL,
    detail        text,
    at            timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_event_phase  CHECK (phase IN
        ('RENDER','UPLOAD','VALIDATE','ACTIVATE','RELOAD','VERIFY','ROLLBACK')),
    CONSTRAINT ck_event_result CHECK (result IN ('STARTED','SUCCESS','FAILURE','SKIPPED'))
);
CREATE INDEX idx_deployment_events_deployment ON deployment_events (deployment_id, at);
--rollback DROP TABLE deployment_events;

--changeset enginx:baseline-054-outbox
--comment Work committed in the same local transaction as the state change that caused it (AD-5),
--comment so a database commit and a remote call cannot race.
CREATE TABLE outbox_messages (
    id              uuid        PRIMARY KEY,
    aggregate_type  varchar(32) NOT NULL,
    aggregate_id    uuid        NOT NULL,
    message_type    varchar(64) NOT NULL,
    status          varchar(16) NOT NULL DEFAULT 'NEW',
    attempts        integer     NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    last_error      text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_outbox_status CHECK (status IN ('NEW','IN_PROGRESS','DONE','DEAD'))
);
-- The dispatcher's claim query. Partial, because DONE rows are the overwhelming majority and are
-- never scanned again.
CREATE INDEX idx_outbox_due ON outbox_messages (next_attempt_at)
    WHERE status IN ('NEW','IN_PROGRESS');
--rollback DROP TABLE outbox_messages;

--changeset enginx:baseline-055-deployment-links
--comment These close reference cycles, so they cannot be columns on the tables above: a bundle
--comment belongs to an instance and an instance points at its active bundle.
ALTER TABLE nginx_instances ADD COLUMN active_bundle_id uuid
    REFERENCES config_bundles(id) ON DELETE SET NULL;
-- What the agent last reported. Diverging from active_bundle_id is configuration drift, which is
-- reported rather than silently corrected (architecture risk R4).
ALTER TABLE nginx_instances ADD COLUMN observed_bundle_id varchar(64);
ALTER TABLE proxy_sites ADD COLUMN last_deployment_id uuid
    REFERENCES deployments(id) ON DELETE SET NULL;
--rollback ALTER TABLE proxy_sites DROP COLUMN last_deployment_id;
--rollback ALTER TABLE nginx_instances DROP COLUMN observed_bundle_id;
--rollback ALTER TABLE nginx_instances DROP COLUMN active_bundle_id;

--changeset enginx:baseline-056-agent-jobs
--comment Work waiting for a host that calls in.
--comment
--comment The outbox stays the trigger for a deployment; this is what a pull host collects. The two
--comment are not the same queue and should not be merged: an outbox row is a job the server owes
--comment itself, drained by whichever replica claims it, while these are owed to one specific host
--comment and nobody else may run them.
--comment
--comment Leases rather than SKIP LOCKED, because the worker is not a thread in this process. A row
--comment is handed out for a bounded time and returns to QUEUED if no result arrives, so an agent
--comment that dies mid-job strands nothing.
CREATE TABLE agent_jobs (
    id                uuid         PRIMARY KEY,
    nginx_instance_id uuid         NOT NULL,
    -- Null for work that belongs to no deployment. Set null rather than cascading on delete: a
    -- job that already ran is a record of what happened to a host, and outlives the deployment
    -- that asked for it.
    deployment_id     uuid,
    type              varchar(32)  NOT NULL,
    payload           jsonb        NOT NULL,
    status            varchar(16)  NOT NULL DEFAULT 'QUEUED',
    -- When the current lease runs out. Null unless LEASED.
    lease_expires_at  timestamptz,
    attempts          integer      NOT NULL DEFAULT 0,
    result            jsonb,
    error             text,
    created_at        timestamptz  NOT NULL DEFAULT now(),
    updated_at        timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_agent_job_status CHECK (status IN ('QUEUED', 'LEASED', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_agent_job_lease CHECK (
        (status = 'LEASED' AND lease_expires_at IS NOT NULL)
        OR (status <> 'LEASED' AND lease_expires_at IS NULL)),
    CONSTRAINT fk_agent_jobs_instance FOREIGN KEY (nginx_instance_id)
        REFERENCES nginx_instances (id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_jobs_deployment FOREIGN KEY (deployment_id)
        REFERENCES deployments (id) ON DELETE SET NULL
);

-- The claim query: oldest waiting job for one host. Partial, because finished jobs accumulate and
-- none of them is ever a candidate again.
CREATE INDEX ix_agent_jobs_claimable ON agent_jobs (nginx_instance_id, created_at)
    WHERE status IN ('QUEUED', 'LEASED');

CREATE INDEX ix_agent_jobs_deployment ON agent_jobs (deployment_id)
    WHERE deployment_id IS NOT NULL;

--rollback DROP TABLE agent_jobs;

--changeset enginx:baseline-057-agent-jobs-single-outstanding
--comment At most one job outstanding per host.
--comment
--comment Risk R2 wants deployments serialised per instance, and this is where that is enforced for
--comment a pull host: two jobs in flight against one NGINX means two processes racing to swap the
--comment same symlink. A unique partial index states it as a database rule rather than leaving it
--comment to the claim query being written correctly every time.
CREATE UNIQUE INDEX ux_agent_jobs_one_leased_per_instance ON agent_jobs (nginx_instance_id)
    WHERE status = 'LEASED';
--rollback DROP INDEX ux_agent_jobs_one_leased_per_instance;

--changeset enginx:baseline-060-audit-logs
--comment Append-only audit trail, range-partitioned by month. A partitioned table requires the
--comment partition key in its primary key, so the key is (id, occurred_at) rather than (id).
--comment
--comment Monthly partitions keep each index small and turn retention into DROP TABLE rather than a
--comment mass DELETE, which the immutability trigger below forbids outright.
CREATE TABLE audit_logs (
    id             uuid          NOT NULL,
    occurred_at    timestamptz   NOT NULL DEFAULT now(),
    actor_subject  varchar(128),
    actor_username varchar(128),
    action         varchar(64)   NOT NULL,
    resource_type  varchar(64)   NOT NULL,
    resource_id    uuid,
    before_state   jsonb,
    after_state    jsonb,
    ip_address     varchar(64),
    user_agent     varchar(512),
    result         varchar(16)   NOT NULL,
    error_message  varchar(2048),
    trace_id       varchar(64),
    CONSTRAINT pk_audit_logs PRIMARY KEY (id, occurred_at),
    CONSTRAINT ck_audit_result CHECK (result IN ('SUCCESS','FAILURE','DENIED'))
) PARTITION BY RANGE (occurred_at);

-- A safety net beneath the monthly partitions. A row whose timestamp falls outside every one of
-- them must still be storable: losing an audit row is worse than an unevenly sized partition.
CREATE TABLE audit_logs_default PARTITION OF audit_logs DEFAULT;

CREATE INDEX idx_audit_resource ON audit_logs (resource_type, resource_id, occurred_at DESC);
CREATE INDEX idx_audit_actor    ON audit_logs (actor_subject, occurred_at DESC);
CREATE INDEX idx_audit_action   ON audit_logs (action, occurred_at DESC);
-- Supports the audit search: newest first, optionally narrowed by action or result.
CREATE INDEX idx_audit_occurred ON audit_logs (occurred_at DESC);
CREATE INDEX idx_audit_result   ON audit_logs (result, occurred_at DESC) WHERE result <> 'SUCCESS';
--rollback DROP TABLE audit_logs;

--changeset enginx:baseline-061-audit-immutability splitStatements:false
--comment Immutability enforced by the database, not only by the absence of an update method on the
--comment repository. A trigger cannot be bypassed by table ownership the way a REVOKE can.
CREATE OR REPLACE FUNCTION enginx_audit_logs_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_logs is append-only; % is not permitted', TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;
--rollback DROP FUNCTION IF EXISTS enginx_audit_logs_immutable();

--changeset enginx:baseline-062-audit-immutability-trigger
--comment Declared on the parent. PostgreSQL propagates a row trigger to every partition, including
--comment ones created later by the maintenance job, so a new month is never briefly mutable.
CREATE TRIGGER trg_audit_logs_immutable
    BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION enginx_audit_logs_immutable();
--rollback DROP TRIGGER IF EXISTS trg_audit_logs_immutable ON audit_logs;

--changeset enginx:baseline-063-audit-partition-function splitStatements:false
--comment Creates the monthly partitions ahead of time. Called daily by the audit-maintenance job,
--comment and once here so the first insert has somewhere to land.
CREATE OR REPLACE FUNCTION enginx_ensure_audit_partitions(months_ahead integer DEFAULT 2)
RETURNS integer AS $$
DECLARE
    start_month date := date_trunc('month', now())::date;
    target      date;
    created     integer := 0;
    i           integer;
BEGIN
    FOR i IN 0..months_ahead LOOP
        target := (start_month + (i || ' month')::interval)::date;

        -- IF NOT EXISTS so the job is idempotent: it runs daily and almost always finds the
        -- partitions already there.
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS audit_logs_%s PARTITION OF audit_logs FOR VALUES FROM (%L) TO (%L)',
            to_char(target, 'YYYY_MM'),
            target,
            (target + interval '1 month')::date);
        created := created + 1;
    END LOOP;
    RETURN created;
END;
$$ LANGUAGE plpgsql;
--rollback DROP FUNCTION IF EXISTS enginx_ensure_audit_partitions(integer);

--changeset enginx:baseline-064-audit-initial-partitions
--comment The current month and the next two. Everything after this is the scheduled job's work.
SELECT enginx_ensure_audit_partitions(2);
--rollback SELECT 1;

--changeset enginx:baseline-065-audit-retention-function splitStatements:false runOnChange:true
--comment Drops audit partitions entirely older than a cutoff, and reports what it dropped.
--comment
--comment This is the reason the table is partitioned. The immutability trigger forbids DELETE, so
--comment without partitioning there would be no way to enforce a retention policy at all short of
--comment disabling the guarantee that makes the trail worth keeping. Dropping a partition removes
--comment a month in constant time and never touches a row.
--comment
--comment Deliberately never touches audit_logs_default. Rows land there only when their timestamp
--comment falls outside every monthly partition, which means something unexpected happened; those
--comment are the last rows anyone should discard automatically.
CREATE OR REPLACE FUNCTION enginx_drop_audit_partitions_before(cutoff date)
RETURNS TABLE(dropped_partition text) AS $$
DECLARE
    part        record;
    upper_bound date;
BEGIN
    FOR part IN
        SELECT c.relname AS name,
               pg_get_expr(c.relpartbound, c.oid) AS bound
          FROM pg_class c
          JOIN pg_inherits i ON i.inhrelid = c.oid
          JOIN pg_class parent ON parent.oid = i.inhparent
         WHERE parent.relname = 'audit_logs'
           AND c.relname <> 'audit_logs_default'
         ORDER BY c.relname
    LOOP
        -- The partition's upper bound, parsed from its own definition rather than reconstructed
        -- from the name. A partition whose name and range disagree is exactly the case where
        -- guessing from the name would drop the wrong month.
        --
        -- The capture is deliberately "everything up to the closing quote" rather than a date
        -- shape: the partition key is timestamptz, so PostgreSQL renders bounds as
        -- '2024-02-01 00:00:00+00'. A date-only pattern matches nothing, and the function then
        -- silently drops nothing at all — a retention job that appears to work and does not.
        upper_bound := (regexp_match(part.bound, 'TO \(''([^'']+)''\)'))[1]::timestamptz::date;

        IF upper_bound IS NOT NULL AND upper_bound <= cutoff THEN
            EXECUTE format('DROP TABLE %I', part.name);
            dropped_partition := part.name;
            RETURN NEXT;
        END IF;
    END LOOP;
END;
$$ LANGUAGE plpgsql;
--rollback DROP FUNCTION IF EXISTS enginx_drop_audit_partitions_before(date);

--changeset enginx:baseline-070-notification-ledger
--comment A record of what has already been sent, so a condition that persists for days is
--comment reported once rather than on every scan.
--comment
--comment Every notifiable condition is found by a job on a timer. Without this table, "expires in
--comment 7 days" would be re-sent on every run for four days, and the recipients would filter the
--comment whole channel into a folder they stop reading — which is a worse outcome than sending
--comment nothing at all.
--comment
--comment `fingerprint` is what re-arms a notification. It holds a value identifying the underlying
--comment fact — a site's expiry instant, a certificate's expiry, the last time an agent was heard
--comment from. Extending a site's expiry changes the fingerprint, so the 7-day warning fires again
--comment for the new date; an agent that recovers and later fails again is a new episode. That
--comment happens without any code path having to remember to clear a flag, which is the version of
--comment this that quietly stops working.
CREATE TABLE notification_ledger (
    id            uuid          PRIMARY KEY,
    kind          varchar(48)   NOT NULL,
    resource_type varchar(48)   NOT NULL,
    resource_id   uuid          NOT NULL,
    -- '' rather than NULL when a kind has no threshold. PostgreSQL treats NULLs as distinct in a
    -- unique index, so a nullable column here would let every scan insert another row and the
    -- deduplication would silently do nothing.
    threshold     varchar(16)   NOT NULL DEFAULT '',
    fingerprint   varchar(128)  NOT NULL,
    status        varchar(16)   NOT NULL DEFAULT 'PENDING',
    recipients    varchar(1024),
    detail        text,
    created_at    timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT ck_notification_status CHECK (status IN ('PENDING','SENT','FAILED','SUPPRESSED'))
);

-- The deduplication itself. Claiming a notification is an INSERT that either succeeds or violates
-- this constraint, which makes "have we sent this already?" a single atomic operation rather than
-- a check followed by a write that two schedulers could interleave.
CREATE UNIQUE INDEX uq_notification_once
    ON notification_ledger (kind, resource_id, threshold, fingerprint);

-- For pruning old rows, and for showing an operator what was recently sent.
CREATE INDEX idx_notification_created ON notification_ledger (created_at DESC);
CREATE INDEX idx_notification_failed ON notification_ledger (created_at DESC) WHERE status = 'FAILED';
--rollback DROP TABLE notification_ledger;
