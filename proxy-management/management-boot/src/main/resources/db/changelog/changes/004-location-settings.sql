--liquibase formatted sql

--changeset enginx:004-010-location-settings
--comment How one location proxies, where it differs from its site. Every column is optional and a
--comment null one is the site's, so every existing location keeps proxying exactly as its site does.
--comment
--comment public_access lets a location of a protected site be reached without signing in, for a
--comment webhook or a health endpoint. The domain refuses it for the root and for the platform's own
--comment paths; ck_location_public_root repeats the first, which is the one that would quietly turn a
--comment site's protection off.
ALTER TABLE proxy_site_locations
    ADD COLUMN public_access         boolean     NOT NULL DEFAULT false,
    ADD COLUMN load_balancing_method varchar(16),
    ADD COLUMN connect_timeout_s     integer,
    ADD COLUMN read_timeout_s        integer,
    ADD COLUMN send_timeout_s        integer,
    ADD COLUMN max_body_size_bytes   bigint,
    ADD COLUMN websocket_enabled     boolean,
    ADD CONSTRAINT ck_location_lb CHECK (load_balancing_method IS NULL
        OR load_balancing_method IN ('ROUND_ROBIN','LEAST_CONN','IP_HASH')),
    ADD CONSTRAINT ck_location_timeouts CHECK (
        (connect_timeout_s IS NULL OR connect_timeout_s BETWEEN 1 AND 3600)
        AND (read_timeout_s IS NULL OR read_timeout_s BETWEEN 1 AND 3600)
        AND (send_timeout_s IS NULL OR send_timeout_s BETWEEN 1 AND 3600)),
    ADD CONSTRAINT ck_location_body_size CHECK (max_body_size_bytes IS NULL
        OR max_body_size_bytes BETWEEN 0 AND 10737418240),
    ADD CONSTRAINT ck_location_public_root CHECK (NOT (public_access AND path_pattern = '/'));
--rollback ALTER TABLE proxy_site_locations DROP CONSTRAINT ck_location_public_root, DROP CONSTRAINT ck_location_body_size, DROP CONSTRAINT ck_location_timeouts, DROP CONSTRAINT ck_location_lb, DROP COLUMN websocket_enabled, DROP COLUMN max_body_size_bytes, DROP COLUMN send_timeout_s, DROP COLUMN read_timeout_s, DROP COLUMN connect_timeout_s, DROP COLUMN load_balancing_method, DROP COLUMN public_access;

--changeset enginx:004-020-location-upstreams
--comment A location's own upstream pool, rendered as an upstream block of its own. The same
--comment constraints as proxy_site_upstreams: the host and port reach a generated file.
CREATE TABLE proxy_site_location_upstreams (
    id             uuid         PRIMARY KEY,
    location_id    uuid         NOT NULL REFERENCES proxy_site_locations(id) ON DELETE CASCADE,
    scheme         varchar(8)   NOT NULL DEFAULT 'http',
    host           varchar(253) NOT NULL,
    port           integer      NOT NULL,
    weight         integer      NOT NULL DEFAULT 1,
    max_fails      integer      NOT NULL DEFAULT 3,
    fail_timeout_s integer      NOT NULL DEFAULT 10,
    backup         boolean      NOT NULL DEFAULT false,
    sort_order     integer      NOT NULL DEFAULT 0,
    CONSTRAINT ck_location_upstream_scheme CHECK (scheme IN ('http','https')),
    CONSTRAINT ck_location_upstream_port   CHECK (port BETWEEN 1 AND 65535),
    CONSTRAINT ck_location_upstream_weight CHECK (weight BETWEEN 1 AND 100),
    CONSTRAINT ck_location_upstream_fails  CHECK (max_fails BETWEEN 0 AND 100),
    CONSTRAINT ck_location_upstream_ftime  CHECK (fail_timeout_s BETWEEN 1 AND 3600),
    CONSTRAINT uq_location_upstream UNIQUE (location_id, scheme, host, port)
);
CREATE INDEX idx_location_upstreams_location ON proxy_site_location_upstreams (location_id);
--rollback DROP TABLE proxy_site_location_upstreams;

--changeset enginx:004-030-location-headers
--comment Request headers a location adds to its site's. The value constraint mirrors the domain
--comment invariant in ProxySiteHeader, as proxy_site_headers' does: a bypass here would be remote
--comment code execution on the host.
CREATE TABLE proxy_site_location_headers (
    id           uuid          PRIMARY KEY,
    location_id  uuid          NOT NULL REFERENCES proxy_site_locations(id) ON DELETE CASCADE,
    header_name  varchar(128)  NOT NULL,
    header_value varchar(1024) NOT NULL,
    CONSTRAINT ck_location_header_name  CHECK (header_name ~ '^[A-Za-z0-9!#$%&''*+._|~-]{1,128}$'),
    CONSTRAINT ck_location_header_value CHECK (header_value !~ '[\r\n;{}"\\$]'),
    CONSTRAINT uq_location_header UNIQUE (location_id, header_name)
);
CREATE INDEX idx_location_headers_location ON proxy_site_location_headers (location_id);
--rollback DROP TABLE proxy_site_location_headers;
