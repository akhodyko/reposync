package tel.panfilov.maven.plugins.reposync.reader;

import org.apache.maven.BuildFailureException;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.resolution.ArtifactDescriptorRequest;

/**
 * @author Aleksandr Khodyko
 */
public interface ArtifactInterimModelReader {
    ArtifactInterimModelResult readArtifactInterimModel(RepositorySystemSession session,
                                                        ArtifactDescriptorRequest request)
            throws BuildFailureException;
}
