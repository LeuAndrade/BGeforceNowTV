package com.bgeforce.tv;
import static org.junit.Assert.*;
import org.junit.Test;
import org.json.*;

public class QueueTransitionTest {
  private SessionClient.Seat seat(String id, int n) {
    SessionClient.Seat s = new SessionClient.Seat(); s.id=id; s.status=1; s.queue=n; return s;
  }
  @Test public void currentPositionWinsOverSetupSnapshot() {
    assertEquals(12,SessionClient.queue(Json.o("queuePosition",12,"seatSetupInfo",Json.o("queuePosition",100))));
  }
  @Test public void liveZeroIsNotMistakenForMissingProgress() {
    assertEquals(0,SessionClient.queue(Json.o("queuePosition",0,"seatSetupInfo",Json.o("queuePosition",100))));
  }
  @Test public void nullAndInvalidValuesFallThroughToActualProgress() {
    assertEquals(8,SessionClient.queue(Json.o("queuePosition",JSONObject.NULL,"seatSetupInfo",Json.o("queuePosition",-1),"sessionProgress",Json.o("queuePosition","8"))));
    assertEquals(-1,SessionClient.queue(Json.o("queuePosition",3.5)));
    assertEquals(-1,SessionClient.queue(Json.o("queuePosition",true)));
  }
  @Test public void realIncreasesAreShownImmediately() {
    QueueProgress q=new QueueProgress();assertEquals(3,q.update(seat("one",3)));
    assertEquals(9,q.update(seat("one",9)));assertEquals(7,q.update(seat("one",7)));
  }
  @Test public void absentPositionIsNotInvented() {
    QueueProgress q=new QueueProgress();q.update(seat("one",3));assertEquals(-1,q.update(seat("one",-1)));
  }
  @Test public void anotherSessionResetsTheFilter() {
    QueueProgress q=new QueueProgress();q.update(seat("one",0));
    assertEquals(90,q.update(seat("two",90)));
  }
  @Test public void zeroDoesNotMeanReadyWhileTheSeatIsStillQueuing() throws Exception {
    JSONObject raw=Json.o("sessionId","one","status",1,"queuePosition",0,
        "seatSetupInfo",Json.o("seatSetupStep",1),"connectionInfo",Json.a(Json.o("usage",14,"resourcePath","wss://game.nvidiagrid.net/nvst/")));
    SessionClient.Seat s=SessionClient.parse(Json.o("session",raw),Api.DEFAULT_BASE);
    assertFalse(s.ready());raw.put("status",2);
    assertTrue(SessionClient.parse(Json.o("session",raw),Api.DEFAULT_BASE).ready());
  }
  @Test public void assignedSeatStopsShowingOldQueueNumber() {
    QueueProgress q=new QueueProgress();q.update(seat("one",15));
    SessionClient.Seat s=seat("one",100);s.status=2;s.signaling="wss://game.nvidiagrid.net/nvst/";
    assertEquals(-1,q.update(s));
  }
}
