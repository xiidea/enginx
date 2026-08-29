--liquibase formatted sql

--changeset enginx:local-users-010-table
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

--changeset enginx:local-users-011-roles
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

--changeset enginx:local-users-012-groups
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
