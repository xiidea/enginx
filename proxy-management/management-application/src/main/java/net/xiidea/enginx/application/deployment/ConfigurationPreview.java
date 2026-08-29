package net.xiidea.enginx.application.deployment;

import java.util.List;

/**
 * What a deployment would produce, without deploying it.
 *
 * @param siteConfiguration the rendered file for the requested site
 * @param changed           whether the instance's bundle would actually change
 * @param changedPaths      files that differ from what the instance currently serves
 */
public record ConfigurationPreview(
        String siteConfiguration,
        String currentContentHash,
        String proposedContentHash,
        boolean changed,
        List<String> changedPaths) {
}
