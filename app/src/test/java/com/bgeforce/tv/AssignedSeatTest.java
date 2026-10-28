package com.bgeforce.tv;
import static org.junit.Assert.*;
import java.util.*;
import java.io.*;
import org.json.*;
import org.junit.Test;

public class AssignedSeatTest {
  static final String NODE="https://game.nvidiagrid.net/";
  static JSONObject response(String endpoint) {
    return Json.o("requestStatus",Json.o("statusCode",1),"session",Json.o("sessionId","one","status",2,
        "queuePosition",0,"connectionInfo",Json.a(Json.o("usage",14,"resourcePath",endpoint))));
  }
  @Test public void queueReadyUsesAssignedNodesFreshEndpointBeforePlayerStarts() throws Exception {
    List<String> hosts=new ArrayList<>();
    Api api=SessionFlowTest.api(Api.DEFAULT_BASE,Collections.emptyMap(),chain->{
      hosts.add(chain.request().url().host());assertEquals("GET",chain.request().method());
      return SessionFlowTest.response(chain.request(),200,response(hosts.size()==1
          ? "wss://game.nvidiagrid.net/nvst/" : "wss://fresh.nvidiagrid.net/nvst/"));
    });
    SessionClient client=new SessionClient(api);SessionClient.Seat queued=new SessionClient.Seat();
    queued.id="one";queued.base=Api.DEFAULT_BASE;queued.status=1;
    SessionClient.Seat ready=client.poll(queued);
    assertEquals(Arrays.asList("prod.cloudmatchbeta.nvidiagrid.net","game.nvidiagrid.net"),hosts);
    assertEquals("wss://fresh.nvidiagrid.net/nvst/",ready.signaling);assertEquals(NODE,ready.base);
    assertTrue(ready.readPinned);client.poll(ready);assertEquals("game.nvidiagrid.net",hosts.get(2));
  }
  @Test public void transientAssignedReadDoesNotRepeatCreateOrClaim() throws Exception {
    List<String> verbs=new ArrayList<>();
    Api api=SessionFlowTest.api(Api.DEFAULT_BASE,Collections.emptyMap(),chain->{
      verbs.add(chain.request().method());return SessionFlowTest.response(chain.request(),
        verbs.size()==1?200:503,response("wss://game.nvidiagrid.net/nvst/"));
    });
    SessionClient.Seat queued=new SessionClient.Seat();queued.id="one";queued.status=1;queued.base=Api.DEFAULT_BASE;
    assertTrue(new SessionClient(api).poll(queued).ready());assertEquals(Arrays.asList("GET","GET"),verbs);
  }
  @Test public void assignedResponseCannotSwitchSessionIdentity() throws Exception {
    int[] n={0};Api api=SessionFlowTest.api(Api.DEFAULT_BASE,Collections.emptyMap(),chain->{
      JSONObject p=response("wss://game.nvidiagrid.net/nvst/");
      if(++n[0]==2)try{p.getJSONObject("session").put("sessionId","other");}catch(JSONException e){throw new IOException(e);}
      return SessionFlowTest.response(chain.request(),200,p);
    });
    SessionClient.Seat queued=new SessionClient.Seat();queued.id="one";queued.status=1;queued.base=Api.DEFAULT_BASE;
    try{new SessionClient(api).poll(queued);fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("identity"));}
  }
  @Test public void aPinnedPollDoesNotAlternateBackToTheRegionalControlRoute() {
    SessionClient.Seat s=new SessionClient.Seat();s.id="one";s.base=NODE;s.control=Api.DEFAULT_BASE;s.readPinned=true;
    assertEquals(NODE,SessionClient.readTargets(s).iterator().next());
  }
}
