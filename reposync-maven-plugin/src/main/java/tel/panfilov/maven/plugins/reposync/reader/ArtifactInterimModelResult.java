package tel.panfilov.maven.plugins.reposync.reader;

import org.apache.maven.model.Model;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.resolution.ArtifactDescriptorRequest;

import static java.util.Objects.requireNonNull;

public class ArtifactInterimModelResult {

    private ArtifactDescriptorRequest request;

    private Artifact artifact;

    private Model model;

    public ArtifactInterimModelResult(ArtifactDescriptorRequest request) {
        this.request = requireNonNull(request, "artifact descriptor request cannot be null");
        this.artifact = request.getArtifact();
    }

    public Artifact getArtifact() {
        return artifact;
    }

    public ArtifactInterimModelResult setArtifact(Artifact artifact) {
        this.artifact = artifact;
        return this;
    }

    public void setModel(Model model) {
        this.model = model;
    }

    public Model getModel() {
        return model;
    }
}
