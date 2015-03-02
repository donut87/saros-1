package de.fu_berlin.inf.dpp.vcs.git;

import java.io.File;
import java.io.IOException;
import java.util.Set;

import org.apache.log4j.Logger;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.jgit.api.CheckoutCommand;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.InitCommand;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRefNameException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.api.errors.RefAlreadyExistsException;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.errors.AmbiguousObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.RevisionSyntaxException;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.FetchResult;
import org.eclipse.team.core.RepositoryProvider;
import org.eclipse.team.core.RepositoryProviderType;

import de.fu_berlin.inf.dpp.activities.VCSActivity;
import de.fu_berlin.inf.dpp.filesystem.ResourceAdapterFactory;
import de.fu_berlin.inf.dpp.negotiation.FileList;
import de.fu_berlin.inf.dpp.session.ISarosSession;
import de.fu_berlin.inf.dpp.vcs.VCSAdapter;
import de.fu_berlin.inf.dpp.vcs.VCSResourceInfo;

//import de.fu_berlin.inf.dpp.filesystem.IResource;

public class GitAdapter extends VCSAdapter {

    static final String identifier = "org.eclipse.egit.core.GitProvider";

    @SuppressWarnings("hiding")
    protected static final Logger log = Logger.getLogger(GitAdapter.class);

    public GitAdapter(RepositoryProviderType provider) {
        super(provider);
    }

    @Override
    public String getID() {
        return identifier;
    }

    @Override
    public String getRepositoryString(IResource resource) {
        Repository gitRepo = getGitRepoForResource(resource);
        return (gitRepo == null) ? null : gitRepo.getWorkTree().getPath();
    }

    @Override
    public String getUrl(IResource resource) {
        Repository gitRepo = getGitRepoForResource(resource);
        Set<String> remoteNames = gitRepo.getConfig().getSubsections(
            ConfigConstants.CONFIG_REMOTE_SECTION);
        if (remoteNames.isEmpty()) {
            return null;
        }
        // origin is the most common remote
        String remoteName = "origin";
        if (!remoteNames.contains("origin")) {
            // there is no origin => take the first that is there
            remoteName = (String) remoteNames.toArray()[0];
        }
        return gitRepo.getConfig().getString("remote", remoteName, "url");
    }

    @Override
    public boolean isManaged(org.eclipse.core.resources.IResource resource) {
        return getGitRepoForResource(resource) != null;
        // return true;
        // Repository gitRepoForResource = getGitRepoForResource(resource);
        // IProject project = resource.getProject();
        // if (!RepositoryProvider.isShared(project)) {
        // return false;
        // }
        // return RepositoryProvider.getProvider(project).getID()
        // .equals(identifier);
    }

    @Override
    public boolean isInManagedProject(
        org.eclipse.core.resources.IResource resource) {
        IProject project = resource.getProject();
        if (!RepositoryProvider.isShared(project)) {
            return false;
        }
        return RepositoryProvider.getProvider(project).getID()
            .equals(identifier);
    }

    @Override
    public IProject checkoutProject(String newProjectName, FileList fileList,
        IProgressMonitor monitor) throws OperationCanceledException {
        IProject project = ResourcesPlugin.getWorkspace().getRoot()
            .getProject(fileList.getProjectID());
        try {
            project.create(monitor); // we know that the project doesn't exist
            File f = new File(project.getLocation().toOSString());
            FileRepositoryBuilder builder = new FileRepositoryBuilder();
            Repository projectRepo = builder.setGitDir(f).readEnvironment()
                .findGitDir().build();
            CloneCommand clone = Git.cloneRepository().setBare(false)
                .setURI(fileList.getVCSUrl("")).setDirectory(f);
            Git git = clone.call();
            git.reset().setRef(fileList.getVCSRevision(""));
        } catch (CoreException e) {
            log.debug("", e);
        } catch (IOException e) {
            log.debug("", e);
        }
        return project;
    }

