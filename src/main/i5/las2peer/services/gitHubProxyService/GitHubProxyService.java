package i5.las2peer.services.gitHubProxyService;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;

import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
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
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import com.fasterxml.jackson.core.JsonProcessingException;

import i5.las2peer.api.Service;
import i5.las2peer.logging.L2pLogger;
import i5.las2peer.logging.NodeObserver.Event;
import i5.las2peer.restMapper.HttpResponse;
import i5.las2peer.restMapper.MediaType;
import i5.las2peer.restMapper.RESTMapper;
import i5.las2peer.restMapper.annotations.ContentParam;
import i5.las2peer.restMapper.annotations.Version;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import io.swagger.annotations.Contact;
import io.swagger.annotations.Info;
import io.swagger.annotations.License;
import io.swagger.annotations.SwaggerDefinition;
import io.swagger.jaxrs.Reader;
import io.swagger.models.Swagger;
import io.swagger.util.Json;

/**
 * 
 * CAE GitHubProxy Service
 * 
 * A LAS2peer service providing an RESTful api to store and load files to and from a repository.
 * Part of the CAE.
 * 
 */

@Path("CAE/github")
@Version("0.1")
@Api
@SwaggerDefinition(info = @Info(title = "CAE GitHub Proxy Service", version = "0.1",
    description = "A LAS2peer service used for store and load files from CAE components on GitHub. Part of the CAE.",
    termsOfService = "none",
    contact = @Contact(name = "Thomas Winkler", url = "https://github.com/thwinkler/",
        email = "winkler@dbis.rwth-aachen.de"),
    license = @License(name = "BSD",
        url = "https://github.com/PedeLa/CAE-Model-Persistence-Service//blob/master/LICENSE.txt")))

public class GitHubProxyService extends Service {

  private static final L2pLogger logger = L2pLogger.getInstance(GitHubProxyService.class.getName());

  private String gitHubUser;
  private String gitHubPassword;
  private String gitHubOrganization;
  private String templateRepository;
  private String gitHubUserMail;

  public GitHubProxyService() {
    setFieldValues();
  }

  private Repository getRepository(String repoName) throws Exception {
    File localPath;
    Repository repository = null;
    try {
      localPath = new File(repoName);
      File repoFile = new File(localPath + "/.git");

      if (!repoFile.exists()) {
        repository = this.createLocalRepository(localPath, repoName);
      } else {
        FileRepositoryBuilder builder = new FileRepositoryBuilder();
        repository = builder.setGitDir(repoFile).readEnvironment().findGitDir().build();
      }
    } catch (Exception e) {
      throw e;
    }

    return repository;
  }

  public static String getTraceFileName(String fileName) {
    return "traces/" + fileName + ".traces";
  }

  @SuppressWarnings("unchecked")
  private JSONObject getFileTraces(Repository repository, String fullFileName) throws Exception {
    JSONObject traceModel = this.getTraceModel(repository);
    JSONArray tracedFiles = (JSONArray) traceModel.get("tracedFiles");
    JSONObject fileTraces = null;

    java.nio.file.Path p = java.nio.file.Paths.get(fullFileName);
    String fileName = p.getFileName().toString();
    if (tracedFiles.contains(fullFileName)) {

      try {
        String content = this.getFileContent(repository, getTraceFileName(fileName));
        JSONParser parser = new JSONParser();
        fileTraces = (JSONObject) parser.parse(content);
        fileTraces.put("generatedID", traceModel.get("id"));
      } catch (FileNotFoundException e) {
        logger.printStackTrace(e);
      }
    }

    return fileTraces;

  }



  @SuppressWarnings("unchecked")
  private JSONObject getTraceModel(Repository repository) throws Exception {
    JSONObject result = new JSONObject();
    JSONArray tracedFiles = new JSONArray();
    result.put("tracedFiles", tracedFiles);
    try {
      String jsonCode = this.getFileContent(repository, "traces/tracedFiles.json");
      JSONParser parser = new JSONParser();
      result = (JSONObject) parser.parse(jsonCode);
    } catch (FileNotFoundException e) {
      // if a global trace model is not found, the error should be logged
      logger.printStackTrace(e);
    }

    return result;
  }

