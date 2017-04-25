/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package se.sics.kompics.testkit.directreqresp;

import se.sics.kompics.Direct;

/**
 * @author Alex Ormenisan <aaor@kth.se>
 */
public class TestMsg {
  public static class Request extends Direct.Request<Response> {
    public Response success() {
      return new Response();
    }
  }
  
  public static class Response implements Direct.Response {
  }
}
