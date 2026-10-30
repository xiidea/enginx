package net.xiidea.enginx.application.proxy;

import net.xiidea.enginx.application.deployment.DeploymentService;
import net.xiidea.enginx.domain.deployment.Deployment;
import net.xiidea.enginx.domain.proxy.ProxySite;
import net.xiidea.enginx.domain.proxy.ProxySiteRepository;
import net.xiidea.enginx.domain.shared.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Site changes that deploy themselves when they change what the host serves.
 *
 * <p>Saving and deploying used to be separate steps, and a saved change the host did not have yet
 * looked exactly like one that did not work: a narrowed access rule still let everyone in, a new
 * user was refused while the old one still worked. Here a change is saved and, when it renders
 * differently, its deployment queued in the same transaction, so the two commit together or not
 * at all. "Renders differently" is exact rather than a list of fields: the spec is the renderer's
 * only input ({@link net.xiidea.enginx.domain.proxy.ProxySiteSpec#rendersDifferentlyFrom}).
 *
 * <p>Only when asked: a caller that wants to stage several edits and deploy once still can, and
 * the API's plain endpoints keep the behaviour scripts were written against.
 */
@Service
public class ProxySiteChanges {

    /**
     * The site as saved, and the deployment that will carry the change: queued for it, or one
     * already pending that will render it. Null when none was needed, or none could be promised
     * now (see {@link DeploymentService#requestSiteDeployment}).
     */
    public record Result(ProxySite site, Deployment deployment) {
    }

    private final ProxySiteService sites;
    private final ProxySiteRepository repository;
    private final DeploymentService deployments;

    public ProxySiteChanges(ProxySiteService sites, ProxySiteRepository repository, DeploymentService deployments) {
        this.sites = sites;
        this.repository = repository;
        this.deployments = deployments;
    }

    /** A new site always changes what its host serves. */
    @Transactional
    public Result create(ProxySiteCommands.Create command) {
        ProxySite created = sites.create(command);
        return new Result(created, deployments.requestSiteDeployment(created.id()).orElse(null));
    }

    @Transactional
    public Result update(ProxySiteCommands.Update command) {
        ProxySite before = load(command.id());
        var previous = before.spec();
        ProxySite after = sites.update(command);
        return deployIf(after, after.spec().rendersDifferentlyFrom(previous));
    }

    @Transactional
    public Result enable(UUID id) {
        return afterStateChange(id, () -> sites.enable(id));
    }

    @Transactional
    public Result disable(UUID id) {
        return afterStateChange(id, () -> sites.disable(id));
    }

    @Transactional
    public Result renew(ProxySiteCommands.Renew command) {
        return afterStateChange(command.id(), () -> sites.renew(command));
    }

    @Transactional
    public Result removeExpiry(UUID id) {
        return afterStateChange(id, () -> sites.removeExpiry(id));
    }

    /** A new gate key only takes effect on the host, so signing everyone out always deploys. */
    @Transactional
    public Result signOutEveryone(UUID id) {
        ProxySite site = sites.signOutEveryone(id);
        return new Result(site, deployments.requestSiteDeployment(id).orElse(null));
    }

    /**
     * Enabling, disabling and changing the window decide whether the site is served at all, and
     * its window appears in what is rendered. A request that changed none of it deploys nothing.
     */
    private Result afterStateChange(UUID id, Supplier<ProxySite> change) {
        ProxySite before = load(id);
        var adminState = before.adminState();
        Instant activeFrom = before.window().activeFrom();
        Instant expiresAt = before.window().expiresAt();
        ProxySite after = change.get();
        boolean changed = after.adminState() != adminState
                || !java.util.Objects.equals(after.window().activeFrom(), activeFrom)
                || !java.util.Objects.equals(after.window().expiresAt(), expiresAt);
        return deployIf(after, changed);
    }

    private Result deployIf(ProxySite site, boolean needed) {
        return new Result(site, needed ? deployments.requestSiteDeployment(site.id()).orElse(null) : null);
    }

    private ProxySite load(UUID id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("PROXY_SITE", id));
    }
}