  @SuppressWarnings("unchecked")
  private static void addFiletoFileList(TreeWalk tw, JSONArray fileList, String path) {
    String name = tw.getPathString();

    // if (!path.isEmpty()) {
    // name = name.substring(path.length() + 1);
    // }

    JSONObject fileObject = new JSONObject();

    if (tw.isSubtree()) {
      fileObject.put("type", "folder");
    } else {
      fileObject.put("type", "file");
    }
    fileObject.put("path", name);
    fileList.add(fileObject);
  }


  private static void addFile(TreeWalk tw, JSONArray files) {
    addFiletoFileList(tw, files, "");
  }

  public HashMap<String, JSONObject> getAllTracedFiles(String repositoryName) {
    HashMap<String, JSONObject> files = new HashMap<String, JSONObject>();

    try (Repository repository = this.getRepository(repositoryName)) {
      repository.resolve("development");
      JSONArray tracedFiles = (JSONArray) this.getTraceModel(repository).get("tracedFiles");

      try (TreeWalk treeWalk = getRepositoryTreeWalk(repository, true)) {
        while (treeWalk.next()) {
          if (tracedFiles.contains(treeWalk.getPathString())) {
            JSONObject fileObject = new JSONObject();
            String content = this.getFileContent(repository, treeWalk.getPathString());
            JSONObject fileTraces = this.getFileTraces(repository, treeWalk.getPathString());

            fileObject.put("content",
                Base64.getEncoder().encodeToString(content.getBytes("utf-8")));
            fileObject.put("fileTraces", fileTraces);

            files.put(treeWalk.getPathString(), fileObject);
          }
        }
      }

    } catch (Exception e) {
      logger.printStackTrace(e);
    }

    return files;
  }

