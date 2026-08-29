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
--comment SSH session; agent_base_url and the pinned certificate fingerprint are the whole contract.
CREATE TABLE nginx_instances (
    id                     uuid         PRIMARY KEY,
    name                   varchar(64)  NOT NULL,
    hostname               varchar(253) NOT NULL,
    agent_base_url         varchar(512) NOT NULL,
    agent_cert_fingerprint varchar(64)  NOT NULL,
    environment            varchar(32)  NOT NULL DEFAULT 'PRODUCTION',
    status                 varchar(16)  NOT NULL DEFAULT 'UNKNOWN',
    nginx_version          varchar(32),
    agent_version          varchar(32),
    last_seen_at           timestamptz,
    -- Set once config_bundles exists; see the deferred links changeset at the end of this file.
    created_at             timestamptz  NOT NULL DEFAULT now(),
    updated_at             timestamptz  NOT NULL DEFAULT now(),
    version                bigint       NOT NULL DEFAULT 0,
    CONSTRAINT uq_nginx_instance_name CHECK (name = lower(name)),
    CONSTRAINT ck_nginx_instance_status CHECK (status IN ('ONLINE','OFFLINE','DEGRADED','UNKNOWN')),
    CONSTRAINT ck_nginx_instance_fingerprint CHECK (agent_cert_fingerprint ~ '^[A-F0-9]{64}$')
);
CREATE UNIQUE INDEX uq_nginx_instances_name ON nginx_instances (name);
--rollback DROP TABLE nginx_instances;

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
