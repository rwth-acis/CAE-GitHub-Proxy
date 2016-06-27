package i5.las2peer.services.gitHubProxyService.gitUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LsRemoteCommand;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.PushCommand;
import org.eclipse.jgit.api.errors.CheckoutConflictException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRefNameException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.RefAlreadyExistsException;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;

import i5.las2peer.logging.L2pLogger;
import i5.las2peer.logging.NodeObserver.Event;
import i5.las2peer.services.gitHubProxyService.GitHubProxyService;

/**
 * A static helper class providing utilities to work with local repositories
 * 
 * @author Thomas Winkler
 *
 */

public class GitHelper {

  private static final L2pLogger logger = L2pLogger.getInstance(GitHubProxyService.class.getName());


  /**
   * Get the path for the given repository name
   * 
   * @param repositoryName The name of the repository
   * @return A file pointing to the path of the repository
   */
  private static File getRepositoryPath(String repositoryName) {
    return new File(repositoryName);
  }

  /**
   * Rename a file within a repository. This method does not commit the renaming.
   * 
   * @param repositoryName The name of the repository
   * @param gitHubOrganization The github organization
   * @param newFileName The path of the new file name, relative to the working directory
   * @param oldFileName The path of the old file t, relative to the working directory
   * @throws FileNotFoundException Thrown if the renamed file is not found
   * @throws IOException Thrown if something during the renaming went wrong
   * @throws Exception Thrown if something else went wrong
   */

  public static void renameFile(String repositoryName, String gitHubOrganization,
      String newFileName, String oldFileName) throws FileNotFoundException, Exception {
    try (Git git = getLocalGit(repositoryName, gitHubOrganization, "development")) {
      File oldFile = new File(getRepositoryPath(repositoryName) + "/" + oldFileName);
      File newFile = new File(getRepositoryPath(repositoryName) + "/" + newFileName);

      L2pLogger.logEvent(Event.SERVICE_MESSAGE,
          "Renaming file " + oldFileName + " to " + newFileName);

      if (newFile.getParentFile() != null) {
        newFile.getParentFile().mkdirs();
      }

      oldFile.renameTo(newFile);
      git.add().addFilepattern(newFileName).call();
      git.rm().addFilepattern(oldFileName).call();
      // delete empty folder of old file
      while (oldFile.getParentFile() != null) {
        File parent = oldFile.getParentFile();
        if (parent.isDirectory() && parent.list().length == 0) {
          oldFile = parent;
          parent.delete();
        } else {
          break;
        }

      }
    }

  }

  /**
   * Delete a file from a repository. This method does not commit the deletion.
   * 
   * @param repositoryName The name of the repository
   * @param gitHubOrganization The github organization
   * @param fileName The path of the file to be deleted
   * @throws FileNotFoundException Thrown if file is not found
   * @throws IOException Thrown if something during the deleting went wrong
   * @throws Exception Thrown if something else went wrong
   */

  public static void deleteFile(String repositoryName, String gitHubOrganization, String fileName)
      throws FileNotFoundException, IOException, Exception {
    try (Git git = getLocalGit(repositoryName, gitHubOrganization, "development")) {
      File file = new File(getRepositoryPath(repositoryName) + "/" + fileName);

      L2pLogger.logEvent(Event.SERVICE_MESSAGE, "Deleting file " + fileName);

      file.delete();
      git.rm().addFilepattern(fileName).call();

    }
  }

  /**
   * Merge the development branch of the repository to the given master branch and push it to the
   * remote repository *
   * 
   * @param repositoryName The name of the repository
   * @param gitHubOragnization The github organization
   * @param masterBranchName The name of the master branch
   * @param cp A credential provider to login to the git
   * @throws Exception If something went wrong during the merging and pushing
   */

  public static void mergeIntoMasterBranch(String repositoryName, String gitHubOragnization,
      String masterBranchName, CredentialsProvider cp) throws Exception {
    Git git = null;

    try {
      git = GitHelper.getLocalGit(repositoryName, gitHubOragnization, masterBranchName);
      git.fetch().call();
      git.pull().call();

      MergeCommand mCmd = git.merge();
      Ref HEAD = git.getRepository().getRef("refs/heads/development");
      mCmd.include(HEAD);
      mCmd.setStrategy(MergeStrategy.THEIRS);
      MergeResult mRes = mCmd.call();

      if (mRes.getMergeStatus().isSuccessful()) {
        L2pLogger.logEvent(Event.SERVICE_MESSAGE,
            "Merged development and master branch successfully");
        L2pLogger.logEvent(Event.SERVICE_MESSAGE, "Now pushing the commits...");
        PushCommand pushCmd = git.push();
        pushCmd.setCredentialsProvider(cp).setForce(true).setPushAll().call();
        L2pLogger.logEvent(Event.SERVICE_MESSAGE, "... commits pushed");
      } else {
        logger.warning("Error during merging of development and master branch");
        throw new Exception("Unable to merge master and development branch");
      }
    } finally {
      if (git != null) {// switch back to development branch
        GitHelper.switchBranch(git, "development");
        git.close();
      }
    }
  }

