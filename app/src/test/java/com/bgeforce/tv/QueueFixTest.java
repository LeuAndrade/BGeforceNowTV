package com.bgeforce.tv;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.io.*;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import org.json.*;
import org.junit.Test;
public class QueueFixTest {
  static final String OWNER="https://np-atl-03.cloudmatchbeta.nvidiagrid.net/";
  static JSONObject queue(int n) { return Json.o("requestStatus",Json.o("statusCode",1),"session",Json.o(
      "sessionId","queue-one","status",1,"queuePosition",n,
      "seatSetupInfo",Json.o("seatSetupStep",1,"queuePosition",100),
      
      "sessionRequestData",Json.o("appId","12345","deviceHashId","fixture-device"))); }
  static JSONObject expired(){ return Json.o("requestStatus",Json.o("statusCode",69,"statusDescription","SESSION_REQUEST_IN_QUEUE_ABANDONED")); }
  @Test public void currentPositionDecreasesWhenNoControlRedirectIsSupplied() throws Exception {
    int[] n={0};List<Request> calls=new ArrayList<>();
    Api api=SessionFlowTest.api(OWNER,Collections.emptyMap(),c->{calls.add(c.request());
      return SessionFlowTest.response(c.request(),200,queue(c.request().url().host().equals(HttpUrl.get(OWNER).host())?new int[]{80,30,0}[n[0]++]:100));});
    SessionClient client=new SessionClient(api);SessionClient.Seat s=SessionClient.parse(queue(100),OWNER);
    for(int expected:new int[]{80,30,0}){s=client.poll(s);assertEquals(expected,new QueueProgress().update(s));assertEquals(OWNER,s.queueBase);}
    assertEquals(3,calls.size());
    for(Request req:calls)assertEquals(HttpUrl.get(OWNER).host(),req.url().host());
  }
  @Test public void queuedTimeoutDoesNotPollReplicas() throws Exception {
    int[] calls={0};Api api=SessionFlowTest.api(OWNER,Collections.emptyMap(),c->{calls[0]++;
      assertEquals(TimeUnit.SECONDS.toNanos(8),c.call().timeout().timeoutNanos());throw new java.net.SocketTimeoutException("fixture");});
    try{new SessionClient(api).poll(SessionClient.parse(queue(100),OWNER));fail();}catch(java.net.SocketTimeoutException expected){}
    assertEquals(1,calls[0]);
  }
  @Test public void terminal503StopsWithoutFallbackOrMutation() throws Exception {
    List<Request> calls=new ArrayList<>();Api api=SessionFlowTest.api(OWNER,Collections.emptyMap(),c->{calls.add(c.request());return SessionFlowTest.response(c.request(),503,expired());});
    try{new SessionClient(api).poll(SessionClient.parse(queue(100),OWNER));fail();}catch(SessionClient.QueueAbandoned expected){assertTrue(expected.getMessage().contains("69"));}
    assertEquals(1,calls.size());assertEquals("GET",calls.get(0).method());assertFalse(SessionClient.transientRead(new Api.Failure(503,expired())));
  }
  @Test public void successfulHttpDoesNotHideTerminalApplicationCode() throws Exception {
    Api api=SessionFlowTest.api(OWNER,Collections.emptyMap(),c->SessionFlowTest.response(c.request(),200,expired()));
    try{new SessionClient(api).poll(SessionClient.parse(queue(100),OWNER));fail();}catch(SessionClient.QueueAbandoned expected){assertFalse(SessionClient.transientRead(expected));}
  }
  @Test public void generic503IsStillTransient() {assertTrue(SessionClient.transientRead(new java.io.InterruptedIOException("timeout")));assertTrue(SessionClient.transientRead(new Api.Failure(503,Json.o("requestStatus",Json.o("statusCode",4)))));}
  @Test public void explicitRetrySkipsExpiredListedIdAndCreatesExactlyOnce() throws Exception {
    int[] posts={0};List<String> methods=new ArrayList<>();
    Api api=SessionFlowTest.api(OWNER,Collections.emptyMap(),c->{Request req=c.request();methods.add(req.method());
      if(req.method().equals("POST")){posts[0]++;return SessionFlowTest.response(req,200,queue(80));}
      if(req.url().encodedPath().equals("/v2/session/queue-one"))return SessionFlowTest.response(req,503,expired());
      return SessionFlowTest.response(req,200,Json.o("sessions",Json.a(Json.obj(queue(100),"session"))));});
    SessionClient client=new SessionClient(api);Game game=SessionFlowTest.game();SessionClient.Seat s=client.create(game,game.selected());
    try{client.poll(s);fail();}catch(SessionClient.QueueAbandoned expected){}
    assertEquals(1,posts[0]);assertEquals(80,client.recoverOrCreate(game,game.selected()).queue);
    assertEquals(Arrays.asList("POST","GET","GET","POST"),methods);
  }
  @Test public void staleQueuedListingIsValidatedBeforeResume() throws Exception {
    List<String> paths=new ArrayList<>();Api api=SessionFlowTest.api(OWNER,Collections.emptyMap(),c->{paths.add(c.request().url().encodedPath());
      return c.request().url().encodedPath().equals("/v2/session/queue-one")?SessionFlowTest.response(c.request(),503,expired())
        :SessionFlowTest.response(c.request(),200,Json.o("sessions",Json.a(Json.obj(queue(100),"session"))));});
    assertNull(new SessionClient(api).findActive("12345"));assertEquals(Arrays.asList("/v2/session","/v2/session/queue-one"),paths);
  }
  @Test public void sessionQueriesAreUncachedAndKeepClientIdentity() throws Exception {
    List<Request> calls=new ArrayList<>();Api api=SessionFlowTest.api(OWNER,Collections.emptyMap(),c->{calls.add(c.request());return SessionFlowTest.response(c.request(),200,queue(80));});
    SessionClient client=new SessionClient(api);Game game=SessionFlowTest.game();client.poll(client.create(game,game.selected()));
    assertEquals(calls.get(0).header("nv-client-id"),calls.get(1).header("nv-client-id"));assertTrue(calls.get(1).header("Cache-Control").contains("no-store"));
  }
  @Test public void provisioningKeepsQueueOwner() throws Exception {
    SessionClient.Seat s=SessionClient.parse(queue(100),OWNER);s.status=6;
    assertEquals(Collections.singletonList(OWNER),new ArrayList<>(SessionClient.readTargets(s)));
  }
  @Test public void expiredResumeRetryClearsOldTarget() throws Exception {
    StreamActivity a=mock(StreamActivity.class,withSettings().useConstructor().defaultAnswer(CALLS_REAL_METHODS));
    android.content.Intent intent=mock(android.content.Intent.class);doReturn(intent).when(a).getIntent();
    java.lang.reflect.Method m=StreamActivity.class.getDeclaredMethod("forgetAbandonedQueue");m.setAccessible(true);m.invoke(a);
    verify(intent).removeExtra("resumeId");verify(intent).removeExtra("resumeBase");
  }
}
