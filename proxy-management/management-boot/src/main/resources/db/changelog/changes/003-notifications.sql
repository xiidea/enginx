--liquibase formatted sql

--changeset enginx:notifications-010-ledger
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
