package i5.las2peer.services.gitHubProxyService;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Serializable;

import org.apache.commons.io.FileUtils;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import i5.las2peer.p2p.LocalNode;
import i5.las2peer.p2p.ServiceNameVersion;
import i5.las2peer.restMapper.MediaType;
import i5.las2peer.restMapper.data.Pair;
import i5.las2peer.security.ServiceAgent;
import i5.las2peer.security.UserAgent;
import i5.las2peer.testing.MockAgentFactory;
import i5.las2peer.webConnector.WebConnector;
import i5.las2peer.webConnector.client.ClientResponse;
import i5.las2peer.webConnector.client.MiniClient;


/**
 *
 */
public class GitHubProxyServiceTest {

  private static final String HTTP_ADDRESS = "http://127.0.0.1";
  private static final int HTTP_PORT = WebConnector.DEFAULT_HTTP_PORT;

  private static LocalNode node;
  private static WebConnector connector;
  private static ByteArrayOutputStream logStream;

  private static final String mainPath = "CAE/github/";

  private static UserAgent testAgent;
  private static final String testPass = "adamspass";

  private static final String gitHubProxyService = GitHubProxyService.class.getCanonicalName();

  private static ServiceAgent testService;
  private static ServiceNameVersion testServiceNameVersion;

  private static final String testRepositoryName = "frontendComponent-JUnit-Test-Repository";

  private static JSONObject fileJson1;
  private static final String FILE_NAME1 = "./testfiles/testWidget1.json";

  /**
   * Called before the tests start.
   * 
   * @throws Exception
   */
  @BeforeClass
  public static void startServer() throws Exception {
    try {
      JSONParser parser = new JSONParser();
      fileJson1 = ((JSONObject) parser.parse(new FileReader(FILE_NAME1)));
    } catch (Exception e) {
      e.printStackTrace();
      fail("File loading problems: " + e);
    }
    // start node
    node = LocalNode.newNode();
    testAgent = MockAgentFactory.getAdam();
    testAgent.unlockPrivateKey(testPass); // agent must be unlocked in order to be stored
    node.storeAgent(testAgent);
    node.launch();

    testServiceNameVersion = new ServiceNameVersion(gitHubProxyService, "0.1");
    testService = ServiceAgent.createServiceAgent(testServiceNameVersion, "a pass");
    testService.unlockPrivateKey("a pass");

    node.registerReceiver(testService);

    // start connector
    logStream = new ByteArrayOutputStream();

    connector = new WebConnector(true, HTTP_PORT, false, 1000);
    connector.setLogStream(new PrintStream(logStream));
    connector.start(node);
    Thread.sleep(10000); // wait a second for the connector to become ready
    testAgent = MockAgentFactory.getAdam();

    connector.updateServiceList();
    // avoid timing errors: wait for the repository manager to get all services before continuing
    try {
      System.out.println("waiting..");
      Thread.sleep(1000);
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }

  /**
   * This test tries to fetch the content of a widget from a test repository. This should also work
   * even before the repository was cloned by the GitHub proxy service
   */

  @Test
  public void testGetFileContentTestNotYetCloned() {
    MiniClient c = new MiniClient();
    c.setAddressPort(HTTP_ADDRESS, HTTP_PORT);

    try {
      c.setLogin(Long.toString(testAgent.getId()), testPass);
      @SuppressWarnings("unchecked")
      ClientResponse result = c.sendRequest("GET",
          mainPath + "frontendComponent-JUnit-Test-Repository/file/?file=widget.xml", "",
          MediaType.APPLICATION_JSON, MediaType.APPLICATION_JSON, new Pair[] {});
      assertEquals(200, result.getHttpCode());

    } catch (Exception e) {
      e.printStackTrace();
      fail("Exception: " + e);
    }
  }

  /**
   * Tests if files with an old generation id are rejected by the service
   * 
   * @throws Exception
   */
  @Test
  public void rejectFileWithOldGenerationId() throws Exception {
    MiniClient c = new MiniClient();
    c.setAddressPort(HTTP_ADDRESS, HTTP_PORT);

    // test method
    try {
      c.setLogin(Long.toString(testAgent.getId()), testPass);
      @SuppressWarnings("unchecked")
      ClientResponse result = c.sendRequest("PUT",
          mainPath + "frontendComponent-JUnit-Test-Repository/file/", fileJson1.toJSONString(),
          MediaType.APPLICATION_JSON, MediaType.APPLICATION_JSON, new Pair[] {});
      assertEquals(409, result.getHttpCode());
    } catch (Exception e) {
      e.printStackTrace();
      fail("Exception: " + e);
    }
  }

  /**
   * Tests a renaming operation for a file in a local repository
   */

  @Test
  public void renameFileTest() {
    try {
      String result = (String) node.invoke(testService, testServiceNameVersion, "renameFile",
          new Serializable[] {testRepositoryName, "widget2.xml", "widget.xml"});
      assertEquals("done", result);
      File fileWidget2 = new File(testRepositoryName + "/widget2.xml");
      File fileWidget = new File(testRepositoryName + "/widget.xml");

      assertEquals(true, fileWidget2.exists());
      assertEquals(false, fileWidget.exists());
      // rename it back to its original name
      result = (String) node.invoke(testService, testServiceNameVersion, "renameFile",
          new Serializable[] {testRepositoryName, "widget.xml", "widget2.xml"});
      assertEquals("done", result);

    } catch (Exception e) {
      e.printStackTrace();
      fail("Exception: " + e);
    }
  }

  /**
   * Tests the deletion of a file from a local repository
   */
  @Test
  public void deleteFileTest() {
    try {
      File fileLogo = new File(testRepositoryName + "/img/logo.png");
      assertEquals(true, fileLogo.exists());

      String result = (String) node.invoke(testService, testServiceNameVersion, "deleteFile",
          new Serializable[] {testRepositoryName, "img/logo.png"});
      assertEquals("done", result);

      assertEquals(false, fileLogo.exists());

    } catch (Exception e) {
      e.printStackTrace();
      fail("Exception: " + e);
    }
  }

  /**
   * Called after the tests have finished.
   * 
   * @throws Exception
   */
  @AfterClass
  public static void shutDownServer() throws Exception {
    try {
      // delete used repositories
      FileUtils.deleteDirectory(new File("frontendComponent-JUnit-Test-Repository"));
    } catch (IOException e) {
      e.printStackTrace();
    }
  }


}