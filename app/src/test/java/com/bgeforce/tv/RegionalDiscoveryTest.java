package com.bgeforce.tv;
import static org.junit.Assert.*;
import java.util.*;
import okhttp3.*;
import org.json.*;
import org.junit.Test;
public class RegionalDiscoveryTest {
  @Test public void explicitServerChoiceIsNotOverridden() throws Exception {
    List<Request> calls=new ArrayList<>();
    Api api=QueueHandoffRegressionTest.api(Collections.singletonMap("serverUrl",QueueHandoffRegressionTest.LOCAL),c->{
      calls.add(c.request());assertEquals("POST",c.request().method());
      assertEquals(HttpUrl.get(QueueHandoffRegressionTest.LOCAL).host(),c.request().url().host());
      return SessionFlowTest.response(c.request(),200,QueueHandoffRegressionTest.queued(10,null));});
    Game g=SessionFlowTest.game();new SessionClient(api).create(g,g.selected());assertEquals(1,calls.size());
  }
  @Test public void discoveryAuthorizationErrorPreventsAnIncompletePost() throws Exception {
    List<Request> calls=new ArrayList<>();
    Api api=QueueHandoffRegressionTest.api(Collections.emptyMap(),c->{calls.add(c.request());return SessionFlowTest.response(c.request(),403,new JSONObject());});
    try{Game g=SessionFlowTest.game();new SessionClient(api).create(g,g.selected());fail();}catch(Api.Failure expected){assertEquals(403,expected.code);}
    assertEquals(1,calls.size());assertEquals("GET",calls.get(0).method());
  }
  @Test public void missingDiscoveryCanStillFollowThePostResponsesController() throws Exception {
    List<Request> calls=new ArrayList<>();
    Api api=QueueHandoffRegressionTest.api(Collections.emptyMap(),c->{Request r=c.request();calls.add(r);
      if(r.url().encodedPath().equals("/v2/serverInfo"))return SessionFlowTest.response(r,503,new JSONObject());
      if(r.method().equals("POST"))return SessionFlowTest.response(r,200,QueueHandoffRegressionTest.queued(25,HttpUrl.get(QueueHandoffRegressionTest.CONTROL).host()));
      assertEquals(HttpUrl.get(QueueHandoffRegressionTest.CONTROL).host(),r.url().host());
      return SessionFlowTest.response(r,200,QueueHandoffRegressionTest.queued(10,null));});
    SessionClient client=new SessionClient(api);Game g=SessionFlowTest.game();assertEquals(10,client.poll(client.create(g,g.selected())).queue);assertEquals(3,calls.size());
  }
  @Test public void malformedLocalRegionDoesNotChooseArbitrarilyAmongMultipleRegions() throws Exception {
    JSONArray entries=QueueHandoffRegressionTest.info().getJSONArray("metaData");
    entries.put(Json.o("key","local-region","value","unknown"));
    // An unknown local-region cannot select an arbitrary item from several regions.
    assertEquals(Api.DEFAULT_BASE,SessionClient.regionalBase(Api.DEFAULT_BASE,entries));
    assertEquals(Api.DEFAULT_BASE,SessionClient.regionalBase(Api.DEFAULT_BASE,Json.a(
        Json.o("key","local-region","value","untrusted"),Json.o("key","untrusted","value","https://evil.example/"))));
  }
}
