--liquibase formatted sql

--changeset enginx:pull-agents-010-connectivity-mode
--comment How the platform and a host reach each other.
--comment
--comment PUSH is the original model: the management server dials the agent at agent_base_url over
--comment mTLS and pins its certificate fingerprint. It needs inbound 8443 on every host, reachable
--comment from the management plane, which a host behind NAT or in another network cannot offer.
--comment
--comment PULL inverts the connection: the agent registers itself with a token and long-polls for
--comment work, so nothing dials the host at all. Both remain supported, per instance, because the
--comment push contract is documented and deployed and removing it would strand every existing host.
ALTER TABLE nginx_instances
    ADD COLUMN connectivity_mode varchar(8) NOT NULL DEFAULT 'PUSH';

ALTER TABLE nginx_instances
    ADD CONSTRAINT ck_instance_connectivity_mode
        CHECK (connectivity_mode IN ('PUSH', 'PULL'));

--changeset enginx:pull-agents-020-push-fields-optional
--comment A pull-mode host has no URL to dial and no certificate to pin, so the two columns that
--comment define a push host must become nullable. The check constraint below is what keeps them
--comment from becoming merely optional: each mode requires exactly its own fields and refuses the
--comment other's, so a row cannot describe a host nobody can reach.
ALTER TABLE nginx_instances ALTER COLUMN agent_base_url DROP NOT NULL;
ALTER TABLE nginx_instances ALTER COLUMN agent_cert_fingerprint DROP NOT NULL;

ALTER TABLE nginx_instances
    ADD CONSTRAINT ck_instance_mode_fields CHECK (
        (connectivity_mode = 'PUSH'
            AND agent_base_url IS NOT NULL
            AND agent_cert_fingerprint IS NOT NULL)
        OR (connectivity_mode = 'PULL'
            AND agent_base_url IS NULL
            AND agent_cert_fingerprint IS NULL));

--changeset enginx:pull-agents-030-registration-tokens
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

--changeset enginx:pull-agents-040-agent-tokens
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
