package se.sics.kompics.testkit.fsm;

import com.google.common.base.Function;
import se.sics.kompics.KompicsEvent;
import se.sics.kompics.testkit.Action;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

class StateTable {

  private final Comparator<Class<? extends KompicsEvent>> eventComparator = new Comparator<Class<? extends KompicsEvent>>() {
    @Override
    public int compare(Class<? extends KompicsEvent> e1, Class<? extends KompicsEvent> e2) {
      if (e1 == e2) {
        return 0;
      } else if (e1.isAssignableFrom(e2)) {
        return 1;
      } else {
        return -1;
      }
    }
  };

  private final Map<Class<? extends KompicsEvent>, Function<? extends KompicsEvent, Action>> defaultActions =
          new TreeMap<Class<? extends KompicsEvent>, Function<? extends KompicsEvent, Action>>(eventComparator);

  private Map<Integer, State> states = new HashMap<Integer, State>();

  void registerExpectedEvent(int state, EventSpec<? extends KompicsEvent> eventSpec, Environment env) {
    states.put(state, new State(state, eventSpec, env));

  }

  void registerExpectedEvent(
          int state, PredicateSpec predSpec, Environment env) {
    states.put(state, new State(state, predSpec, env));
  }

  void registerExpectedEvent(int state, List<Spec> expectUnordered, Environment env) {
    states.put(state, new State(state, expectUnordered, env));
  }

  <E extends KompicsEvent> void setDefaultAction(
          Class<E> eventType, Function<E, Action> predicate) {
    defaultActions.put(eventType, predicate);
  }

  void printExpectedEventAt(int state) {
    FSM.logger.debug("{}: Expect\t{}", state, getExpectedSpecAt(state));
  }

  String getExpectedSpecAt(int state) {
    assert states.get(state) != null;
    return states.get(state).toString();
  }

  Transition lookup(int state, EventSpec<? extends KompicsEvent> receivedSpec) {
    return states.get(state).onEvent(receivedSpec);
  }

  void printTable(int final_state) {
    for (int i = 0; i <= final_state; i++) {
      State state = states.get(i);
      if (state != null) {
        System.out.println(i);
        for (Transition t : state.transitions.values()) {
          System.out.println("\t\t" + t);
        }
        System.out.println("\t\t" + state);
      }
    }
  }

  static class Transition {
    final EventSpec eventSpec;
    final Action action;
    final int nextState;

    Transition(EventSpec eventSpec, Action action, int nextState) {
      this.eventSpec = eventSpec;
      this.action = action;
      this.nextState = nextState;
    }

    Transition(EventSpec eventSpec, int nextState) {
      this(eventSpec, null, nextState);
    }

    boolean handleEvent() {
      return action == Action.HANDLE;
    }

    // transitions are equal if they are for the same event
    public boolean equals(Object obj) {
      if (!(obj instanceof Transition)) {
        return false;
      }
      Transition other = (Transition) obj;
      return this.eventSpec.equals(other.eventSpec);
    }

    public int hashCode() {
      return eventSpec.hashCode();
    }

    public String toString() {
      return "( " + eventSpec + " ) " + (handleEvent()? "handle " : "drop ") + nextState;
    }
  }

  private class State {
    private final int state;
    private PredicateSpec predicateSpec;
    private EventSpec<? extends KompicsEvent> eventSpec;
    private List<Spec> expectUnordered;
    private List<EventSpec<? extends KompicsEvent>> matchedUnordered;
    private final Map<EventSpec<? extends KompicsEvent>, Transition> transitions =
            new HashMap<EventSpec<? extends KompicsEvent>, Transition>();

    private State(int state, Environment env) {
      this.state = state;
      addTransitions(env);
    }

    State(int state, EventSpec<? extends KompicsEvent> eventSpec, Environment env) {
      this(state, env);
      this.eventSpec = eventSpec;
    }

    State(int state, PredicateSpec predicateSpec, Environment env) {
      this(state, env);
      this.predicateSpec = predicateSpec;
    }

