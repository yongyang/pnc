package org.jboss.pnc.mavenrepositorymanager;

import org.commonjava.aprox.client.core.Aprox;
import org.commonjava.aprox.client.core.AproxClientException;
import org.commonjava.aprox.client.core.module.AproxContentClientModule;
import org.commonjava.aprox.folo.client.AproxFoloAdminClientModule;
import org.commonjava.aprox.folo.client.AproxFoloContentClientModule;
import org.commonjava.aprox.folo.dto.TrackedContentDTO;
import org.commonjava.aprox.folo.dto.TrackedContentEntryDTO;
import org.commonjava.aprox.model.core.Group;
import org.commonjava.aprox.model.core.HostedRepository;
import org.commonjava.aprox.model.core.StoreKey;
import org.commonjava.aprox.model.core.StoreType;
import org.commonjava.aprox.promote.client.AproxPromoteClientModule;
import org.commonjava.aprox.promote.model.PromoteRequest;
import org.commonjava.aprox.promote.model.PromoteResult;
import org.commonjava.maven.atlas.ident.ref.ArtifactRef;
import org.commonjava.maven.atlas.ident.util.ArtifactPathInfo;
import org.jboss.pnc.common.Configuration;
import org.jboss.pnc.model.Artifact;
import org.jboss.pnc.model.ArtifactStatus;
import org.jboss.pnc.model.BuildCollection;
import org.jboss.pnc.model.BuildConfiguration;
import org.jboss.pnc.model.BuildRecord;
import org.jboss.pnc.model.ProductVersion;
import org.jboss.pnc.model.RepositoryType;
import org.jboss.pnc.model.builder.ArtifactBuilder;
import org.jboss.pnc.spi.repositorymanager.RepositoryManager;
import org.jboss.pnc.spi.repositorymanager.RepositoryManagerException;
import org.jboss.pnc.spi.repositorymanager.model.RepositoryConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

/**
 * Implementation of {@link RepositoryManager} that manages an <a href="https://github.com/jdcasey/aprox">AProx</a> instance to
 * support repositories for Maven-ish builds.
 *
 * Created by <a href="mailto:matejonnet@gmail.com">Matej Lazar</a> on 2014-11-25.
 * 
 * @author <a href="mailto:jdcasey@commonjava.org">John Casey</a>
 */
@ApplicationScoped
public class RepositoryManagerDriver implements RepositoryManager {

    private static final String MAVEN_REPOSITORY_CONFIG_SECTION = "maven-repository";

    private static final String BASE_URL_PROPERTY = "base.url";

    private static final String GROUP_ID_FORMAT = "product+%s+%s";

    private static final String REPO_ID_FORMAT = "build+%s+%s";

    public static final String PUBLIC_GROUP_ID = "public";

    public static final String SHARED_RELEASES_ID = "shared-releases";

    public static final String SHARED_IMPORTS_ID = "shared-imports";

    private Aprox aprox;

    @Deprecated
    public RepositoryManagerDriver() { // workaround for CDI constructor parameter injection bug
    }

    @Inject
    public RepositoryManagerDriver(Configuration configuration) {
        Properties properties = configuration.getModuleConfig(MAVEN_REPOSITORY_CONFIG_SECTION);

        String baseUrl = properties.getProperty(BASE_URL_PROPERTY);
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        if (!baseUrl.endsWith("/api")) {
            baseUrl += "/api";
        }

        aprox = new Aprox(baseUrl, new AproxFoloAdminClientModule(), new AproxFoloContentClientModule(),
                new AproxPromoteClientModule()).connect();
    }

    /**
     * Only supports {@link RepositoryType#MAVEN}.
     */
    @Override
    public boolean canManage(RepositoryType managerType) {
        return (managerType == RepositoryType.MAVEN);
    }

