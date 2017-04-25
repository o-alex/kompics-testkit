/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package se.sics.kompics.testkit.directreqresp;

import se.sics.kompics.PortType;

/**
 * @author Alex Ormenisan <aaor@kth.se>
 */
public class TestPort extends PortType {
  {
    request(TestMsg.Request.class);
    indication(TestMsg.Response.class);
  }
}