  /**
   * Checks whether a remote repository of the given name in the given github organization exists.
   * Uses the ls remote git command to determine if the repository exists.
   * 
   * @param url The url of the repository
   * 
   * @return True, if the repository exists, otherwise false
   */

  public static boolean existsRemoteRepository(String url) {
    LsRemoteCommand lsCmd = new LsRemoteCommand(null);
    lsCmd.setRemote(url);
    lsCmd.setHeads(true);
    boolean exists = true;
    try {
      lsCmd.call();
    } catch (Exception e) {
      // ignore the exception, as this is the way we determine if a remote repository exists
      exists = false;
    }
    return exists;
  }

  /**
   * Checks if the local repository already exists
   * 
   * @param repositoryName The name of the repository
   * @return True, if the local repository exists
   */

  public static boolean existsLocalRepository(String repositoryName) {
    File localPath;
    localPath = getRepositoryPath(repositoryName);
    File repoFile = new File(localPath + "/.git");
    return repoFile.exists();
  }

  /**
   * Get the local repository with the given name within the given github organization. If a local
   * repository does not exist yet, it will be created.
   * 
   * @param repositoryName The name of the repository
   * @param gitHubOrganization The github organization of the repository
   * @return The local repository
   * @throws Exception
   */

  public static Repository getLocalRepository(String repositoryName, String gitHubOrganization)
      throws Exception {
    File localPath;
    Repository repository = null;
    localPath = getRepositoryPath(repositoryName);
    File repoFile = new File(localPath + "/.git");

    if (!repoFile.exists()) {
      try {
        repository = createLocalRepository(repositoryName, gitHubOrganization);
      } catch (TransportException e) {
      }
    } else {
      FileRepositoryBuilder builder = new FileRepositoryBuilder();
      repository = builder.setGitDir(repoFile).readEnvironment().findGitDir().build();
    }
    return repository;
  }

  /**
   * Get a {@link org.eclipse.jgit.api.Git} for a repository with the given name and github
   * organization
   * 
   * @param repositoryName The name of the repository
   * @param gitHubOrganization The github organization of the repository
   * @return A {@link org.eclipse.jgit.api.Git}
   * @throws Exception
   */

  public static Git getLocalGit(String repositoryName, String gitHubOrganization) throws Exception {
    Git git = new AutoCloseGit(getLocalRepository(repositoryName, gitHubOrganization));
    return git;
  }

  /**
   * Get a {@link org.eclipse.jgit.api.Git} for a repository with the given name and github
   * organization checked out to the given branch name.
   * 
   * @param repositoryName The name of the repository
   * @param gitHubOrganization The github organization of the repository
   * @param branchName The branch name to checkout
   * @return A {@link org.eclipse.jgit.api.Git} checked out to the branch name
   * @throws Exception
   */

  public static Git getLocalGit(String repositoryName, String gitHubOrganization, String branchName)
      throws Exception {
    Git git = getLocalGit(repositoryName, gitHubOrganization);
    switchBranch(git, branchName);
    return git;
  }

  /**
   * Get a {@link org.eclipse.jgit.api.Git} of a git repository checked out to the given branch name
   * 
   * @param repository The git repository to use
   * @param branchName The branch name to checkout
   * @return A {@link org.eclipse.jgit.api.Git} checked out to the branch name
   * @throws Exception
   */

  public static Git getLocalGit(Repository repository, String branchName) throws Exception {
    Git git = new AutoCloseGit(repository);
    switchBranch(git, branchName);
    return git;
  }

  /**
   * Get a non recursive {@link org.eclipse.jgit.treewalk.TreeWalk} of the repository.
   * 
   * @param repository The repository
   * @return The tree walk of the repository or null if there was an error
   */

  public static TreeWalk getRepositoryTreeWalk(Repository repository) throws Exception {
    return getRepositoryTreeWalk(repository, false);
  }

  /**
   * Get a {@link org.eclipse.jgit.treewalk.TreeWalk} of the repository.
   * 
   * @param repository The repository
   * @param recursive Determine if the tree walk should be recursive or not
   * @return The tree walk of the repository or null if there was an error
   */