  public String getFileContent(Repository repository, String fileName) throws Exception {

    String content = "";

    try (TreeWalk treeWalk = this.getRepositoryTreeWalk(repository)) {

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

  public String getFileContent(String repositoryName, String fileName)
      throws FileNotFoundException {
    String content = "";
    try (Repository repository = this.getRepository(repositoryName)) {
      content = this.getFileContent(repository, fileName);
    } catch (Exception e) {
      logger.printStackTrace(e);
    }
    return content;
  }

  public Repository createLocalRepository(File path, String repoName)
      throws InvalidRemoteException, TransportException, GitAPIException {
    logger.info("created new local repository");
    String repositoryAddress = "https://github.com/" + gitHubOrganization + "/" + repoName + ".git";
    Repository repository = null;
    try (Git result = Git.cloneRepository().setURI(repositoryAddress).setDirectory(path)
        .setBranch("gh-pages").call()) {
      repository = result.getRepository();
    }

    return repository;
  }

  public void switchBranch(String branchName, Git git)
      throws IOException, RefAlreadyExistsException, RefNotFoundException, InvalidRefNameException,
      CheckoutConflictException, GitAPIException {
    boolean branchExists = git.getRepository().getRef(branchName) != null;
    if (!branchExists) {
      git.branchCreate().setName(branchName).call();
    }
    git.checkout().setName(branchName).call();
  }

  @PUT
  @Path("{repositoryName}/push/")
  @Produces(MediaType.TEXT_PLAIN)
  @ApiOperation(value = "Push the commits to the remote repo.",
      notes = "Push the commits to the remote repo.")
  @ApiResponses(value = {@ApiResponse(code = HttpURLConnection.HTTP_OK, message = "OK, file found"),
      @ApiResponse(code = HttpURLConnection.HTTP_INTERNAL_ERROR,
          message = "Internal server error")})
  public HttpResponse pushToRemote(@PathParam("repositoryName") String repositoryName) {
    try {
      // TODO: implement lock during the pushing
      boolean isFrontend = repositoryName.startsWith("frontendComponent-");
      String masterBranchName = isFrontend ? "gh-pages" : "master";

      Repository repository = this.getRepository(repositoryName);

      try (Git git = new Git(repository)) {
        this.switchBranch(masterBranchName, git);
        // TODO: check fetch and pull results
        git.fetch().call();
        git.pull().call();

        MergeCommand mCmd = git.merge();
        Ref HEAD = repository.getRef("refs/heads/development");
        mCmd.include(HEAD);
        mCmd.setStrategy(MergeStrategy.THEIRS);
        MergeResult mRes = mCmd.call();

        if (mRes.getMergeStatus().isSuccessful()) {
          CredentialsProvider cp =
              new UsernamePasswordCredentialsProvider(gitHubUser, gitHubPassword);

          PushCommand pushCmd = git.push();
          pushCmd.setCredentialsProvider(cp).setForce(true).setPushAll();
          Iterator<PushResult> it = pushCmd.call().iterator();
          while (it.hasNext()) {
            System.out.println(it.next().toString());
          }

          // switch back to development branch
          this.switchBranch("development", git);
          HttpResponse r = new HttpResponse("OK", HttpURLConnection.HTTP_OK);
          return r;
        } else {
          throw new Exception("Unable to merge master and development branch");
        }

      }



    } catch (Exception e) {
      logger.printStackTrace(e);
      HttpResponse r = new HttpResponse("Internal Error", HttpURLConnection.HTTP_INTERNAL_ERROR);
      return r;
    }
  }

  public HttpResponse storeAndCommitFleRaw(String repositoryName, String filePath, String content) {
    try {
      String commitMessage = "someMessage";

      byte[] base64decodedBytes = Base64.getDecoder().decode(content);
      String decodedString = new String(base64decodedBytes, "utf-8");

      Repository repository = this.getRepository(repositoryName);

      try (Git git = new Git(repository)) {

        // switch to development branch
        this.switchBranch("development", git);

        File file = new File(repository.getDirectory().getParent(), filePath);
        if (file.exists()) {

          FileWriter fW = new FileWriter(file, false);
          fW.write(decodedString);
          fW.close();

          git.add().addFilepattern(filePath).call();
          git.commit().setAuthor(gitHubUser, gitHubUserMail).setMessage(commitMessage).call();
          HttpResponse r =
              new HttpResponse("OK, file stored and commited", HttpURLConnection.HTTP_OK);
          return r;
        } else {
          HttpResponse r = new HttpResponse("404", HttpURLConnection.HTTP_NOT_FOUND);
          return r;
        }

      }

    } catch (Exception e) {
      logger.printStackTrace(e);
      HttpResponse r = new HttpResponse("Internal Error", HttpURLConnection.HTTP_INTERNAL_ERROR);
      return r;
    }
  }

  @POST
  @Path("{repositoryName}/file/")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.TEXT_PLAIN)
  @ApiOperation(
      value = "Returns the content of the given file within the specified repository encoded in Base64.",
      notes = "Returns the content of the given file within the specified repository.")
  @ApiResponses(value = {@ApiResponse(code = HttpURLConnection.HTTP_OK, message = "OK, file found"),
      @ApiResponse(code = HttpURLConnection.HTTP_INTERNAL_ERROR, message = "Internal server error"),
      @ApiResponse(code = HttpURLConnection.HTTP_NOT_FOUND, message = "404, file not found")})
  public HttpResponse storeAndCommitFle(@PathParam("repositoryName") String repositoryName,
      @ContentParam String content) {
    try {

      JSONParser parser = new JSONParser();
      JSONObject contentObject = (JSONObject) parser.parse(content);
      String filePath = contentObject.get("filename").toString();
      String fileContent = contentObject.get("content").toString();
      String commitMessage = contentObject.get("commitMessage").toString();
      JSONObject traces = (JSONObject) contentObject.get("traces");

      byte[] base64decodedBytes = Base64.getDecoder().decode(fileContent);
      String decodedString = new String(base64decodedBytes, "utf-8");

      Repository repository = this.getRepository(repositoryName);

      try (Git git = new Git(repository)) {

        // switch to development branch
        this.switchBranch("development", git);

        File file = new File(repository.getDirectory().getParent(), filePath);
        if (file.exists()) {

          FileWriter fW = new FileWriter(file, false);
          fW.write(decodedString);
          fW.close();

          java.nio.file.Path p = java.nio.file.Paths.get(filePath);
          String fileName = p.getFileName().toString();

          File traceFile =
              new File(repository.getDirectory().getParent(), getTraceFileName(fileName));

          fW = new FileWriter(traceFile, false);
          fW.write(traces.toJSONString());
          fW.close();

          git.add().addFilepattern(filePath).addFilepattern(getTraceFileName(fileName)).call();
          git.commit().setAuthor(gitHubUser, gitHubUserMail).setMessage(commitMessage).call();
          HttpResponse r =
              new HttpResponse("OK, file stored and commited", HttpURLConnection.HTTP_OK);
          return r;
        } else {
          HttpResponse r = new HttpResponse("404", HttpURLConnection.HTTP_NOT_FOUND);
          return r;
        }

      }

    } catch (Exception e) {
      logger.printStackTrace(e);
      HttpResponse r = new HttpResponse("Internal Error", HttpURLConnection.HTTP_INTERNAL_ERROR);
      return r;
    }
  }

  @SuppressWarnings("unchecked")
  @GET
  @Path("{repositoryName}/segment/{modelId}")
  @Produces(MediaType.APPLICATION_JSON)
  @ApiOperation(value = "Returns the segment id and filename for the given model id.",
      notes = "Returns the segment id and filename.")
  @ApiResponses(value = {
      @ApiResponse(code = HttpURLConnection.HTTP_OK, message = "OK, segment found"),
      @ApiResponse(code = HttpURLConnection.HTTP_INTERNAL_ERROR, message = "Internal server error"),
      @ApiResponse(code = HttpURLConnection.HTTP_NOT_FOUND, message = "404, segment not found")})
  public HttpResponse getSegmentOfModelId(@PathParam("repositoryName") String repositoryName,
      @PathParam("modelId") String modelId) {

    Git git = null;
    Repository repository = null;
    try {

      repository = this.getRepository(repositoryName);
      git = new Git(repository);

      this.switchBranch("development", git);
      repository.resolve("development");

      JSONObject resultObject = new JSONObject();

      JSONObject traceModel = this.getTraceModel(repository);
      JSONObject modelsToFiles = (JSONObject) traceModel.get("modelsToFile");

      if (modelsToFiles != null) {
        if (modelsToFiles.containsKey(modelId)) {
          JSONArray fileList = (JSONArray) ((JSONObject) modelsToFiles.get(modelId)).get("files");
          String fileName = (String) fileList.get(0);
          JSONObject fileTraceModel = this.getFileTraces(repository, fileName);
          JSONObject fileTraces = (JSONObject) fileTraceModel.get("traces");
          JSONArray segments = (JSONArray) ((JSONObject) fileTraces.get(modelId)).get("segments");
          String segmentId = (String) segments.get(0);

          resultObject.put("fileName", fileName);
          resultObject.put("segmentId", segmentId);

        } else {
          throw new FileNotFoundException();
        }
      } else {
        throw new Exception("Error: modelsToFiles mapping not found!");
      }

      HttpResponse r = new HttpResponse(resultObject.toJSONString(), HttpURLConnection.HTTP_OK);
      return r;
    } catch (FileNotFoundException fileNotFoundException) {
      HttpResponse r = new HttpResponse("Not found", HttpURLConnection.HTTP_NOT_FOUND);
      return r;
    } catch (Exception e) {
      logger.printStackTrace(e);
      HttpResponse r = new HttpResponse("Internal Error", HttpURLConnection.HTTP_INTERNAL_ERROR);
      return r;
    } finally {
      if (git != null) {
        git.close();
      }
      if (repository != null) {
        repository.close();
      }
    }

  }

  @SuppressWarnings("unchecked")
  @GET
  @Path("{repositoryName}/file/")
  @Produces(MediaType.APPLICATION_JSON)
  @ApiOperation(
      value = "Returns the content of the given file within the specified repository encoded in Base64.",
      notes = "Returns the content of the given file within the specified repository.")
  @ApiResponses(value = {@ApiResponse(code = HttpURLConnection.HTTP_OK, message = "OK, file found"),
      @ApiResponse(code = HttpURLConnection.HTTP_INTERNAL_ERROR, message = "Internal server error"),
      @ApiResponse(code = HttpURLConnection.HTTP_NOT_FOUND, message = "404, file not found")})
  public HttpResponse getFileInRepository(@PathParam("repositoryName") String repositoryName,
      @QueryParam("file") String fileName) {

    Git git = null;
    try {
      Repository repository = this.getRepository(repositoryName);
      git = new Git(repository);
      this.switchBranch("development", git);
      repository.resolve("development");
      JSONObject fileTraces = this.getFileTraces(repository, fileName);


      String content = this.getFileContent(repository, fileName);
      String contentBase64 = Base64.getEncoder().encodeToString(content.getBytes("utf-8"));

      JSONObject resultObject = new JSONObject();
      resultObject.put("content", contentBase64);

      // add file traces to the json response if one exists
      if (fileTraces != null) {
        resultObject.put("traceModel", fileTraces);
      }

      HttpResponse r = new HttpResponse(resultObject.toJSONString(), HttpURLConnection.HTTP_OK);
      return r;
    } catch (FileNotFoundException fileNotFoundException) {
      HttpResponse r = new HttpResponse("Not found", HttpURLConnection.HTTP_NOT_FOUND);
      return r;
    } catch (Exception e) {
      logger.printStackTrace(e);
      HttpResponse r = new HttpResponse("Internal Error", HttpURLConnection.HTTP_INTERNAL_ERROR);
      return r;
    } finally {
      if (git != null) {
        git.close();
      }
    }

  }

  public TreeWalk getRepositoryTreeWalk(Repository repository) throws Exception {
    return this.getRepositoryTreeWalk(repository, false);
  }

  public TreeWalk getRepositoryTreeWalk(Repository repository, boolean recursive) throws Exception {

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
      throw e;
    } finally {
      repository.close();
      revWalk.close();
    }

    return treeWalk;
  }

