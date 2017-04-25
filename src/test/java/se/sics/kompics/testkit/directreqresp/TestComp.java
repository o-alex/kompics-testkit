/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package se.sics.kompics.testkit.directreqresp;

import se.sics.kompics.ComponentDefinition;
import se.sics.kompics.Handler;
import se.sics.kompics.Negative;

/**
 * @author Alex Ormenisan <aaor@kth.se>
 */
public class TestComp extends ComponentDefinition {

  Negative<TestPort> port = provides(TestPort.class);

  public TestComp() {
    subscribe(handleDirectRequest, port);
  }

  Handler handleDirectRequest = new Handler<TestMsg.Request>() {
    @Override
    public void handle(TestMsg.Request req) {
      answer(req, req.success());
    }
  };
}