    @Override
    public void update(org.eclipse.core.resources.IResource resource,
        String targetRevision, String targetBranch, IProgressMonitor monitor) {
        // In git this is of course exactly the same as 'switch'
        Repository repo = getGitRepoForResource(resource);
        Git git = new Git(repo);
        try {
            // a stash would be way cooler here...
            git.reset().setMode(ResetType.HARD).call();
            git.fetch().call(); // update current tree
            CheckoutCommand checkout = git.checkout().setName(targetBranch)
                .setStartPoint(targetRevision);
            Ref call = checkout.call();
        } catch (IOException e) {
            log.debug("", e);
        } catch (JGitInternalException e) {
            log.debug("", e);
        } catch (InvalidRemoteException e) {
            log.debug("", e);
        } catch (RefAlreadyExistsException e) {
            log.debug("", e);
        } catch (RefNotFoundException e) {
            log.debug("", e);
        } catch (InvalidRefNameException e) {
            log.debug("", e);
        }
        try {
            resource.refreshLocal(
                org.eclipse.core.resources.IResource.DEPTH_INFINITE, null);
        } catch (CoreException e) {
            log.error("Refresh failed", e);
        }
    }

    @Override
    public void switch_(org.eclipse.core.resources.IResource resource,
        String url, String revision, IProgressMonitor monitor, String branch) {
        // In git this is of course 'git checkout'
        Repository gitRepo = this.getGitRepoForResource(resource);
        if (gitRepo == null) {
            return;
        }
        Git git = new Git(gitRepo);
        try {
            FetchResult call = git.fetch().call();
            // there should be a stash here instead of a reset!!!
            git.reset().setMode(ResetType.HARD).call();
            Ref checkoutCall = git.checkout().setName(branch).call();
            resource.refreshLocal(IResource.DEPTH_INFINITE, null);
            if (!git.getRepository().resolve(Constants.HEAD).name()
                .equals(revision)) {
                git.reset().setMode(ResetType.HARD).setRef(revision).call();
            }
        } catch (RefAlreadyExistsException e) {
            // Cannot happen. We are not creating a new branch here.
            log.debug("", e);
        } catch (RefNotFoundException e) {
            // Can happen. But shouldn't!
            log.debug(
                "Branch was not found on this end of the line. Strange...", e);
        } catch (InvalidRefNameException e) {
            // Should not happen. The reference already exists and is validated.
            log.debug("", e);
        } catch (GitAPIException e) {
            log.debug("FUCK! We are doomed!", e);
        } catch (IOException e) {
            log.debug("", e);
        } catch (CoreException e) {
            log.debug("", e);
        }
    }

    @Override
    public void revert(org.eclipse.core.resources.IResource resource,
        SubMonitor monitor) {
        // In git this is of course 'git checkout'
        Repository gitRepo = this.getGitRepoForResource(resource);
        if (gitRepo == null) {
            return;
        }
        Git git = new Git(gitRepo);
        try {
            File workTree = gitRepo.getWorkTree();
            git.checkout()
                .setStartPoint(Constants.HEAD)
                .addPath(
                    resource.getLocation().toOSString()
                        .replace(workTree.getAbsolutePath(), "").substring(1))
                .call();
        } catch (RefAlreadyExistsException e1) {
            log.debug(
                "This cannot happen here. We are not trying to create a new branch.",
                e1);
        } catch (RefNotFoundException e1) {
            // Seems unlikely but is possible in a headless state
            log.debug("Start Point invalid. Could not find 'HEAD'.", e1);
        } catch (InvalidRefNameException e1) {
            // Highly unlikely
            log.debug("Invalid Reference 'HEAD'", e1);
        } catch (GitAPIException e1) {
            log.debug("We are doomed...", e1);
        }
        try {
            resource.refreshLocal(
                org.eclipse.core.resources.IResource.DEPTH_INFINITE, null);
        } catch (CoreException e) {
            log.error("Refresh failed", e);
        }

    }

    @Override
    public VCSResourceInfo getResourceInfo(
        org.eclipse.core.resources.IResource resource) {
        return new VCSResourceInfo(getUrl(resource),
            getRevisionString(resource), getBranchName(resource));
    }

