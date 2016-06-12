package i5.las2peer.services.gitHubProxyService;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Serializable;
import java.net.HttpURLConnection;
import java.util.Base64;
import java.util.HashMap;

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
import i5.las2peer.services.gitHubProxyService.gitUtils.GitHelper;
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
 * A LAS2peer service providing an RESTful API to store and load files to and from a repository.
 * Part of the CAE.
 * 
 * @author Thomas Winkler
 * 
 */

@Path("CAE/github")
@Version("0.1")
@Api
@SwaggerDefinition(info = @Info(title = "CAE GitHub Proxy Service", version = "0.1",
    description = "A LAS2peer service used for store and load files of CAE components to and from GitHub. Part of the CAE.",
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
  private String gitHubUserMail;
  private boolean useModelCheck;

  public GitHubProxyService() {
    setFieldValues();
  }

  private static String getTraceFileName(String fileName) {
    return "traces/" + fileName + ".traces";
  }

  /**
   * Get the traces for a file
   * 
   * @param git The git object of the repository of the file
   * @param fullFileName The file name whose traces should be returned. Must be the full file name,
   *        i.e. with full file path
   * @return A JSONObject of the file traces or null if the file does not have any traces
   * @throws Exception Thrown if something went wrong
   */

  @SuppressWarnings("unchecked")
  private JSONObject getFileTraces(Git git, String fullFileName) throws Exception {
    JSONObject traceModel = this.getTraceModel(git);
    JSONArray tracedFiles = (JSONArray) traceModel.get("tracedFiles");
    JSONObject fileTraces = null;

    if (tracedFiles.contains(fullFileName)) {

      try {
        String content =
            GitHelper.getFileContent(git.getRepository(), getTraceFileName(fullFileName));
        JSONParser parser = new JSONParser();
        fileTraces = (JSONObject) parser.parse(content);
        fileTraces.put("generationId", traceModel.get("id"));
      } catch (FileNotFoundException e) {
        logger.printStackTrace(e);
      }
    }

    return fileTraces;

  }

  @SuppressWarnings("unchecked")
  private static JSONObject getGuidances(Git git) {

    JSONObject guidances = new JSONObject();
    // add empty json array
    guidances.put("guidances", new JSONArray());

    JSONParser parser = new JSONParser();
    String content = "traces/guidances.json";
    if (content.length() > 0) {
      try {
        guidances =
            (JSONObject) parser.parse(GitHelper.getFileContent(git.getRepository(), content));
      } catch (Exception e) {
        logger.printStackTrace(e);
      }
    }

    return guidances;
  }

  /**
   * Get the global trace model of a component
   * 
   * @param git The git object of the repository
   * @return A JSONObject of the trace model.
   * @throws Exception Thrown if something went wrong.
   */

  @SuppressWarnings("unchecked")
  private JSONObject getTraceModel(Git git) throws Exception {
    JSONObject result = new JSONObject();
    JSONArray tracedFiles = new JSONArray();
    result.put("tracedFiles", tracedFiles);
    try {
      String jsonCode = GitHelper.getFileContent(git.getRepository(), "traces/tracedFiles.json");
      JSONParser parser = new JSONParser();
      result = (JSONObject) parser.parse(jsonCode);
    } catch (FileNotFoundException e) {
      // if a global trace model is not found, the error should be logged
      logger.printStackTrace(e);
    }

    return result;
  }

  /**
   * A private helper method to add the current file or folder of a tree walk to a json array.
   * 
   * @param tw The tree walk which current file/folder should be added to the json array
   * @param files The json array the current file/folder should be added
   * @param path The path of the current file
   */

  @SuppressWarnings("unchecked")
  private static void addFiletoFileList(TreeWalk tw, JSONArray fileList, JSONArray tracedFiles,
      String path) {
    String name = tw.getPathString();

    JSONObject fileObject = new JSONObject();

    if (tw.isSubtree()) {
      if (name.equals("traces")) {
        return;
      }
      fileObject.put("type", "folder");
    } else if (!tracedFiles.contains(name)) {
      return;
    } else {
      fileObject.put("type", "file");
    }
    fileObject.put("path", name);
    fileList.add(fileObject);
  }

  /**
   * A private helper method to add the current file or folder of a tree walk to a json array
   * 
   * @param tw The tree walk which current file/folder should be added to the json array
   * @param files The json array the current file/folder should be added
   */

  private static void addFile(TreeWalk tw, JSONArray files, JSONArray tracedFiles) {
    addFiletoFileList(tw, files, tracedFiles, "");
  }

  @SuppressWarnings("unchecked")
  public HashMap<String, JSONObject> getAllTracedFiles(String repositoryName) {
    HashMap<String, JSONObject> files = new HashMap<String, JSONObject>();

    try (Git git = GitHelper.getLocalGit(repositoryName, gitHubOrganization, "development");) {
      JSONArray tracedFiles = (JSONArray) this.getTraceModel(git).get("tracedFiles");

      try (TreeWalk treeWalk = GitHelper.getRepositoryTreeWalk(git.getRepository(), true)) {
        while (treeWalk.next()) {
          if (tracedFiles.contains(treeWalk.getPathString())) {
            JSONObject fileObject = new JSONObject();
            String content =
                GitHelper.getFileContent(git.getRepository(), treeWalk.getPathString());
            JSONObject fileTraces = this.getFileTraces(git, treeWalk.getPathString());

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

  /**
   * Store the content of the files encoded in based64 to a repository
   * 
   * @param repositoryName The name of the repository
   * @param commitMessage The commit message to use
   * @param files The file list containing the files to commit
   * @return A status string
   */

  public String storeAndCommitFilesRaw(String repositoryName, String commitMessage,
      String[][] files) {

    try (Git git = GitHelper.getLocalGit(repositoryName, gitHubOrganization, "development");) {
      for (String[] fileData : files) {

        String filePath = fileData[0];
        String content = fileData[1];

        byte[] base64decodedBytes = Base64.getDecoder().decode(content);
        String decodedString = new String(base64decodedBytes, "utf-8");

        File file = new File(git.getRepository().getDirectory().getParent(), filePath);
        if (!file.exists()) {
          File dirs = file.getParentFile();
          dirs.mkdirs();
          file.createNewFile();
        }
        FileWriter fW = new FileWriter(file, false);
        fW.write(decodedString);
        fW.close();

        git.add().addFilepattern(filePath).call();

      }

      git.commit().setAuthor(gitHubUser, gitHubUserMail).setMessage(commitMessage).call();


    } catch (Exception e) {
      logger.printStackTrace(e);
      return e.getMessage();
    }

    return "done";
  }

  /**
   * Rename a file of a repository.
   * 
   * @param repositoryName The name of the repository
   * @param newFileName The new file name
   * @param oldFileName The old file name
   * @return String with the status code of the request
   */

  public String renameFile(String repositoryName, String newFileName, String oldFileName) {
    try (Git git = GitHelper.getLocalGit(repositoryName, newFileName, "development")) {

      GitHelper.renameFile(repositoryName, gitHubOrganization, newFileName, oldFileName);
      JSONObject currentTraceFile = this.getFileTraces(git, oldFileName);

      // also rename the trace file if it exists
      if (currentTraceFile != null) {
        GitHelper.renameFile(repositoryName, gitHubOrganization, getTraceFileName(newFileName),
            getTraceFileName(oldFileName));
      }

      return "done";
    } catch (FileNotFoundException e) {
      logger.printStackTrace(e);
      return e.getMessage();
    } catch (Exception e) {
      logger.printStackTrace(e);
      return e.getMessage();
    }
  }

  /**
   * Merges the development branch of the given repository with the master/gh-pages branch and
   * pushes the changes to the remote repository.
   * 
   * @param repositoryName The name of the repository to push the local changes to
   * @return HttpResponse containing the status code of the request or the result of the model
   *         violation if it fails
   */

  @SuppressWarnings("unchecked")
  @PUT
  @Path("{repositoryName}/push/")
  @Produces(MediaType.APPLICATION_JSON)
  @ApiOperation(value = "Merge and push the commits to the remote repository",
      notes = "Push the commits to the remote repo.")
  @ApiResponses(
      value = {@ApiResponse(code = HttpURLConnection.HTTP_OK, message = "OK"), @ApiResponse(
          code = HttpURLConnection.HTTP_INTERNAL_ERROR, message = "Internal server error")})
  public HttpResponse pushToRemote(@PathParam("repositoryName") String repositoryName) {
    try {

      // determine which branch to merge in
      boolean isFrontend = repositoryName.startsWith("frontendComponent-");
      String masterBranchName = isFrontend ? "gh-pages" : "master";

      GitHelper.mergeIntoMasterBranch(repositoryName, gitHubOrganization, masterBranchName,
          new UsernamePasswordCredentialsProvider(gitHubUser, gitHubPassword));
      JSONObject result = new JSONObject();
      result.put("status", "ok");
      HttpResponse r = new HttpResponse(result.toJSONString(), HttpURLConnection.HTTP_OK);
      return r;
    } catch (Exception e) {
      logger.printStackTrace(e);
      HttpResponse r = new HttpResponse("Internal Error", HttpURLConnection.HTTP_INTERNAL_ERROR);
      return r;
    }
  }

  /**
   * Store the content and traces of a file in a repository and commit it to the local repository.
   * 
   * @param repositoryName The name of the repository
   * @param content A json string containing the content of the file encoded in base64 and its file
   *        traces
   * @return HttpResponse with the status code of the request
   */

  @SuppressWarnings("unchecked")
  @POST
  @Path("{repositoryName}/file/")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  @ApiOperation(
      value = "Stores the content for the given file in the local repository and commits the changes.",
      notes = "Stores the content for the given file in the local repository and commits the changes.")
  @ApiResponses(value = {@ApiResponse(code = HttpURLConnection.HTTP_OK, message = "OK, file found"),
      @ApiResponse(code = HttpURLConnection.HTTP_INTERNAL_ERROR, message = "Internal server error"),
      @ApiResponse(code = HttpURLConnection.HTTP_NOT_FOUND, message = "404, file not found")})
  public HttpResponse storeAndCommitFle(@PathParam("repositoryName") String repositoryName,
      @ContentParam String content) {
    try {
      JSONObject result = new JSONObject();

      JSONParser parser = new JSONParser();
      JSONObject contentObject = (JSONObject) parser.parse(content);
      String filePath = contentObject.get("filename").toString();
      String fileContent = contentObject.get("content").toString();
      String commitMessage = contentObject.get("commitMessage").toString();
      JSONObject traces = (JSONObject) contentObject.get("traces");

      byte[] base64decodedBytes = Base64.getDecoder().decode(fileContent);
      String decodedString = new String(base64decodedBytes, "utf-8");

      try (Git git = GitHelper.getLocalGit(repositoryName, gitHubOrganization, "development");) {

        File file = new File(git.getRepository().getDirectory().getParent(), filePath);
        if (file.exists()) {

          FileWriter fW = new FileWriter(file, false);
          fW.write(decodedString);
          fW.close();

          java.nio.file.Path p = java.nio.file.Paths.get(filePath);

          if (this.useModelCheck) {
            JSONObject tracedFileObject = new JSONObject();
            tracedFileObject.put("content", fileContent);
            tracedFileObject.put("fileTraces", traces);

            HashMap<String, JSONObject> tracedFile = new HashMap<String, JSONObject>();
            tracedFile.put(filePath, tracedFileObject);

            Serializable[] payload = {getGuidances(git), tracedFile};
            JSONArray guidances = (JSONArray) this.invokeServiceMethod(
                "i5.las2peer.services.codeGenerationService.CodeGenerationService@0.1",
                "checkModel", payload);
            if (guidances.size() > 0) {

              result.put("status", "Model violation check fails");
              result.put("guidances", guidances);
              HttpResponse r = new HttpResponse(result.toJSONString(), HttpURLConnection.HTTP_OK);
              return r;
            }
          }

          JSONObject currentTraceFile = this.getFileTraces(git, filePath);
          if (currentTraceFile != null) {
            String generationId = (String) currentTraceFile.get("generationId");
            String payloadGenerationId = (String) traces.get("generationId");
            if (!generationId.equals(payloadGenerationId)) {
              HttpResponse r = new HttpResponse("Commit rejected. Wrong generation id",
                  HttpURLConnection.HTTP_CONFLICT);
              return r;
            }
          }

          File traceFile =
              new File(git.getRepository().getDirectory().getParent(), getTraceFileName(filePath));

          fW = new FileWriter(traceFile, false);
          fW.write(traces.toJSONString());
          fW.close();

          git.add().addFilepattern(filePath).addFilepattern(getTraceFileName(filePath)).call();
          git.commit().setAuthor(gitHubUser, gitHubUserMail).setMessage(commitMessage).call();
          result.put("status", "OK, file stored and commited");
          HttpResponse r = new HttpResponse(result.toJSONString(), HttpURLConnection.HTTP_OK);
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

  /**
   * Calculate and returns the file name and segment id for a given model id.
   * 
   * @param repositoryName The name of the repository
   * @param modelId The id of the model.
   * @return HttpResponse with the status code of the request and the file name and segment id of
   *         the model
   */

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

    try (Git git = GitHelper.getLocalGit(repositoryName, gitHubOrganization, "development");) {

      JSONObject resultObject = new JSONObject();

      JSONObject traceModel = this.getTraceModel(git);
      JSONObject modelsToFiles = (JSONObject) traceModel.get("modelsToFile");

      if (modelsToFiles != null) {
        if (modelsToFiles.containsKey(modelId)) {
          JSONArray fileList = (JSONArray) ((JSONObject) modelsToFiles.get(modelId)).get("files");
          String fileName = (String) fileList.get(0);
          JSONObject fileTraceModel = this.getFileTraces(git, fileName);
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
    }

  }

  /**
   * Get the files needed for the live preview widget collected in one response
   */

  @SuppressWarnings("unchecked")
  @GET
  @Path("{repositoryName}/livePreviewFiles/")
  @Produces(MediaType.APPLICATION_JSON)
  @ApiOperation(
      value = "Returns all needed files for the live preview widget of the given repository encoded in Base64.",
      notes = "Returns all needed files for the live preview widget.")
  @ApiResponses(value = {@ApiResponse(code = HttpURLConnection.HTTP_OK, message = "OK, file found"),
      @ApiResponse(code = HttpURLConnection.HTTP_INTERNAL_ERROR, message = "Internal server error"),
      @ApiResponse(code = HttpURLConnection.HTTP_NOT_FOUND, message = "404, file not found")})
  public HttpResponse getLivePreviewFiles(@PathParam("repositoryName") String repositoryName) {
    if (repositoryName.startsWith("frontendComponent")
        && GitHelper.existsLocalRepository(repositoryName)) {

      try (Git git = GitHelper.getLocalGit(repositoryName, gitHubOrganization, "development")) {

        JSONObject result = new JSONObject();
        JSONArray fileList = new JSONArray();

        String[] neededFileNames = {"widget.xml", "js/applicationScript.js"};

        for (String fileName : neededFileNames) {
          String content = GitHelper.getFileContent(git.getRepository(), fileName);
          String contentBase64 = Base64.getEncoder().encodeToString(content.getBytes("utf-8"));

          JSONObject fileObject = new JSONObject();
          fileObject.put("fileName", fileName);
          fileObject.put("content", contentBase64);
          fileList.add(fileObject);
        }

        result.put("files", fileList);
        HttpResponse r = new HttpResponse(result.toJSONString(), HttpURLConnection.HTTP_OK);
        return r;
      } catch (FileNotFoundException e) {
        logger.info(repositoryName + " not found");
        HttpResponse r =
            new HttpResponse(repositoryName + " not found", HttpURLConnection.HTTP_NOT_FOUND);
        return r;
      } catch (Exception e) {
        logger.printStackTrace(e);
        HttpResponse r = new HttpResponse("Internal Error", HttpURLConnection.HTTP_INTERNAL_ERROR);
        return r;
      }
    } else {
      HttpResponse r = new HttpResponse("Only frontend components are supported",
          HttpURLConnection.HTTP_NOT_ACCEPTABLE);
      return r;
    }

  }

  /**
   * Returns the content encoded in base64 of a file in a repository
   * 
   * @param repositoryName The name of the repository
   * @param fileName The absolute path of the file
   * @return HttpResponse containing the status code of the request and the content of the file
   *         encoded in base64 if everything was fine.
   */

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

    try (Git git = GitHelper.getLocalGit(repositoryName, gitHubOrganization, "development")) {

      JSONObject fileTraces = this.getFileTraces(git, fileName);

      String content = GitHelper.getFileContent(git.getRepository(), fileName);
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

  /**
   * List all files of a folder of a repository.
   * 
   * @param repositoryName the name of the repository
   * @param path the path of the folder whose files should be listed
   * @return HttpResponse containing the files of the given repository as a json string
   * 
   */

  @SuppressWarnings("unchecked")
  @GET
  @Path("/{repoName}/files")
  @Produces(MediaType.APPLICATION_JSON)
  @ApiOperation(value = "Lists all files of a folder of the given repository.",
      notes = "Lists all files of the given repository.")
  @ApiResponses(value = {
      @ApiResponse(code = HttpURLConnection.HTTP_OK, message = "OK, repository of the model found"),
      @ApiResponse(code = HttpURLConnection.HTTP_INTERNAL_ERROR,
          message = "Internal server error")})
  public HttpResponse listFilesInRepository(@PathParam("repoName") String repositoryName,
      @QueryParam("path") String path) {

    if (path == null) {
      path = "";
    } else if (path.equals("/")) {
      path = "";
    }

    JSONObject jsonResponse = new JSONObject();
    JSONArray files = new JSONArray();
    jsonResponse.put("files", files);
    try (Git git = GitHelper.getLocalGit(repositoryName, gitHubOrganization, "development");) {

      JSONArray tracedFiles = (JSONArray) this.getTraceModel(git).get("tracedFiles");
      TreeWalk treeWalk = GitHelper.getRepositoryTreeWalk(git.getRepository());

      if (path.isEmpty()) {
        while (treeWalk.next()) {
          addFile(treeWalk, files, tracedFiles);
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
            addFiletoFileList(treeWalk, files, tracedFiles, path);
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

  /**
   * Deletes a local repository
   * 
   * @param repositoryName The repository to delete
   * @return HttpResponse containing a status code
   */

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
