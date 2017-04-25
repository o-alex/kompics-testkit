/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package se.sics.kompics.testkit.directreqresp;

import static org.junit.Assert.assertEquals;
import org.junit.Test;
import se.sics.kompics.Component;
import se.sics.kompics.Init;
import se.sics.kompics.Port;
import se.sics.kompics.testkit.Direction;
import se.sics.kompics.testkit.TestContext;
import se.sics.kompics.testkit.Testkit;

/**
 * @author Alex Ormenisan <aaor@kth.se>
 */
public class DirectRequestResponseTest {
  private TestContext<TestComp> tc = Testkit.newTestContext(TestComp.class, Init.NONE);
  private Component comp = tc.getComponentUnderTest();
  private Direction incoming = Direction.INCOMING;
  private Direction outgoing = Direction.OUTGOING;
  
  @Test
  public void test() {
    Port<TestPort> port = comp.getPositive(TestPort.class);
    tc.body()
      .trigger(new TestMsg.Request(), port)
      ;
    assertEquals(tc.check(), tc.getFinalState());
  }
}
