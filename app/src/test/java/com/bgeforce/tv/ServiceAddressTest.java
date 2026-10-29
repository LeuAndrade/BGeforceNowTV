package com.bgeforce.tv;

import static org.junit.Assert.*;
import java.net.InetAddress;
import java.util.*;
import okhttp3.*;
import org.junit.Test;
import org.json.*;

public class ServiceAddressTest {
  @Test public void controlAcceptsStringOrArrayAndSkipsEmptyAndUntrustedItems() {
    String host="np-mia-04.cloudmatchbeta.nvidiagrid.net";
    assertEquals("https://"+host+"/",ServiceAddress.control(Json.o("sessionControlInfo",Json.o("ip",host,"port",443))));
    assertEquals("https://"+host+"/",ServiceAddress.control(Json.o("sessionControlInfo",Json.o("ip",Json.a(JSONObject.NULL,"", "evil.example",host),"port",443))));
  }
  @Test public void invalidControlDoesNotWeakenTheHttpsHostBoundary() {
    for(String ip:new String[]{"127.0.0.1","evil.example","trusted.nvidia.com@evil.example","safe.nvidia.com/path","https://safe.nvidia.com","safe.nvidia.com.evil.example"})
      assertEquals("",ServiceAddress.control(Json.o("sessionControlInfo",Json.o("ip",Json.a(ip),"port",443))));
    for(Object port:new Object[]{-1,65536,"bad",443.5})assertEquals("",ServiceAddress.control(Json.o("sessionControlInfo",Json.o("ip","safe.nvidia.com","port",port))));
  }
  @Test public void explicitTlsPortAndZeroDefaultArePreserved() {
    assertEquals("https://safe.nvidia.com:8443/",ServiceAddress.control(Json.o("sessionControlInfo",Json.o("ip",Json.a("safe.nvidia.com"),"port",8443))));
    assertEquals("https://safe.nvidia.com/",ServiceAddress.control(Json.o("sessionControlInfo",Json.o("ip","safe.nvidia.com","port",0))));
  }
  @Test public void mediaAndSignalingPinAcceptListAddressesWithoutChangingTls() throws Exception {
    JSONObject p=QueueHandoffRegressionTest.queued(0,Json.a("np-mia-04.cloudmatchbeta.nvidiagrid.net"));
    p.getJSONObject("session").put("status",2).put("connectionInfo",Json.a(
        Json.o("usage",14,"resourcePath","wss://game.nvidiagrid.net/nvst/","ip",Json.a("203.0.113.8"),"port",443),
        Json.o("usage",2,"ip",Json.a("198.51.100.8"),"port",47998)));
    SessionClient.Seat seat=SessionClient.parse(p,Api.DEFAULT_BASE);
    assertEquals("203.0.113.8",seat.ip);assertEquals("198.51.100.8",seat.mediaIp);assertTrue(seat.ready());
    OkHttpClient original=new OkHttpClient.Builder().dns(h->Collections.singletonList(InetAddress.getByName("203.0.113.9"))).build();
    OkHttpClient route=SessionRoute.signaling(original,seat);
    assertEquals("203.0.113.8",route.dns().lookup("game.nvidiagrid.net").get(0).getHostAddress());
    assertEquals("203.0.113.9",route.dns().lookup("other.nvidiagrid.net").get(0).getHostAddress());
    assertSame(original.sslSocketFactory(),route.sslSocketFactory());assertSame(original.hostnameVerifier(),route.hostnameVerifier());
  }
  @Test public void ipv6AndWhitespaceAreNormalizedWithoutJsonPunctuation() {
    assertEquals("2001:db8::1",ServiceAddress.first(Json.a(" 2001:db8::1 ")));
    assertEquals("node.nvidiagrid.net",ServiceAddress.first(Json.a("", " node.nvidiagrid.net ")));
    assertEquals("",ServiceAddress.first(Json.o("ip","node.nvidiagrid.net")));
  }
}