  public static TreeWalk getRepositoryTreeWalk(Repository repository, boolean recursive)
      throws Exception {

    RevWalk revWalk = null;
    TreeWalk treeWalk = null;
    try {
      ObjectId lastCommitId = repository.resolve(Constants.HEAD);
      treeWalk = new TreeWalk(repository);
      revWalk = new RevWalk(repository);
      RevTree tree = revWalk.parseCommit(lastCommitId).getTree();
      treeWalk.addTree(tree);
      treeWalk.setRecursive(recursive);

    } catch (Exception e) {
      logger.printStackTrace(e);
    } finally {
      repository.close();
      revWalk.close();
    }

    return treeWalk;
  }

  /**
   * Get the content of a file in a repository
   * 
   * @param repository The repository of the file
   * @param fileName The name of the file
   * @return The content of the file.
   * @throws FileNotFoundException Thrown if the file was not found
   * @throws Exception Thrown if anything else went wrong
   */

  public static String getFileContent(Repository repository, String fileName) throws Exception {

    String content = "";

    try (TreeWalk treeWalk = getRepositoryTreeWalk(repository)) {

      treeWalk.setFilter(PathFilter.create(fileName));
      boolean fileFound = false;

      while (treeWalk.next()) {

        if (!fileFound && treeWalk.isSubtree()) {
          treeWalk.enterSubtree();
        }
        if (treeWalk.getPathString().equals(fileName)) {
          fileFound = true;
          break;
        }

      }
      if (fileFound) {
        ObjectReader reader = treeWalk.getObjectReader();
        ObjectId objectId = treeWalk.getObjectId(0);
        ObjectLoader loader = reader.open(objectId);
        content = new String(loader.getBytes(), "UTF-8");
      } else {
        throw new FileNotFoundException(fileName + " not found");
      }

    }

    return content;


  }

  /**
   * Checkout a {@link org.eclipse.jgit.api.Git} to a specific branch. If the branch does not exist
   * yet, it will be created.
   * 
   * @param git The git instance to checkout
   * @param branchName The name of the branch
   * @throws IOException Thrown if an i/o error occurs
   * @throws RefAlreadyExistsException Thrown if we try to create a new branch if it already exists
   * @throws RefNotFoundException Thrown if we try to checkout a branch that does not exist
   * @throws InvalidRefNameException Thrown when an invalid remote is used internally
   * @throws CheckoutConflictException Thrown if something went wrong during the checkout
   * @throws GitAPIException Thrown if something went wrong during the branch creation or checkout
   */

  public static void switchBranch(Git git, String branchName)
      throws IOException, RefAlreadyExistsException, RefNotFoundException, InvalidRefNameException,
      CheckoutConflictException, GitAPIException {
    if (git.getRepository().getBranch().equals(branchName)) {
      return;
    }
    boolean branchExists = git.getRepository().getRef(branchName) != null;
    if (!branchExists) {
      git.branchCreate().setName(branchName).call();
    }
    git.checkout().setName(branchName).call();
  }

  /**
   * Creates a new local repository with the given name within the given github organization.
   * 
   * @param repositoryName The name of the repository
   * @param gitHubOrganization The github organization of the repository
   * @return The new created local repository
   * @throws InvalidRemoteException Thrown when an invalid remote is used internally
   * @throws TransportException Thrown if a protocol error has occurred
   * @throws GitAPIException Thrown by any git API classes
   * @throws FileNotFoundException Thrown if the remote repository does not exists
   */

  private static Repository createLocalRepository(String repositoryName, String gitHubOrganization)
      throws InvalidRemoteException, TransportException, GitAPIException, FileNotFoundException {
    L2pLogger.logEvent(Event.SERVICE_MESSAGE, "created new local repository " + repositoryName);
    String repositoryAddress =
        "https://github.com/" + gitHubOrganization + "/" + repositoryName + ".git";
    Repository repository = null;

    boolean isFrontend = repositoryName.startsWith("frontendComponent-");
    String masterBranchName = isFrontend ? "gh-pages" : "master";

    if (existsRemoteRepository(repositoryAddress)) {
      try (Git result = Git.cloneRepository().setURI(repositoryAddress)
          .setDirectory(getRepositoryPath(repositoryName)).setBranch(masterBranchName).call()) {
        repository = result.getRepository();
      }
    } else {
      throw new FileNotFoundException("Remote repository: " + repositoryAddress + " not found!");
    }

    return repository;
  }

  public static boolean indexIsLocked(String repositoryName) {
    return getRepositoryPath(repositoryName + "/.git/index.lock").exists();
  }

}
