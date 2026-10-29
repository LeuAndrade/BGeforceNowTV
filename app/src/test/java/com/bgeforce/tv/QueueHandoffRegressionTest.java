package com.bgeforce.tv;

import static org.junit.Assert.*;
import java.util.*;
import okhttp3.*;
import org.json.*;
import org.junit.Test;

/** Raw protocol shape modelled on the public session-control client; no live account data. */
public class QueueHandoffRegressionTest {
  static final String ROOT = Api.DEFAULT_BASE;
  static final String LOCAL = "https://np-atl-03.cloudmatchbeta.nvidiagrid.net/";
  static final String CONTROL = "https://np-mia-04.cloudmatchbeta.nvidiagrid.net/";
  static final String NEXT = "https://np-mia-05.cloudmatchbeta.nvidiagrid.net/";

  static Api api(Map<String,Object> preferences, Interceptor wire) throws Exception {
    ApiFlowTest.MemoryAccount account = new ApiFlowTest.MemoryAccount();
    account.saved.put("base", ROOT);
    account.saved.getJSONObject("user").put("age", 37);
    Api api = new Api(account, SessionFlowTest.settings(preferences),
        new OkHttpClient.Builder().addInterceptor(wire).build());
    assertTrue(api.restore());
    return api;
  }

  static JSONObject info() {
    return Json.o("requestStatus",Json.o("statusCode",1),"metaData",Json.a(
        Json.o("key","gfn-regions","value","east,west"),
        Json.o("key","west","value","https://np-lax-01.cloudmatchbeta.nvidiagrid.net/"),
        Json.o("key","east","value",LOCAL),
        Json.o("key","local-region","value","east")));
  }

  static JSONObject queued(int position, Object control) {
    JSONObject s=Json.o("sessionId","handoff-session","status",1,
        "seatSetupInfo",Json.o("seatSetupStep",1,"queuePosition",position),
        "sessionRequestData",Json.o("appId","12345","deviceHashId","fixture-device"));
    if (control!=null) try {s.put("sessionControlInfo",Json.o("ip",control,"port",443));}
    catch(JSONException e){throw new IllegalArgumentException(e);}
    return Json.o("requestStatus",Json.o("statusCode",1),"session",s);
  }

  @Test public void discoveryUsesDeclaredLocalRegionBeforePostAndReadsAssignedController() throws Exception {
    List<Request> requests=new ArrayList<>();
    Api api=api(Collections.emptyMap(),c->{
      Request r=c.request();requests.add(r);
      assertEquals("BROWSER",r.header("nv-client-type"));
      assertEquals("WEBRTC",r.header("nv-client-streamer"));
      assertEquals("GFNJWT identity-old",r.header("Authorization"));
      if(r.url().encodedPath().equals("/v2/serverInfo")) {
        assertEquals("GET",r.method());return SessionFlowTest.response(r,200,info());
      }
      if(r.method().equals("POST")) {
        assertEquals(HttpUrl.get(LOCAL).host(),r.url().host());
        return SessionFlowTest.response(r,200,queued(100,Json.a(HttpUrl.get(CONTROL).host())));
      }
      assertEquals(HttpUrl.get(CONTROL).host(),r.url().host());
      return SessionFlowTest.response(r,200,queued(75,Json.a(HttpUrl.get(CONTROL).host())));
    });
    SessionClient client=new SessionClient(api);Game g=SessionFlowTest.game();
    SessionClient.Seat s=client.create(g,g.selected());s=client.poll(s);
    assertEquals(75,s.queue);assertEquals(CONTROL,s.base);
    assertEquals(3,requests.size());
    assertEquals("GET",requests.get(0).method());
    assertEquals("POST",requests.get(1).method());
    assertEquals("GET",requests.get(2).method());
    assertEquals(1,new HashSet<>(Arrays.asList(requests.get(0).header("nv-client-id"),
        requests.get(1).header("nv-client-id"),requests.get(2).header("nv-client-id"))).size());
  }

  @Test public void listAddressInInitialQueueReplyDoesNotFallBackToGlobalBroker() throws Exception {
    List<Request> requests=new ArrayList<>();
    Api api=api(Collections.emptyMap(),c->{
      Request r=c.request();requests.add(r);
      if(!r.url().host().equals(HttpUrl.get(CONTROL).host()))
        return SessionFlowTest.response(r,503,Json.o("requestStatus",Json.o("statusCode",69,
            "statusDescription","SESSION_REQUEST_IN_QUEUE_ABANDONED")));
      return SessionFlowTest.response(r,200,queued(17,null));
    });
    SessionClient client=new SessionClient(api);
    SessionClient.Seat s=SessionClient.parse(queued(40,Json.a(HttpUrl.get(CONTROL).host())),ROOT);
    s=client.poll(s);
    assertEquals(17,s.queue);assertEquals(CONTROL,s.base);assertEquals(1,requests.size());
  }

  @Test public void eachAuthenticatedControlHintReplacesThePreviousQueueRoute() throws Exception {
    List<String> targets=new ArrayList<>();
    Api api=api(Collections.emptyMap(),c->{
      Request r=c.request();targets.add(r.url().host());assertEquals("GET",r.method());
      assertEquals(targets.size()==1?HttpUrl.get(CONTROL).host():HttpUrl.get(NEXT).host(),r.url().host());
      return SessionFlowTest.response(r,200,queued(30-targets.size()*5,
          targets.size()==1?Json.a(HttpUrl.get(NEXT).host()):null));
    });
    SessionClient client=new SessionClient(api);
    SessionClient.Seat s=SessionClient.parse(queued(35,HttpUrl.get(CONTROL).host()),ROOT);
    for(int i=0;i<3;i++)s=client.poll(s);
    assertEquals(Arrays.asList(HttpUrl.get(CONTROL).host(),HttpUrl.get(NEXT).host(),HttpUrl.get(NEXT).host()),targets);
    assertEquals(15,s.queue);
  }
}
