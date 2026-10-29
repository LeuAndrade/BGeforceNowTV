package com.bgeforce.tv;

import static org.junit.Assert.*;
import java.util.*;
import okhttp3.*;
import org.json.*;
import org.junit.Test;
import okio.Buffer;

/** Real protocol-5 descriptor shape from the NVIDIA client, without account/session data. */
public class SecureChannel19RegressionTest {
  private SessionClient.Seat parse(JSONArray list) throws Exception {
    return SessionClient.parse(Json.o("session",Json.o("sessionId","fixture","status",2,
        "connectionInfo",list)),Api.DEFAULT_BASE);
  }
  @Test public void secureWebSocketProtocolFiveKeepsItsAdvertisedPort() throws Exception {
    SessionClient.Seat s=parse(Json.a(Json.o("usage",14,"appLevelProtocol",5,
        "ip","game.nvidiagrid.net","port",49100,"resourcePath","/nvst/")));
    assertEquals("wss://game.nvidiagrid.net:49100/nvst/",s.signaling);
  }
  @Test public void secureSignalingPortIsNeverInventedAsUdpMedia() throws Exception {
    SessionClient.Seat s=parse(Json.a(Json.o("usage",14,"appLevelProtocol",5,
        "ip","203.0.113.8","port",49100,"resourcePath","/nvst/")));
    assertEquals("",s.mediaIp);assertEquals(0,s.mediaPort);
  }
  @Test public void explicitSecureDescriptorOutranksALegacyRelativeCandidate() throws Exception {
    SessionClient.Seat s=parse(Json.a(
        Json.o("usage",14,"appLevelProtocol",4,"ip","legacy.nvidiagrid.net","port",8443),
        Json.o("usage",14,"appLevelProtocol",5,"ip","secure.nvidiagrid.net","port",49100)));
    assertEquals("wss://secure.nvidiagrid.net:49100/nvst/",s.signaling);
  }
  @Test public void nativeRendererRequestsWebRtcRatherThanClassicAllocation() throws Exception {
    List<Request> calls=new ArrayList<>();
    Api api=QueueHandoffRegressionTest.api(Collections.singletonMap("engine","native"),c->{
      Request r=c.request();calls.add(r);
      if(r.url().encodedPath().equals("/v2/serverInfo"))
        return SessionFlowTest.response(r,200,Json.o("metaData",Json.a()));
      assertEquals("BROWSER",r.header("nv-client-type"));
      assertEquals("WEBRTC",r.header("nv-client-streamer"));
      Buffer b=new Buffer();r.body().writeTo(b);
      JSONObject data=Json.obj(Json.parse(b.readUtf8()),"sessionRequestData");
      assertEquals("browser",Json.s(data,"clientPlatformName"));
      assertFalse(data.optBoolean("secureRTSPSupported"));
      assertEquals(1280,Json.arr(data,"clientRequestMonitorSettings").optJSONObject(0).optInt("widthInPixels"));
      return SessionFlowTest.response(r,200,QueueHandoffRegressionTest.queued(10,null));
    });
    SessionClient client=new SessionClient(api);Game g=SessionFlowTest.game();
    client.create(g,g.selected());assertEquals(2,calls.size());
    assertEquals("native",api.settings.engine()); // The local MediaCodec player is unchanged.
  }
}
