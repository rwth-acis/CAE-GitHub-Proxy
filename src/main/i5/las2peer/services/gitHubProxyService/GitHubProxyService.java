package i5.las2peer.services.gitHubProxyService;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.net.HttpURLConnection;
import java.util.Base64;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
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

  private JSONObject getFileTraces(Repository repository, String fullFileName) throws Exception {
    JSONArray tracedFiles = this.getTracedFiles(repository);
    JSONObject fileTraces = null;

    java.nio.file.Path p = java.nio.file.Paths.get(fullFileName);
    String fileName = p.getFileName().toString();

    if (tracedFiles.contains(fileName)) {

      try {
        String content = this.getFileContent(repository, getTraceFileName(fileName));
        JSONParser parser = new JSONParser();
        fileTraces = (JSONObject) parser.parse(content);
      } catch (FileNotFoundException e) {
        logger.printStackTrace(e);
      }
    }

    return fileTraces;

  }



  private JSONArray getTracedFiles(Repository repository) throws Exception {
    JSONArray result = new JSONArray();
    try {
      String jsonCode = this.getFileContent(repository, "traces/tracedFiles.json");
      JSONParser parser = new JSONParser();
      JSONObject jobj = (JSONObject) parser.parse(jsonCode);
      result = (JSONArray) jobj.get("tracedFiles");
    } catch (FileNotFoundException e) {
      // if a global trace model is not found, the error should be logged
      logger.printStackTrace(e);
    }

    return result;
  }

  @SuppressWarnings("unchecked")
  private static void addFiletoFileList(TreeWalk tw, JSONArray fileList, String path) {
    String name = tw.getPathString();

    if (!path.isEmpty()) {
      name = name.substring(path.length() + 1);
    }

    JSONObject fileObject = new JSONObject();

    if (tw.isSubtree()) {
      fileObject.put("type", "folder");
    } else {
      fileObject.put("type", "file");
    }
    fileObject.put("name", name);
    fileList.add(fileObject);
  }


  private static void addFile(TreeWalk tw, JSONArray files) {
    addFiletoFileList(tw, files, "");
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
    repository =
        Git.cloneRepository().setURI(repositoryAddress).setDirectory(path).call().getRepository();
    return repository;
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
          git.commit().setMessage(commitMessage).call();
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
    try {
      Repository repository = this.getRepository(repositoryName);
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
   * @return HttpResponse containing the status code of the request and (if successful) the model as
   *         a JSON string
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

    try {

      Repository repository = this.getRepository(repoName);
      JSONArray tracedFiles = this.getTracedFiles(repository);
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

}