    /**
     * Currently, we're using the AutoProx add-on of AProx. This add-on supports a series of rules (implemented as groovy
     * scripts), which match repository/group naming patterns and create remote repositories, hosted repositories, and groups in
     * flexible configurations on demand. Because these rules will create the necessary repositories the first time they are
     * accessed, this driver only has to formulate a repository URL that will trigger the appropriate AutoProx rule, then pass
     * this back via a {@link MavenRepositoryConfiguration} instance.
     * 
     * @throws RepositoryManagerException In the event one or more repositories or groups can't be created to support the build
     *         (or product, or shared-releases).
     */
    @Override
    public RepositoryConfiguration createRepository(BuildConfiguration buildConfiguration, BuildCollection buildCollection)
            throws RepositoryManagerException {

        try {
            setupGlobalRepos();
        } catch (AproxClientException e) {
            throw new RepositoryManagerException("Failed to setup shared-releases hosted repository: %s", e, e.getMessage());
        }

        ProductVersion pv = buildCollection.getProductVersion();

        String productRepoId = String.format(GROUP_ID_FORMAT, safeUrlPart(pv.getProduct().getName()),
                safeUrlPart(pv.getVersion()));
        try {
            setupProductRepos(productRepoId);
        } catch (AproxClientException e) {
            throw new RepositoryManagerException("Failed to setup product-local hosted repository or repository group: %s", e,
                    e.getMessage());
        }

        // TODO Better way to generate id that doesn't rely on System.currentTimeMillis() but will still be relatively fast.

        String buildRepoId = String.format(REPO_ID_FORMAT, safeUrlPart(buildConfiguration.getProject().getName()),
                System.currentTimeMillis());
        try {
            setupBuildRepos(buildRepoId, productRepoId);
        } catch (AproxClientException e) {
            throw new RepositoryManagerException("Failed to setup build-local hosted repository or repository group: %s", e,
                    e.getMessage());
        }

        // since we're setting up a group/hosted repo per build, we can pin the tracking ID to the build repo ID.
        String url;

        try {
            url = aprox.module(AproxFoloContentClientModule.class).trackingUrl(buildRepoId, StoreType.group, buildRepoId);
        } catch (AproxClientException e) {
            throw new RepositoryManagerException("Failed to retrieve AProx client module for the artifact tracker: %s", e,
                    e.getMessage());
        }

        return new MavenRepositoryConfiguration(buildRepoId, productRepoId, new MavenRepositoryConnectionInfo(url));
    }

    /**
     * Retrieve tracking report from repository manager. Add each tracked download to the dependencies of the build result. Add
     * each tracked upload to the built artifacts of the build result. Promote uploaded artifacts to the product-level storage.
     * Finally, clear the tracking report, and delete the hosted repository + group associated with the completed build.
     */
    @Override
    // TODO move under returned object (do not use the one from model) form createRepo
    public void persistArtifacts(RepositoryConfiguration repository, BuildRecord buildResult) throws RepositoryManagerException {
        String buildId = repository.getId();

        TrackedContentDTO report;
        try {
            report = aprox.module(AproxFoloAdminClientModule.class).getTrackingReport(buildId, StoreType.group, buildId);
        } catch (AproxClientException e) {
            throw new RepositoryManagerException("Failed to retrieve tracking report for: %s. Reason: %s", e, buildId,
                    e.getMessage());
        }

        processUploads(report, buildResult, repository);
        processDownloads(report, buildResult);

        // clean up.
        try {
            aprox.module(AproxFoloAdminClientModule.class).clearTrackingRecord(buildId, StoreType.group, buildId);
            aprox.stores().delete(StoreType.group, buildId);
            aprox.stores().delete(StoreType.remote, buildId);
        } catch (AproxClientException e) {
            throw new RepositoryManagerException(
                    "Failed to clean up build repositories / tracking information for: %s. Reason: %s", e, buildId,
                    e.getMessage());
        }
    }

    /**
     * Promote all build dependencies NOT ALREADY CAPTURED to the hosted repository holding store for the shared imports, then
     * for each dependency artifact, add its metadata to the build result.
     * 
     * @param report The tracking report that contains info about artifacts downloaded by the build
     * @param buildResult The build result where dependency artifact metadata should be appended
     * @throws RepositoryManagerException In case of a client API transport error or an error during promotion of artifacts
     */
    private void processDownloads(TrackedContentDTO report, BuildRecord buildResult) throws RepositoryManagerException {

        AproxContentClientModule content;
        try {
            content = aprox.content();
        } catch (AproxClientException e) {
            throw new RepositoryManagerException("Failed to retrieve AProx client module. Reason: %s", e, e.getMessage());
        }

        Set<TrackedContentEntryDTO> downloads = report.getDownloads();
        if (downloads != null) {
            List<Artifact> deps = new ArrayList<>();

            Map<StoreKey, Set<String>> toPromote = new HashMap<>();

            StoreKey sharedImports = new StoreKey(StoreType.hosted, SHARED_IMPORTS_ID);
            StoreKey sharedReleases = new StoreKey(StoreType.hosted, SHARED_RELEASES_ID);

            for (TrackedContentEntryDTO download : downloads) {
                StoreKey sk = download.getStoreKey();
                if (!sharedImports.equals(sk) && !sharedReleases.equals(sk)) {
                    // this has not been captured, so promote it.
                    Set<String> paths = toPromote.get(sk);
                    if (paths == null) {
                        paths = new HashSet<>();
                        toPromote.put(sk, paths);
                    }

                    paths.add(download.getPath());
                }

                String path = download.getPath();
                ArtifactPathInfo pathInfo = ArtifactPathInfo.parse(path);
                if (pathInfo == null) {
                    // metadata file. Ignore.
                    continue;
                }

                ArtifactRef aref = new ArtifactRef(pathInfo.getProjectId(), pathInfo.getType(), pathInfo.getClassifier(), false);

                ArtifactBuilder artifactBuilder = ArtifactBuilder.newBuilder().checksum(download.getSha256())
                        .deployUrl(content.contentUrl(download.getStoreKey(), download.getPath()))
                        .filename(new File(path).getName()).identifier(aref.toString()).repoType(RepositoryType.MAVEN)
                        .status(ArtifactStatus.BINARY_IMPORTED).buildRecord(buildResult);
                deps.add(artifactBuilder.build());
            }

            for (Map.Entry<StoreKey, Set<String>> entry : toPromote.entrySet()) {
                PromoteRequest req = new PromoteRequest(entry.getKey(), sharedImports, entry.getValue()).setPurgeSource(false);
                doPromote(req);
            }

            buildResult.setDependencies(deps);
        }
    }

