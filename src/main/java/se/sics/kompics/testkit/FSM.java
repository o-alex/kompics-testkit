package se.sics.kompics.testkit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Stack;

import com.google.common.base.Function;
import com.google.common.base.Predicate;

import org.slf4j.Logger;

import se.sics.kompics.Component;
import se.sics.kompics.ComponentCore;
import se.sics.kompics.ComponentDefinition;
import se.sics.kompics.Fault;
import se.sics.kompics.JavaComponent;
import se.sics.kompics.Kompics;
import se.sics.kompics.PortType;
import se.sics.kompics.Port;
import se.sics.kompics.KompicsEvent;
import se.sics.kompics.Start;

class FSM<T extends ComponentDefinition> {
  static final Logger logger = Testkit.logger;

  private final T definitionUnderTest;
  private final EventQueue eventQueue;

  static final int ERROR_STATE = -1;
  private String ERROR_MESSAGE = "";
  private int FINAL_STATE;
  private boolean STARTED = false;

  private final ComponentCore proxyComponent;
  private Collection<Component> participants = new HashSet<Component>();

  private final Stack<Block> balancedBlock = new Stack<Block>();
  private Map<Integer, Block> blockStart = new HashMap<Integer, Block>();
  private Map<Integer, Block> blockEnd = new HashMap<Integer, Block>();

  private List<SingleEventSpec> expectUnordered = new ArrayList<SingleEventSpec>();
  private ExpectMapper expectMapper;
  private ExpectFuture expectFuture;

  private Map<Integer, Trigger> triggeredEvents = new HashMap<Integer, Trigger>();
  private Map<Integer, Predicate<T>> componentPredicates = new HashMap<Integer, Predicate<T>>();
  private Map<Integer, ExpectedFault> expectedFaults = new HashMap<Integer, ExpectedFault>();

  private ComparatorMap comparators = new ComparatorMap();
  private StateTable table = new StateTable();

  private Block currentBlock;
  private int currentState = 0;

  FSM(Proxy<T> proxy, T definitionUnderTest) {
    this.eventQueue = proxy.getEventQueue();
    this.proxyComponent =  proxy.getComponentCore();
    this.definitionUnderTest = definitionUnderTest;

    initializeFSM();
  }

  void addParticipant(Component c) {
    participants.add(c);
  }

  <P extends PortType> void addDisallowedEvent(
          KompicsEvent event, Port<P> port, Direction direction) {
    checkInHeaderMode();
    currentBlock.addDisallowedMessage(newEventSpec(event, port, direction));
  }

  <P extends  PortType> void addAllowedEvent(
          KompicsEvent event, Port<P> port, Direction direction) {
    checkInHeaderMode();
    currentBlock.addAllowedMessage(newEventSpec(event, port, direction));
  }

  <P extends  PortType> void addDroppedEvent(
          KompicsEvent event, Port<P> port, Direction direction) {
    checkInHeaderMode();
    currentBlock.addDroppedMessage(newEventSpec(event, port, direction));
  }

  <P extends PortType> void expectMessage(
          KompicsEvent event, Port<P> port, Direction direction) {
    EventSpec<? extends KompicsEvent> eventSpec = newEventSpec(event, port, direction);
    registerSpec(eventSpec);
  }

  <P extends PortType, E extends KompicsEvent> void expectMessage(
          Class<E> eventType, Predicate<E> predicate, Port<P> port, Direction direction) {
    PredicateSpec predicateSpec = new PredicateSpec(eventType, predicate, port, direction);
    registerSpec(predicateSpec);
  }

  <P extends PortType> void expectWithinBlock(
          KompicsEvent event, Port<P> port, Direction direction) {
    EventSpec<? extends KompicsEvent> eventSpec = newEventSpec(event, port, direction);
    checkInHeaderMode();
    currentBlock.expectWithinBlock(eventSpec);
  }

  <P extends PortType, E extends KompicsEvent> void expectWithinBlock(
          Class<E> eventType, Predicate<E> predicate, Port<P> port, Direction direction) {
    PredicateSpec predicateSpec = new PredicateSpec(eventType, predicate, port, direction);
    checkInHeaderMode();
    currentBlock.expectWithinBlock(predicateSpec);
  }

