--liquibase formatted sql

--changeset enginx:agent-jobs-010-table
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

--changeset enginx:agent-jobs-020-single-outstanding
--comment At most one job outstanding per host.
--comment
--comment Risk R2 wants deployments serialised per instance, and this is where that is enforced for
--comment a pull host: two jobs in flight against one NGINX means two processes racing to swap the
--comment same symlink. A unique partial index states it as a database rule rather than leaving it
--comment to the claim query being written correctly every time.
CREATE UNIQUE INDEX ux_agent_jobs_one_leased_per_instance ON agent_jobs (nginx_instance_id)
    WHERE status = 'LEASED';