    /**
     * Promote all build output to the hosted repository holding store for the build collection to which this build belongs,
     * then for each output artifact, add its metadata to the build result.
     * 
     * @param report The tracking report that contains info about artifacts uploaded (output) from the build
     * @param buildResult The build result where output artifact metadata should be appended
     * @param repository The AProx connection configuration containing the build- and collection-level repo id's
     * @throws RepositoryManagerException In case of a client API transport error or an error during promotion of artifacts
     */
    private void processUploads(TrackedContentDTO report, BuildRecord buildResult, RepositoryConfiguration repository)
            throws RepositoryManagerException {

        AproxContentClientModule content;
        try {
            content = aprox.content();
        } catch (AproxClientException e) {
            throw new RepositoryManagerException("Failed to retrieve AProx client module. Reason: %s", e, e.getMessage());
        }

        String buildId = repository.getId();
        String collectionId = repository.getCollectionId();

        Set<TrackedContentEntryDTO> uploads = report.getUploads();
        if (uploads != null) {
            PromoteRequest promoteReq = new PromoteRequest(new StoreKey(StoreType.hosted, buildId), new StoreKey(
                    StoreType.hosted, collectionId));

            doPromote(promoteReq);

            List<Artifact> builds = new ArrayList<>();

            for (TrackedContentEntryDTO upload : uploads) {

                String path = upload.getPath();
                ArtifactPathInfo pathInfo = ArtifactPathInfo.parse(path);
                if (pathInfo == null) {
                    // metadata file. Ignore.
                    continue;
                }

                ArtifactRef aref = new ArtifactRef(pathInfo.getProjectId(), pathInfo.getType(), pathInfo.getClassifier(), false);
                content.contentUrl(StoreType.hosted, collectionId, upload.getPath());

                ArtifactBuilder artifactBuilder = ArtifactBuilder.newBuilder().checksum(upload.getSha256())
                        .deployUrl(upload.getLocalUrl()).filename(new File(path).getName()).identifier(aref.toString())
                        .repoType(RepositoryType.MAVEN).status(ArtifactStatus.BINARY_BUILT).buildRecord(buildResult);

                builds.add(artifactBuilder.build());
            }

            buildResult.setBuiltArtifacts(builds);
        }
    }

    /**
     * Promotes a set of artifact paths (or everything, if the path-set is missing) from a particular AProx artifact store to
     * another, and handle the various error conditions that may arise. If the promote call fails, attempt to rollback before
     * throwing an exception.
     * 
     * @param req The promotion request to process, which contains source and target store keys, and (optionally) the set of
     *        paths to promote
     * @throws RepositoryManagerException When either the client API throws an exception due to something unexpected in
     *         transport, or if the promotion process results in an error.
     */
    private void doPromote(PromoteRequest req) throws RepositoryManagerException {
        AproxPromoteClientModule promoter;
        try {
            promoter = aprox.module(AproxPromoteClientModule.class);
        } catch (AproxClientException e) {
            throw new RepositoryManagerException("Failed to retrieve AProx client module. Reason: %s", e, e.getMessage());
        }

        try {
            PromoteResult result = promoter.promote(req);
            if (result.getError() != null) {
                String addendum = "";
                try {
                    PromoteResult rollback = promoter.rollback(result);
                    if (rollback.getError() != null) {
                        addendum = "\nROLLBACK WARNING: Promotion rollback also failed! Reason given: " + result.getError();
                    }

                } catch (AproxClientException e) {
                    throw new RepositoryManagerException("Rollback failed for promotion of: %s. Reason: %s", e, req,
                            e.getMessage());
                }

                throw new RepositoryManagerException("Failed to promote: %s. Reason given was: %s%s", req, result.getError(),
                        addendum);
            }
        } catch (AproxClientException e) {
            throw new RepositoryManagerException("Failed to promote: %s. Reason: %s", e, req, e.getMessage());
        }
    }