  /**
   * 
   * @param repoName the name of the repository
   * 
   * @return HttpResponse containing the files of the given repository as a json string
   * 
   */

  @SuppressWarnings("unchecked")
  @GET
  @Path("/{repoName}/files")
  @Produces(MediaType.APPLICATION_JSON)
  @ApiOperation(value = "Lists all files of the given repository.",
      notes = "Lists all files of the given repository.")
  @ApiResponses(value = {
      @ApiResponse(code = HttpURLConnection.HTTP_OK, message = "OK, repository of the model found"),
      @ApiResponse(code = HttpURLConnection.HTTP_INTERNAL_ERROR,
          message = "Internal server error")})
  public HttpResponse listFilesInRepository(@PathParam("repoName") String repoName,
      @QueryParam("path") String path) {

    if (path == null) {
      path = "";
    } else if (path.equals("/")) {
      path = "";
    }

    JSONObject jsonResponse = new JSONObject();
    JSONArray files = new JSONArray();
    jsonResponse.put("files", files);
    Git git = null;
    Repository repository = null;
    try {

      repository = this.getRepository(repoName);
      git = new Git(repository);
      this.switchBranch("development", git);
      repository.resolve("development");
      JSONArray tracedFiles = (JSONArray) this.getTraceModel(repository).get("tracedFiles");
      TreeWalk treeWalk = getRepositoryTreeWalk(repository);

      if (path.isEmpty()) {
        while (treeWalk.next()) {
          addFile(treeWalk, files);
        }
      } else {

        PathFilter filter = PathFilter.create(path);
        boolean folderFound = false;
        treeWalk.setFilter(filter);

        while (treeWalk.next()) {

          if (!folderFound && treeWalk.isSubtree()) {
            treeWalk.enterSubtree();
          }
          if (treeWalk.getPathString().equals(path)) {
            folderFound = true;
            continue;
          }
          if (folderFound) {
            addFiletoFileList(treeWalk, files, path);
          }
        }
      }

    } catch (Exception e) {
      L2pLogger.logEvent(Event.SERVICE_ERROR, "getModelFiles: exception fetching files: " + e);
      logger.printStackTrace(e);
      HttpResponse r = new HttpResponse("IO error!", HttpURLConnection.HTTP_INTERNAL_ERROR);
      return r;
    } finally {
      if (git != null) {
        git.close();
      }
      if (repository != null) {
        repository.close();
      }
    }

    HttpResponse r =
        new HttpResponse(jsonResponse.toString().replace("\\", ""), HttpURLConnection.HTTP_OK);
    return r;
  }

