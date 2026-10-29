package com.bgeforce.tv;
import static org.junit.Assert.*;
import org.junit.Test;
public class SessionTraceTest {
  @Test public void boundedTraceDoesNotRetainSecretsUrlsIdsOrArbitraryMessages() {
    long[] t={0};SessionTrace trace=new SessionTrace(()->t[0]);
    for(int i=0;i<20;i++) {
      t[0]+=1000000;
      trace.record("GET Bearer forbidden-secret","https://password@np-mia-04.cloudmatchbeta.nvidiagrid.net/v2/session/private-id?id_token=private-token",1,1,100-i,
          "https://control.nvidiagrid.net/?refresh_token=secret");
    }
    String out=trace.details()+trace.summary();
    for(String secret:new String[]{"password","private-id","private-token","secret","Bearer","refresh_token","id_token","/v2/session"})assertFalse(secret,out.contains(secret));
    assertEquals(8,trace.details().split("\\n").length);
    assertTrue(out.contains("np-mia-04.cloudmatchbeta.nvidiagrid.net"));assertTrue(out.contains("leituras=20"));
  }
  @Test public void traceTimesAreActualMonotonicOffsetsAndFailureCodeIsBounded() {
    long[] t={1000000};SessionTrace trace=new SessionTrace(()->t[0]);t[0]=3000000;
    trace.record("GET","https://np-mia-04.cloudmatchbeta.nvidiagrid.net/",69,-1,-1,"");
    assertTrue(trace.details().startsWith("+2ms GET"));assertTrue(trace.details().contains("api=69"));
  }
}