  void setUnorderedMode() {
    checkNewModeAllowed();
    currentBlock.mode = Block.MODE.UNORDERED;
    expectUnordered = new ArrayList<SingleEventSpec>();
  }

  void setExpectWithMapperMode() {
    checkNewModeAllowed();
    currentBlock.mode = Block.MODE.EXPECT_MAPPER;
    expectMapper = new ExpectMapper(proxyComponent);
  }

  <E extends KompicsEvent, R extends KompicsEvent> void setMapperForNext(
          int expectedEvents, Class<E> eventType, Function<E, R> mapper) {
    checkInExpectMapperMode();
    expectMapper.setMapperForNext(expectedEvents, eventType, mapper);
  }

  void addExpectWithMapper(
          Port<? extends PortType> listenPort, Port<? extends PortType> responsePort) {
    checkInExpectMapperMode();
    expectMapper.addExpectedEvent(listenPort, responsePort);
  }

  <E extends KompicsEvent, R extends KompicsEvent> void addExpectWithMapper(
          Class<E> eventType, Port<? extends PortType> listenPort,
          Port<? extends PortType> responsePort, Function<E, R> mapper) {
    checkInExpectMapperMode();
    expectMapper.addExpectedEvent(eventType, listenPort, responsePort, mapper);
  }

  void setExpectWithFutureMode() {
    checkNewModeAllowed();
    currentBlock.mode = Block.MODE.EXPECT_FUTURE;
    expectFuture = new ExpectFuture(proxyComponent);
  }

  public <E extends KompicsEvent, R extends KompicsEvent> void addExpectWithFuture(
          Class<E> eventType, Port<? extends PortType> listenPort, Future<E, R> future) {
    checkInExpectFutureMode();
    expectFuture.addExpectedEvent(eventType, listenPort, future);
  }

  public <E extends KompicsEvent, R extends KompicsEvent, P extends PortType> void addTrigger(
          Port<P> responsePort, Future<E, R> future) {
    checkInExpectFutureMode();
    expectFuture.addTrigger(responsePort, future);
  }

  void addTrigger(KompicsEvent event, Port<? extends PortType> port) {
    checkNewModeAllowed();
    triggeredEvents.put(currentState, new Trigger(event, port));
    gotoNextState();
  }

  void repeat(int times) {
    Block block = new Block(currentBlock, times, currentState);
    enterNewBlock(block);
  }

  void repeat(int times, BlockInit init) {
    Block block = new Block(currentBlock, times, currentState, init);
    enterNewBlock(block);
  }

  void body() {
    checkInHeaderMode();
    currentBlock.mode = Block.MODE.BODY;
  }

  void end() {
    switch (currentBlock.mode) {
      case UNORDERED:
        endUnorderedMode();
        break;
      case EXPECT_MAPPER:
        endExpectWithMapperMode();
        break;
      case EXPECT_FUTURE:
        endExpectWithFutureMode();
        break;
      default:
        endBlock();
    }
  }

  void setIterationInit(BlockInit iterationInit) {
    checkInHeaderMode();
    currentBlock.setIterationInit(iterationInit);
  }

  void addExpectedFault(
          Class<? extends Throwable> exceptionType, Fault.ResolveAction resolveAction) {
    checkNewModeAllowed();
    checkExpectedFaultHasMatchingClause();
    expectedFaults.put(currentState, new ExpectedFault(exceptionType, resolveAction));
    gotoNextState();
  }

  void addExpectedFault(
          Predicate<Throwable> exceptionPredicate, Fault.ResolveAction resolveAction) {
    checkNewModeAllowed();
    checkExpectedFaultHasMatchingClause();
    expectedFaults.put(currentState, new ExpectedFault(exceptionPredicate, resolveAction));
    gotoNextState();
  }

  void addAssertComponent(Predicate<T> assertPred) {
    componentPredicates.put(currentState, assertPred);
    gotoNextState();
  }

