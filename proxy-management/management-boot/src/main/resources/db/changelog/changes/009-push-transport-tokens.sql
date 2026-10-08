--liquibase formatted sql

--changeset enginx:push-transports-010-push-transport-column
--comment Adds push_transport and agent_auth_token to nginx_instances table.
ALTER TABLE nginx_instances
    ADD COLUMN push_transport varchar(16) NOT NULL DEFAULT 'MTLS',
    ADD COLUMN agent_auth_token varchar(512);

ALTER TABLE nginx_instances
    ADD CONSTRAINT ck_instance_push_transport
        CHECK (push_transport IN ('MTLS', 'HTTP_TOKEN', 'GRPC_TOKEN'));

--changeset enginx:push-transports-020-update-mode-fields-constraint
--comment Update check constraint to account for push transports with shared tokens.
ALTER TABLE nginx_instances DROP CONSTRAINT ck_instance_mode_fields;

ALTER TABLE nginx_instances
    ADD CONSTRAINT ck_instance_mode_fields CHECK (
        (connectivity_mode = 'PUSH'
            AND push_transport = 'MTLS'
            AND agent_base_url IS NOT NULL
            AND agent_cert_fingerprint IS NOT NULL)
        OR (connectivity_mode = 'PUSH'
            AND push_transport IN ('HTTP_TOKEN', 'GRPC_TOKEN')
            AND agent_base_url IS NOT NULL
            AND agent_auth_token IS NOT NULL)
        OR (connectivity_mode = 'PULL'
            AND agent_base_url IS NULL
            AND agent_cert_fingerprint IS NULL
            AND agent_auth_token IS NULL));
