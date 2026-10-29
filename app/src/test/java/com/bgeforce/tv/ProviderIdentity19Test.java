package com.bgeforce.tv;

import static org.junit.Assert.*;
import java.util.*;
import okhttp3.*;
import org.json.*;
import org.junit.Test;
import okio.Buffer;

public class ProviderIdentity19Test {
  @Test public void resumeKeepsNvidiaWebRtcIdentityOnAControllerOutsideTheBrokerDomain()
      throws Exception {
    List<Request> calls=new ArrayList<>();
    String control="https://assigned.gfn.nvidia.com/";
    Api api=QueueHandoffRegressionTest.api(Collections.singletonMap("engine","native"),c->{
      Request r=c.request();calls.add(r);
      assertEquals("BROWSER",r.header("nv-client-type"));
      assertEquals("WEBRTC",r.header("nv-client-streamer"));
      if(r.method().equals("PUT")) {
        Buffer b=new Buffer();r.body().writeTo(b);
        JSONObject request=Json.obj(Json.parse(b.readUtf8()),"sessionRequestData");
        assertEquals("browser",Json.s(request,"clientPlatformName"));
        assertFalse(request.has("clientRequestMonitorSettings"));
      }
      return SessionFlowTest.response(r,200,Json.o("requestStatus",Json.o("statusCode",1),
          "session",Json.o("sessionId","resume-fixture","status",3,
              "sessionRequestData",Json.o("appId","12345"),
              "sessionControlInfo",Json.o("ip","assigned.gfn.nvidia.com","port",443),
              "connectionInfo",Json.a(Json.o("usage",14,"appLevelProtocol",5,
                  "resourcePath","wss://game.nvidiagrid.net:49100/nvst/")))));
    });
    SessionClient.Seat result=new SessionClient(api).resume(control,"resume-fixture","12345");
    assertTrue(result.ready());assertTrue(result.resumed);
    assertEquals(1,calls.stream().filter(r->r.method().equals("PUT")).count());
    assertEquals(0,calls.stream().filter(r->r.method().equals("POST") || r.method().equals("DELETE")).count());
  }
  @Test public void allianceAllocationStillUsesItsProviderContract() throws Exception {
    Api api=SessionFlowTest.api("https://prod.geforcenow.nvidiagrid.net/",
        Collections.singletonMap("engine","native"),c->{throw new AssertionError("No HTTP expected");});
    JSONObject request=Json.obj(new SessionClient(api).buildRequest(SessionFlowTest.game().selected(),
        "https://np-mia-03.cloudmatchbeta.nvidiagrid.net/"),"sessionRequestData");
    assertEquals("windows",Json.s(request,"clientPlatformName"));
    assertFalse(request.optBoolean("secureRTSPSupported"));
    assertEquals(1,request.optInt("streamerVersion"));
  }
}
