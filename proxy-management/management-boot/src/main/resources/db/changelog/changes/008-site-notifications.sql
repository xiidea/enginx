--liquibase formatted sql

--changeset enginx:site-notifications-010-settings
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

--changeset enginx:site-notifications-020-subscribers
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