    /**
     * Create the hosted repository and group necessary to support a single build. The hosted repository holds artifacts
     * uploaded from the build, and the group coordinates access to this hosted repository, along with content from the
     * product-level content group with which this build is associated. The group also provides a tracking target, so the
     * repository manager can keep track of downloads and uploads for the build.
     * 
     * @param buildRepoId
     * @param productRepoId
     * @throws AproxClientException
     */
    private void setupBuildRepos(String buildRepoId, String productRepoId) throws AproxClientException {
        // if the build-level group doesn't exist, create it.
        if (!aprox.stores().exists(StoreType.group, buildRepoId)) {
            // if the product-level storage repo (for in-progress product builds) doesn't exist, create it.
            if (!aprox.stores().exists(StoreType.hosted, buildRepoId)) {
                HostedRepository buildArtifacts = new HostedRepository(buildRepoId);
                buildArtifacts.setAllowSnapshots(true);
                buildArtifacts.setAllowReleases(true);

                aprox.stores().create(buildArtifacts, HostedRepository.class);
            }

            Group buildGroup = new Group(buildRepoId);

            // Priorities for build-local group:

            // 1. build-local artifacts
            buildGroup.addConstituent(new StoreKey(StoreType.hosted, buildRepoId));

            // 2. product-level group
            buildGroup.addConstituent(new StoreKey(StoreType.group, productRepoId));

            aprox.stores().create(buildGroup, Group.class);
        }
    }

    /**
     * Lazily create product-level hosted repository and group if they don't exist. The group uses the following content
     * preference order:
     * <ol>
     * <li>product-level hosted repository (artifacts built for this product release)</li>
     * <li>global shared-releases hosted repository (contains artifacts from "released" product versions)</li>
     * <li>global shared-imports hosted repository (contains anything imported for a previous build)</li>
     * <li>the 'public' group, which manages the allowed remote repositories from which imports can be downloaded</li>
     * </ol>
     * 
     * @param productRepoId
     * @throws AproxClientException
     */
    private void setupProductRepos(String productRepoId) throws AproxClientException {
        // if the product-level group doesn't exist, create it.
        if (!aprox.stores().exists(StoreType.group, productRepoId)) {
            // if the product-level storage repo (for in-progress product builds) doesn't exist, create it.
            if (!aprox.stores().exists(StoreType.hosted, productRepoId)) {
                HostedRepository productArtifacts = new HostedRepository(productRepoId);
                productArtifacts.setAllowSnapshots(false);
                productArtifacts.setAllowReleases(true);

                aprox.stores().create(productArtifacts, HostedRepository.class);
            }

            Group productGroup = new Group(productRepoId);

            // Priorities for product-local group:

            // 1. product-local artifacts
            productGroup.addConstituent(new StoreKey(StoreType.hosted, productRepoId));

            // 2. global shared-releases artifacts
            productGroup.addConstituent(new StoreKey(StoreType.hosted, SHARED_RELEASES_ID));

            // 3. global shared-imports artifacts
            productGroup.addConstituent(new StoreKey(StoreType.hosted, SHARED_IMPORTS_ID));

            // 4. public group, containing remote proxies to the outside world
            // TODO: Configuration by product to determine whether outside world access is permitted.
            productGroup.addConstituent(new StoreKey(StoreType.group, PUBLIC_GROUP_ID));

            aprox.stores().create(productGroup, Group.class);
        }
    }

    /**
     * Lazily create the shared-releases and shared-imports global hosted repositories if they don't already exist.
     * 
     * @throws AproxClientException
     */
    private void setupGlobalRepos() throws AproxClientException {
        // if the global shared-releases repository doesn't exist, create it.
        if (!aprox.stores().exists(StoreType.hosted, SHARED_RELEASES_ID)) {
            HostedRepository sharedArtifacts = new HostedRepository(SHARED_RELEASES_ID);
            sharedArtifacts.setAllowSnapshots(false);
            sharedArtifacts.setAllowReleases(true);

            aprox.stores().create(sharedArtifacts, HostedRepository.class);
        }

        // if the global imports repo doesn't exist, create it.
        if (!aprox.stores().exists(StoreType.hosted, SHARED_IMPORTS_ID)) {
            HostedRepository productArtifacts = new HostedRepository(SHARED_IMPORTS_ID);
            productArtifacts.setAllowSnapshots(false);
            productArtifacts.setAllowReleases(true);

            aprox.stores().create(productArtifacts, HostedRepository.class);
        }
    }

    /**
     * Sift out spaces, pipe characters and colons (things that don't play well in URLs) from the project name, and convert them
     * to dashes. This is only for naming repositories, so an approximate match to the project in question is fine.
     */
    private String safeUrlPart(String name) {
        return name.replaceAll("\\W+", "-").replaceAll("[|:]+", "-");
    }

    /**
     * Convenience method for tests.
     */
    protected Aprox getAprox() {
        return aprox;
    }

}
