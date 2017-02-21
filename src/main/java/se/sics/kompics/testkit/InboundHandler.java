package se.sics.kompics.testkit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.sics.kompics.*;
import se.sics.kompics.testkit.fsm.EventSpec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

class InboundHandler extends ProxyHandler {
  private static final Logger logger = LoggerFactory.getLogger(InboundHandler.class);

  // // TODO: 2/20/17 remove sourcePorts if not useful
  private List<Port<? extends PortType>> sourcePorts;
  private Port<? extends PortType> destPort;

  InboundHandler(
          Proxy proxy, PortStructure portStruct,
          Class<? extends KompicsEvent> type, Collection<? extends Port<? extends PortType>> sourcePorts,
          Port<? extends PortType> destPort) {
    super(proxy, portStruct, type);
    this.destPort = destPort;
    this.sourcePorts = new ArrayList<>(sourcePorts);
  }

  @Override
  public void handle(KompicsEvent event) {
    //logger.warn("received event: {}", event);
    // // TODO: 2/21/17 uncomment assertion
    if (event instanceof Response) {
      Response response = (Response) event;
      assert response.getTopPathElementFirst().getComponent() == destPort.getPair().getOwner();
    }

    if (event instanceof Request) {
      Request request = (Request) event;
      request.pushPathElement(proxy.getComponentCore());
    }
    EventSpec eventSpec = new EventSpec(event, destPort, TestKit.Direction.INCOMING);
    eventSpec.setHandler(this);
    eventQueue.offer(eventSpec);
  }

  @Override
  public void doHandle(KompicsEvent event) {
    destPort.doTrigger(event, 0, proxy.getComponentCore());
  }
}