    State(int state, List<Spec> expectUnordered, Environment env) {
      this(state, env);
      this.expectUnordered = expectUnordered;
      matchedUnordered = new ArrayList<EventSpec<? extends KompicsEvent>>(expectUnordered.size());
    }

    void addTransitions(Environment env) {
      for (EventSpec<? extends KompicsEvent> e : env.getDisallowedEvents()) {
        addTransition(e, Action.FAIL, FSM.ERROR_STATE);
      }
      for (EventSpec<? extends KompicsEvent> e : env.getAllowedEvents()) {
        addTransition(e, Action.HANDLE, state);
      }
      for (EventSpec<? extends KompicsEvent> e : env.getDroppedEvents()) {
        addTransition(e, Action.DROP, state);
      }
    }

    private void addTransition(EventSpec<? extends KompicsEvent> onEvent, Action action, int nextState) {
      Transition transition = new Transition(onEvent, action, nextState);
      transitions.put(onEvent, transition);
    }

    Transition onEvent(EventSpec<? extends KompicsEvent> receivedSpec) {
      // single event or predicate transition
      if ((eventSpec != null && eventSpec.match(receivedSpec)) ||
          (predicateSpec != null && predicateSpec.match(receivedSpec))) {
        receivedSpec.handle();
        int nextState = state + 1;
        return new Transition(receivedSpec, nextState);
      }

      // unordered events
      if (expectUnordered != null && expectUnordered.contains(receivedSpec)) {
        int index = expectUnordered.indexOf(receivedSpec);
        matchedUnordered.add(receivedSpec);
        expectUnordered.remove(index);

        if (!expectUnordered.isEmpty()) {
          return new Transition(receivedSpec, state);
        } else {
          for (EventSpec<? extends KompicsEvent> e : matchedUnordered) {
            e.handle();
          }
          // do not reuse state
          matchedUnordered = null;
          expectUnordered = null;
          int nextState = state + 1;
          return new Transition(receivedSpec, nextState);
        }
      }

      // other transitions
      Transition transition = transitions.get(receivedSpec);
      // default transition
      if (transition == null) {
        transition = defaultLookup(state, receivedSpec);
      }

      if (transition != null && transition.handleEvent()) {
        receivedSpec.handle();
      }

      return transition;
    }

    private Transition defaultLookup(int state, EventSpec eventSpec) {
      KompicsEvent event = eventSpec.getEvent();
      Class<? extends KompicsEvent> eventType = event.getClass();

      for (Class<? extends KompicsEvent> registeredType : defaultActions.keySet()) {
        if (registeredType.isAssignableFrom(eventType)) {
          Action action = actionFor(event, registeredType);
          switch (action) {
            case HANDLE:
              return new Transition(eventSpec, Action.HANDLE, state);
            case DROP:
              return new Transition(eventSpec, Action.DROP, state);
            default:
              return new Transition(eventSpec, Action.FAIL, FSM.ERROR_STATE);
          }
        }
      }
      return null;
    }

    private <E extends KompicsEvent> Action actionFor(
            KompicsEvent event, Class<? extends KompicsEvent> registeredType) {
      E ev = (E) event;
      Function<E, Action> function = (Function<E, Action>) defaultActions.get(registeredType);
      Action action = function.apply(ev);
      if (action == null) {
        throw new NullPointerException(String.format("(default handler for %s returned null for event '%s')",
                registeredType, event));
      }
      return action;
    }

    public String toString() {
      StringBuilder sb = new StringBuilder();
      if (predicateSpec != null) {
        sb.append(predicateSpec.toString());
      } else if (eventSpec != null) {
        sb.append(eventSpec.toString());
      } else {
        sb.append("Unordered<Seen(");
        for (EventSpec e : matchedUnordered) {
          sb.append(" ").append(e);
        }
        sb.append(")Expecting(");
        for (Spec e : expectUnordered) {
          sb.append(" ").append(e);
        }
        sb.append(")");
      }
      return sb.append(" handle ").append(state + 1).toString();
    }
  }

}
