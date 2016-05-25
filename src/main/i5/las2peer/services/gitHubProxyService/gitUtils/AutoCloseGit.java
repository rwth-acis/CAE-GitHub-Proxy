package i5.las2peer.services.gitHubProxyService.gitUtils;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;

/**
 * A special git class that also close the repository if itself will be closed. We need this class
 * as this behavior is not possible with the original git class.
 * 
 * @author Thomas Winkler
 *
 */

public class AutoCloseGit extends Git {

  public AutoCloseGit(Repository repo) {
    super(repo);
  }

  @Override
  public void close() {
    this.getRepository().close();
  }

}