    @Override
    public VCSResourceInfo getCurrentResourceInfo(
        org.eclipse.core.resources.IResource resource) {
        Repository repo = getGitRepoForResource(resource);
        String url = "";
        if (repo.getConfig().getSubsections("remote").contains("origin")) {
            url = repo.getConfig().getString("remote", "origin", "url");
        } else {
            String name = repo.getConfig().getSubsections("remote").iterator()
                .next();
            url = repo.getConfig().getString("remote", name, "url");
        }
        try {
            return new VCSResourceInfo(url,
                repo.resolve(Constants.HEAD).name(), repo.getBranch());
        } catch (RevisionSyntaxException e) {
            log.debug("Constant was not correctly formatted. WTF???", e);
        } catch (AmbiguousObjectException e) {
            // Cannot happen since ObjectId.name() is always the long SHA1-ID
            log.debug("", e);
        } catch (IncorrectObjectTypeException e) {
            // Cannot happe either. Saros only wprks on text files...
            log.debug("", e);
        } catch (IOException e) {
            log.debug("FUCK! We are doomed!", e);
        }
        return null;
    }

    @Override
    public void connect(IProject project, String repositoryRoot,
        String directory, IProgressMonitor progress) {
        if (isInManagedProject(project)) {
            return;
        }
        InitCommand ic = new InitCommand();
        Git git = ic.setBare(false).setDirectory(new File(directory)).call();
        log.debug(git);
    }

    @Override
    public void disconnect(IProject project, boolean deleteContent,
        IProgressMonitor progress) {
        // TODO Auto-generated method stub

    }

    @Override
    public boolean hasLocalCache(IProject project) {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public VCSActivity getUpdateActivity(ISarosSession sarosSession,
        org.eclipse.core.resources.IResource resource) {
        VCSResourceInfo info = getResourceInfo(resource);
        try {
            Repository repo = getGitRepoForResource(resource);
            String revision = repo.resolve(Constants.HEAD).getName();
            String branch = repo.getBranch();
            return VCSActivity.update(sarosSession,
                ResourceAdapterFactory.create(resource), revision, branch);
        } catch (AmbiguousObjectException e) {
            log.debug("", e);
        } catch (IOException e) {
            log.debug("", e);
        }
        return null;
    }

    @Override
    public VCSActivity getSwitchActivity(ISarosSession sarosSession,
        org.eclipse.core.resources.IResource resource) {
        VCSResourceInfo info = getResourceInfo(resource);
        try {
            String url = info.getURL();
            Repository repo = getGitRepoForResource(resource);
            String revision = repo.resolve(Constants.HEAD).getName();
            String branch = repo.getBranch();
            return VCSActivity.switch_(sarosSession,
                ResourceAdapterFactory.create(resource), url, revision, branch);
        } catch (AmbiguousObjectException e) {
            log.debug("", e);
        } catch (IOException e) {
            log.debug("", e);
        }
        return null;
    }

    private Repository getGitRepoForResource(IResource resource) {
        if (resource == null) {
            log.debug("Null Resource given.");
            return null;
        }
        File f = new File(resource.getLocation().toOSString());
        FileRepositoryBuilder builder = new FileRepositoryBuilder();
        builder.findGitDir(f);
        try {
            if (builder.getGitDir() != null) {
                return builder.build();
            }
        } catch (IOException e) {
            log.debug("The resource " + resource.getLocation().toOSString()
                + " was not found in file system.", e);
        }
        return null;
    }

    @Override
    public String getRepositoryString(
        de.fu_berlin.inf.dpp.filesystem.IResource resource) {
        return getRepositoryString(ResourceAdapterFactory.convertBack(resource));
    }

    @Override
    public VCSResourceInfo getResourceInfo(
        de.fu_berlin.inf.dpp.filesystem.IResource resource) {
        return getResourceInfo(ResourceAdapterFactory.convertBack(resource));
    }

    @Override
    public VCSResourceInfo getCurrentResourceInfo(
        de.fu_berlin.inf.dpp.filesystem.IResource resource) {
        return getCurrentResourceInfo(ResourceAdapterFactory
            .convertBack(resource));
    }

    @Override
    public String getUrl(de.fu_berlin.inf.dpp.filesystem.IResource resource) {
        return getUrl(ResourceAdapterFactory.convertBack(resource));
    }

    @Override
    public String getBranchName(IResource resource) {
        Repository repo = getGitRepoForResource(resource);
        try {
            return repo.getBranch();
        } catch (IOException e) {
            log.debug("", e);
        }
        return "";
    }
}