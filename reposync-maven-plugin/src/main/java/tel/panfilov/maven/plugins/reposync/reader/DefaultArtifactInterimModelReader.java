package tel.panfilov.maven.plugins.reposync.reader;

import org.apache.maven.BuildFailureException;
import org.apache.maven.model.DistributionManagement;
import org.apache.maven.model.Model;
import org.apache.maven.model.Relocation;
import org.apache.maven.model.building.*;
import org.apache.maven.model.resolution.UnresolvableModelException;
import org.apache.maven.repository.internal.ArtifactDescriptorUtils;
import org.codehaus.plexus.component.annotations.Component;
import org.eclipse.aether.RepositoryEvent;
import org.eclipse.aether.RepositoryException;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.RequestTrace;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.impl.*;
import org.eclipse.aether.repository.WorkspaceRepository;
import org.eclipse.aether.resolution.*;
import org.eclipse.aether.transfer.ArtifactNotFoundException;

import javax.inject.Inject;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

@Component(role = ArtifactInterimModelReader.class)
public class DefaultArtifactInterimModelReader implements ArtifactInterimModelReader {

    @Inject
    private RemoteRepositoryManager remoteRepositoryManager;

    @Inject
    private VersionResolver versionResolver;

    @Inject
    private VersionRangeResolver versionRangeResolver;

    @Inject
    private ArtifactResolver artifactResolver;

    @Inject
    private RepositoryEventDispatcher repositoryEventDispatcher;

    @Inject
    private ModelBuilder modelBuilder;

    @Override
    public ArtifactInterimModelResult readArtifactInterimModel(RepositorySystemSession session,
                                                               ArtifactDescriptorRequest request)
            throws BuildFailureException {
        ArtifactInterimModelResult result = new ArtifactInterimModelResult(request);

        Model model = loadPom(session, request, result);
        if (model != null) {
            result.setModel(model);
        }

        return result;
    }