  <E extends KompicsEvent> void addComparator(
          Class<E> eventType, Comparator<E> comparator) {
    checkInInitialHeader();
    comparators.put(eventType, comparator);
  }

  <E extends KompicsEvent> void setDefaultAction(
          Class<E> eventType, Function<E, Action> function) {
    checkInInitialHeader();
    table.setDefaultAction(eventType, function);
  }

  public int getFinalState() {
    return STARTED? FINAL_STATE : currentState;
  }

  void checkInInitialHeader() {
    checkInHeaderMode();
    if (currentBlock == null || currentBlock.previousBlock != null) {
      throw new IllegalStateException("Operation only supported in initial header");
    }
  }

  int start() {
    if (!STARTED) {
      STARTED = true;
      addFinalState();
      checkBalancedRepeatBlocks();
      printTable(FINAL_STATE);
      run();
    }
    return currentState == FINAL_STATE + 1 ? FINAL_STATE : currentState;
  }

  <P extends  PortType, E extends KompicsEvent> EventSpec<? extends KompicsEvent> newEventSpec(
          KompicsEvent event, Port<P> port, Direction direction) {
    Comparator<E> c = (Comparator<E>) comparators.get(event.getClass());
    return EventSpec.create(c, (E) event, port, direction);
  }

  ExpectedFault getExpectedFault() {
    // // TODO: 3/21/17 use view with (expect, fault) pair
    int initialState = currentState;
    ExpectedFault expectedFault = expectedFaults.get(initialState);
    if (expectedFault == null) { // try next state
      expectedFault = expectedFaults.get(initialState + 1);
    }
    return expectedFault;
  }

  private void run() {
    runStartState();
    currentState = 0;
    while (currentState <= FINAL_STATE && currentState != ERROR_STATE) {
      if (expectingAnEvent()) {
        table.printExpectedEventAt(currentState);
        String expected = table.getExpectedSpecAt(currentState);

        EventSpec<? extends KompicsEvent> received = removeEventFromQueue();
        setComparatorForEvent(received);

        StateTable.Transition transition = table.lookup(currentState, received);

        if (!transitionedToErrorState(expected, received.toString(), transition)) {
          logger.debug("{}: Matched({}) with Transition({})", currentState, received, transition);
          currentState = transition.nextState;
        }
      }
    }
    runFinalState();
  }

  private void initializeFSM() {
    repeat(1);
  }

  // // TODO: 3/31/17 only allow in body, unordered mode
  private void registerSpec(SingleEventSpec spec) {
    if (currentBlock.mode == Block.MODE.UNORDERED) {
      expectUnordered.add(spec);
    } else {
      checkInBodyMode();
      table.registerExpectedEvent(currentState, spec, currentBlock);
      gotoNextState();
    }
  }

  private void endUnorderedMode() {
    currentBlock.mode = Block.MODE.BODY;
    if (expectUnordered.isEmpty()) {
      throw new IllegalStateException("No events were specified in unordered mode");
    }

    table.registerExpectedEvent(currentState, expectUnordered, currentBlock);
    gotoNextState();
  }

  private void endExpectWithMapperMode() {
    currentBlock.mode = Block.MODE.BODY;
    if (expectMapper.expected.isEmpty()) {
      throw new IllegalStateException("No events were specified");
    }

    table.registerExpectedEvent(currentState, expectMapper, currentBlock);
    gotoNextState();
    expectMapper = null;
  }

  // // TODO: 4/1/17 merget with endWithExpectMapperMode
  private void endExpectWithFutureMode() {
    currentBlock.mode = Block.MODE.BODY;
    if (expectFuture.expected.isEmpty()) {
      throw new IllegalStateException("No events were specified");
    }

    table.registerExpectedEvent(currentState, expectFuture, currentBlock);
    gotoNextState();
    expectFuture = null;
  }

  private void endBlock() {
    checkNewModeAllowed();
    if (balancedBlock.isEmpty()) {
      throw new IllegalStateException("matching repeat not found for end");
    }

    blockEnd.put(currentState, currentBlock);
    restorePreviousBlock();
    gotoNextState();
  }

