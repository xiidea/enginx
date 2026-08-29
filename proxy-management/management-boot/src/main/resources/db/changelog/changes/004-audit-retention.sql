--liquibase formatted sql

--changeset enginx:retention-010-drop-audit-partitions splitStatements:false runOnChange:true
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