    private Model loadPom(RepositorySystemSession session, ArtifactDescriptorRequest request, ArtifactInterimModelResult result)
            throws BuildFailureException {
        RequestTrace trace = RequestTrace.newChild(request.getTrace(), request);

        Set<String> visited = new LinkedHashSet<>();
        for (Artifact a = request.getArtifact(); ; ) {
            Artifact pomArtifact = ArtifactDescriptorUtils.toPomArtifact(a);
            try {
                VersionRequest versionRequest =
                        new VersionRequest(a, request.getRepositories(), request.getRequestContext());
                versionRequest.setTrace(trace);
                VersionResult versionResult = versionResolver.resolveVersion(session, versionRequest);

                a = a.setVersion(versionResult.getVersion());

                versionRequest =
                        new VersionRequest(pomArtifact, request.getRepositories(), request.getRequestContext());
                versionRequest.setTrace(trace);
                versionResult = versionResolver.resolveVersion(session, versionRequest);

                pomArtifact = pomArtifact.setVersion(versionResult.getVersion());
            } catch (VersionResolutionException e) {
                throw new BuildFailureException(e.getMessage(), e);
            }

            if (!visited.add(a.getGroupId() + ':' + a.getArtifactId() + ':' + a.getBaseVersion())) {
                RepositoryException exception =
                        new RepositoryException("Artifact relocations form a cycle: " + visited);
                invalidDescriptor(session, trace, a, exception);
                if ((getPolicy(session, a, request) & ArtifactDescriptorPolicy.IGNORE_INVALID) != 0) {
                    return null;
                }
                throw new BuildFailureException(exception.getMessage(), exception);
            }

            ArtifactResult resolveResult;
            try {
                ArtifactRequest resolveRequest =
                        new ArtifactRequest(pomArtifact, request.getRepositories(), request.getRequestContext());
                resolveRequest.setTrace(trace);
                resolveResult = artifactResolver.resolveArtifact(session, resolveRequest);
                pomArtifact = resolveResult.getArtifact();
            } catch (ArtifactResolutionException e) {
                if (e.getCause() instanceof ArtifactNotFoundException) {
                    missingDescriptor(session, trace, a, (Exception) e.getCause());
                    if ((getPolicy(session, a, request) & ArtifactDescriptorPolicy.IGNORE_MISSING) != 0) {
                        return null;
                    }
                }
                throw new BuildFailureException(e.getMessage() +
                        "; artifact: " + pomArtifact.getGroupId() + ":" + pomArtifact.getArtifactId() + ":" + pomArtifact.getVersion(), e);
            }

            Model model;

            try {
                ModelBuildingRequest modelRequest = new DefaultModelBuildingRequest();
                modelRequest.setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL);
                modelRequest.setProcessPlugins(false);
                modelRequest.setTwoPhaseBuilding(true);
                modelRequest.setSystemProperties(toProperties(session.getUserProperties(),
                        session.getSystemProperties()));
                modelRequest.setModelResolver(new DefaultModelResolver(session, trace.newChild(modelRequest),
                        request.getRequestContext(), artifactResolver,
                        versionRangeResolver, remoteRepositoryManager,
                        request.getRepositories()));
                if (resolveResult.getRepository() instanceof WorkspaceRepository) {
                    modelRequest.setPomFile(pomArtifact.getFile());
                } else {
                    modelRequest.setModelSource(new FileModelSource(pomArtifact.getFile()));
                }

                model = modelBuilder.build(modelRequest).getEffectiveModel();
            } catch (ModelBuildingException e) {
                for (ModelProblem problem : e.getProblems()) {
                    if (problem.getException() instanceof UnresolvableModelException) {
                        throw new BuildFailureException(e.getMessage(), e);
                    }
                }
                invalidDescriptor(session, trace, a, e);
                if ((getPolicy(session, a, request) & ArtifactDescriptorPolicy.IGNORE_INVALID) != 0) {
                    return null;
                }
                throw new BuildFailureException(e.getMessage(), e);
            }

            Relocation relocation = getRelocation(model);

            if (relocation != null) {
                result.setArtifact(a);
            } else {
                return model;
            }
        }
    }

    private Properties toProperties(Map<String, String> dominant, Map<String, String> recessive) {
        Properties props = new Properties();
        if (recessive != null) {
            props.putAll(recessive);
        }
        if (dominant != null) {
            props.putAll(dominant);
        }
        return props;
    }

    private Relocation getRelocation(Model model) {
        Relocation relocation = null;
        DistributionManagement distMgmt = model.getDistributionManagement();
        if (distMgmt != null) {
            relocation = distMgmt.getRelocation();
        }
        return relocation;
    }

    private void missingDescriptor(RepositorySystemSession session, RequestTrace trace, Artifact artifact,
                                   Exception exception) {
        RepositoryEvent.Builder event = new RepositoryEvent.Builder(session, RepositoryEvent.EventType.ARTIFACT_DESCRIPTOR_MISSING);
        event.setTrace(trace);
        event.setArtifact(artifact);
        event.setException(exception);

        repositoryEventDispatcher.dispatch(event.build());
    }

    private void invalidDescriptor(RepositorySystemSession session, RequestTrace trace, Artifact artifact,
                                   Exception exception) {
        RepositoryEvent.Builder event = new RepositoryEvent.Builder(session, RepositoryEvent.EventType.ARTIFACT_DESCRIPTOR_INVALID);
        event.setTrace(trace);
        event.setArtifact(artifact);
        event.setException(exception);

        repositoryEventDispatcher.dispatch(event.build());
    }

    private int getPolicy(RepositorySystemSession session, Artifact a, ArtifactDescriptorRequest request) {
        ArtifactDescriptorPolicy policy = session.getArtifactDescriptorPolicy();
        if (policy == null) {
            return ArtifactDescriptorPolicy.STRICT;
        }
        return policy.getPolicy(session, new ArtifactDescriptorPolicyRequest(a, request.getRequestContext()));
    }


}