  private void enterNewBlock(Block block) {
    checkNewModeAllowed();
    if (block.times <= 0) {
      throw new IllegalArgumentException("only positive value allowed for block");
    }

    currentBlock = block;
    balancedBlock.push(currentBlock);
    blockStart.put(currentState, block);
    gotoNextState();
  }

  private void restorePreviousBlock() {
    if (!balancedBlock.isEmpty()) {
      currentBlock = balancedBlock.pop().previousBlock;
    }
  }

  private void addFinalState() {
    FINAL_STATE = currentState;
    endBlock();
  }

  private void checkExpectedFaultHasMatchingClause() {
    int previousState = currentState - 1;
    if (!(table.isExpectState(previousState) || triggeredEvents.containsKey(previousState))) {
      throw new IllegalStateException("expected fault must be preceded by an expect or trigger");
    }
  }

  private boolean expectingAnEvent() {
    return !(isStartOfBlock() || isEndOfBlock() || triggeredAnEvent()
            || assertedComponent() || expectedFault());
  }

  private boolean transitionedToErrorState(
          String expected, String received, StateTable.Transition transition) {
    if (transition != null && transition.nextState != ERROR_STATE) {
      return false;
    }
    String errorMessage = String.format("Received %s message <%s> while expecting <%s>",
                            (transition == null? "unexpected" : "unwanted"), received, expected);
    gotoErrorState(errorMessage);
    return true;
  }

  private boolean assertedComponent() {
    Predicate<T> assertPred = componentPredicates.get(currentState);
    if (assertPred == null) {
      return false;
    }
    logger.debug("{}: Asserting Component", currentState);
    JavaComponent cut = (JavaComponent) definitionUnderTest.getComponentCore();

    // // TODO: 3/31/17 do not poll
    while (cut.workCount.get() > 0) {
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }
    boolean successful = assertPred.apply(definitionUnderTest);

    if (!successful) {
      gotoErrorState("Component assertion failed");
    } else {
      gotoNextState();
    }
    return true;
  }

  private boolean expectedFault() {
    ExpectedFault expectedFault = this.expectedFaults.get(currentState);
    if (expectedFault == null) {
      return false;
    }
    logger.info("Expect fault matching {}", expectedFault.strReprOfExpectedException());

    ExpectedFault.Result result = expectedFault.getResult();
    String assertMessage = result.message;

    if (result.succeeded) {
      logger.debug(assertMessage);
      gotoNextState();
    } else {
      gotoErrorState(assertMessage);
    }
    return true;
  }

  //@SuppressWarnings("unchecked")
  private void setComparatorForEvent(EventSpec eventSpec) {
    eventSpec.setComparator(comparators.get(eventSpec.getEvent().getClass()));
  }

  private boolean triggeredAnEvent() {
    Trigger trigger = triggeredEvents.get(currentState);
    if (trigger == null) {
      return false;
    }
    logger.debug("{}: triggeredAnEvent({})\t", currentState, trigger);
    trigger.doTrigger();
    gotoNextState();
    return true;
  }

  private boolean isStartOfBlock() {
    Block block = blockStart.get(currentState);
    if (block == null) {
      return false;
    } else {
      block.initialize();
      logger.debug("{}: repeat({})\t", currentState, block.getCurrentCount());
      gotoNextState();
      return true;
    }
  }

  private boolean isEndOfBlock() {
    Block block = blockEnd.get(currentState);
    if (block == null) { // not end of block
      return false;
    }

    logger.debug("{}: end({})\t", currentState, block.times);

    if (block.hasPendingEvents()) {

      while (block.hasPendingEvents()) {
        logger.debug("Awaiting pending events in empty block: {}", block.pendingEventsToString());
        EventSpec<? extends KompicsEvent> receivedSpec = removeEventFromQueue();
        logger.debug("Received ({})", receivedSpec);

        // match event
        StateTable.Transition transition = table.lookupWithBlock(currentState, receivedSpec, block);

        if (transitionedToErrorState(block.status(), receivedSpec.toString(), transition)) {
          break;
        }

        logger.debug("{}: Matched({}) with Transition({})", currentState, receivedSpec, transition);

        currentState = transition.nextState;
      }
    } else {
      block.iterationComplete();

      if (block.hasMoreIterations()) {
        currentState = block.indexOfFirstState();
      } else {
        gotoNextState();
      }
    }

    return true;
  }

