package se.sics.kompics.testkit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.sics.kompics.ChannelCore;
import se.sics.kompics.KompicsEvent;
import se.sics.kompics.Port;
import se.sics.kompics.PortCore;
import se.sics.kompics.PortType;
import se.sics.kompics.Request;
import se.sics.kompics.RequestPathElement;
import se.sics.kompics.Response;
import se.sics.kompics.testkit.fsm.EventSpec;
import java.util.List;

class OutBoundHandler extends ProxyHandler {
  private static final Logger logger = LoggerFactory.getLogger(OutBoundHandler.class);
  
  private Port<? extends PortType> sourcePort;
  private List<? extends Port<? extends PortType>> destPorts;

  OutBoundHandler(
          Proxy proxy, PortStructure portStruct,
          Class<? extends KompicsEvent> eventType) {

    super(proxy, portStruct, eventType);
    this.sourcePort = portStruct.getOutboundPort();
    this.destPorts = portStruct.getConnectedPorts();
  }

  @Override
  public void handle(KompicsEvent event) {
    logger.trace("received event: {}, connected to {} ports", event, destPorts.size());

    if (event instanceof Request) {
      Request request = (Request) event;
      request.pushPathElement(proxy.getComponentCore());
    }

    EventSpec eventSpec = new EventSpec(event, sourcePort, Direction.OUTGOING);
    eventSpec.setHandler(this);
    eventQueue.offer(eventSpec);
  }

  @Override
  public void doHandle(KompicsEvent event) {
    if (event instanceof Response) {
      deliverToSingleChannel((Response) event);
    } else {
      deliverToAllConnectedPorts(event);
    }
  }

  private void deliverToAllConnectedPorts(KompicsEvent event) {
    for (Port<? extends PortType> port : destPorts) {
      port.doTrigger(event, 0, portStruct.getChannel(port));
    }
  }

  private void deliverToSingleChannel(Response response) {
    RequestPathElement pe = response.getTopPathElement();
    if (pe != null && pe.isChannel()) {
      ChannelCore<?> caller = pe.getChannel();
      // // TODO: 2/21/17 isPositivePort does not belong in Kompics core
      if (((PortCore)sourcePort).isPositivePort()) {
        caller.forwardToNegative(response, 0);
      } else {
        caller.forwardToPositive(response, 0);
      }
    }
  }
}