  ////////////////////////////////////////////////////////////////////////////////////////
  // Methods required by the LAS2peer framework.
  ////////////////////////////////////////////////////////////////////////////////////////

  /**
   * 
   * This method is needed for every RESTful application in LAS2peer.
   * 
   * @return the mapping
   * 
   */
  public String getRESTMapping() {
    String result = "";
    try {
      result = RESTMapper.getMethodsAsXML(this.getClass());
    } catch (Exception e) {
      logger.printStackTrace(e);
    }
    return result;
  }


  ////////////////////////////////////////////////////////////////////////////////////////
  // Methods providing a Swagger documentation of the service API.
  ////////////////////////////////////////////////////////////////////////////////////////

  /**
   * 
   * Returns the API documentation for a specific annotated top level resource for purposes of the
   * Swagger documentation.
   * 
   * Note: If you do not intend to use Swagger for the documentation of your Service API, this
   * method may be removed.
   * 
   * Trouble shooting: Please make sure that the endpoint URL below is correct with respect to your
   * service.
   * 
   * @return the resource's documentation
   * 
   */
  @GET
  @Path("/swagger.json")
  @Produces(MediaType.APPLICATION_JSON)
  public HttpResponse getSwaggerJSON() {
    Swagger swagger = new Reader(new Swagger()).read(this.getClass());
    if (swagger == null) {
      return new HttpResponse("Swagger API declaration not available!",
          HttpURLConnection.HTTP_NOT_FOUND);
    }
    try {
      return new HttpResponse(Json.mapper().writeValueAsString(swagger), HttpURLConnection.HTTP_OK);
    } catch (JsonProcessingException e) {
      logger.printStackTrace(e);
      return new HttpResponse(e.getMessage(), HttpURLConnection.HTTP_INTERNAL_ERROR);
    }
  }

  @GET
  @Path("/{repoName}/delete")
  @Produces(MediaType.APPLICATION_JSON)
  @ApiOperation(value = "Deletes the local repository of the given repository name",
      notes = "Deletes the local repository.")
  @ApiResponses(value = {
      @ApiResponse(code = HttpURLConnection.HTTP_OK, message = "OK, local repository deleted"),
      @ApiResponse(code = HttpURLConnection.HTTP_INTERNAL_ERROR,
          message = "Internal server error")})

  public HttpResponse deleteLocalRepository(@PathParam("repoName") String repositoryName) {
    try {
      FileUtils.deleteDirectory(new File(repositoryName));
    } catch (IOException e) {
      logger.printStackTrace(e);
      return new HttpResponse(e.getMessage(), HttpURLConnection.HTTP_INTERNAL_ERROR);
    }
    return new HttpResponse("Ok", HttpURLConnection.HTTP_OK);
  }

}