  private void runStartState() {
    logger.debug("Sending Start to {} participant component(s)", participants.size());
    for (Component child : participants) {
      child.getControl().doTrigger(Start.event, 0, proxyComponent);
    }
  }

  private void runFinalState() {
    logger.info("Done!({})", currentState == ERROR_STATE? "FAILURE -> " + ERROR_MESSAGE : "PASS");
    Kompics.shutdown();
  }

  private void checkBalancedRepeatBlocks() {
    if (!balancedBlock.isEmpty()) {
      throw new IllegalStateException("unmatched end for block");
    }
  }

  private void checkNewModeAllowed() {
    if (currentBlock == null) {
      return;
    }

    checkInBodyMode();

    switch (currentBlock.mode) {
      case UNORDERED:
      case EXPECT_MAPPER:
      case EXPECT_FUTURE:
        throw new IllegalStateException(String.format("method not allowed in %s mode", currentBlock.mode));
    }
  }

  private void checkMode(Block.MODE mode) {
    if (currentBlock != null && currentBlock.mode != mode) {
      throw new IllegalStateException(String.format("Expected mode [%s], Actual mode [%s]",
                      mode, currentBlock.mode));
    }
  }

  private void checkInBodyMode() {
    checkMode(Block.MODE.BODY);
  }

  private void checkInHeaderMode() {
    checkMode(Block.MODE.HEADER);
  }

  private void checkInExpectMapperMode() {
    checkMode(Block.MODE.EXPECT_MAPPER);
  }

  private void checkInExpectFutureMode() {
    checkMode(Block.MODE.EXPECT_FUTURE);
  }

  private void gotoNextState() {
    currentState++;
  }
  
  private void gotoErrorState(String errorMessage) {
    currentState = ERROR_STATE;
    ERROR_MESSAGE = errorMessage;
  }

  private EventSpec<? extends KompicsEvent> removeEventFromQueue() {
    return eventQueue.poll();
  }

  void printTable(int final_state) {
    Testkit.logger.info("State\t\t\t\tTransitions");
    for (int i = 0; i <= final_state; i++) {
      StateTable.State state = table.states.get(i);
      if (state != null) {
        Testkit.logger.info("{}", i);
        for (StateTable.Transition t : state.transitions.values()) {
          Testkit.logger.info("\t\ton {}", t);
        }
        Testkit.logger.info("\t\ton {}", state);
      } else if (blockStart.containsKey(i)) {
        logger.info("{}\t\t{}",i, blockStart.get(i));
      } else if (blockEnd.containsKey(i)) {
        logger.info("{}\t\tend{}",i, blockEnd.get(i));
      } else if (triggeredEvents.containsKey(i)) {
        logger.info("{}\t\ttrigger({})", i, triggeredEvents.get(i));
      }
    }

  }
  // // TODO: 2/17/17 switch to eventSpec?
  private class Trigger {
    private final KompicsEvent event;
    private final Port<? extends PortType> port;

    Trigger(KompicsEvent event, Port<? extends PortType> port) {
      this.event = event;
      this.port = port;
    }

    void doTrigger() {
      port.doTrigger(event, 0, port.getOwner());
    }

    public String toString() {
      return event.toString();
    }
  }

  private class ComparatorMap {
    Map<Class<? extends KompicsEvent>, Comparator<? extends KompicsEvent>> comparators =
            new HashMap<Class<? extends KompicsEvent>, Comparator<? extends KompicsEvent>>();

    @SuppressWarnings("unchecked")
    public <E extends KompicsEvent> Comparator<E> get(Class<E> eventType) {
      return (Comparator<E>) comparators.get(eventType);
    }

    public <E extends KompicsEvent> void put(Class<E> eventType, Comparator<E> comparator) {
      comparators.put(eventType, comparator);
    }
  }
}