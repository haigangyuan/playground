package coolbeevip.playgroud.statemachine.saga;

import static org.junit.Assert.assertEquals;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Terminated;
import akka.persistence.fsm.PersistentFSM;
import akka.persistence.fsm.PersistentFSM.CurrentState;
import akka.testkit.javadsl.TestKit;
import coolbeevip.playgroud.statemachine.saga.actors.SagaActor;
import coolbeevip.playgroud.statemachine.saga.actors.SagaActorState;
import coolbeevip.playgroud.statemachine.saga.actors.TxState;
import coolbeevip.playgroud.statemachine.saga.actors.event.SagaAbortedEvent;
import coolbeevip.playgroud.statemachine.saga.actors.event.SagaEndedEvent;
import coolbeevip.playgroud.statemachine.saga.actors.event.SagaStartedEvent;
import coolbeevip.playgroud.statemachine.saga.actors.event.SagaTimeoutEvent;
import coolbeevip.playgroud.statemachine.saga.actors.event.TxAbortedEvent;
import coolbeevip.playgroud.statemachine.saga.actors.event.TxComponsitedEvent;
import coolbeevip.playgroud.statemachine.saga.actors.event.TxEndedEvent;
import coolbeevip.playgroud.statemachine.saga.actors.event.TxStartedEvent;
import coolbeevip.playgroud.statemachine.saga.actors.model.SagaData;
import java.time.Duration;
import java.util.UUID;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

@Slf4j
public class SagaActorTest {

  static ActorSystem system;

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("SagaActorTest");
  }

  @AfterClass
  public static void tearDown() {
    TestKit.shutdownActorSystem(system);
    system = null;
  }

  public String genPersistenceId() {
    return UUID.randomUUID().toString();
  }

  /**
   * 1. SagaStartedEvent
   * 2. TxStartedEvent
   * 3. TxEndedEvent
   * 4. TxStartedEvent
   * 5. TxEndedEvent
   * 6. SagaEndedEvent
   */
  @Test
  @SneakyThrows
  public void successfulTest() {
    new TestKit(system) {{
      final String globalTxId = UUID.randomUUID().toString();
      final String localTxId_1 = UUID.randomUUID().toString();
      final String localTxId_2 = UUID.randomUUID().toString();

      ActorRef saga = system.actorOf(SagaActor.props(genPersistenceId()), "saga");
      watch(saga);
      saga.tell(new PersistentFSM.SubscribeTransitionCallBack(getRef()), getRef());
      saga.tell(SagaStartedEvent.builder().globalTxId(globalTxId).build(), getRef());
      saga.tell(TxStartedEvent.builder().globalTxId(globalTxId).parentTxId(globalTxId).localTxId(localTxId_1).build(), getRef());
      saga.tell(TxEndedEvent.builder().globalTxId(globalTxId).parentTxId(globalTxId).localTxId(localTxId_1).build(), getRef());
      saga.tell(TxStartedEvent.builder().globalTxId(globalTxId).parentTxId(globalTxId).localTxId(localTxId_2).build(), getRef());
      saga.tell(TxEndedEvent.builder().globalTxId(globalTxId).parentTxId(globalTxId).localTxId(localTxId_2).build(), getRef());
      saga.tell(SagaEndedEvent.builder().globalTxId(globalTxId).build(), getRef());

      //expect
      CurrentState currentState = expectMsgClass(PersistentFSM.CurrentState.class);
      assertEquals(SagaActorState.IDEL, currentState.state());

      PersistentFSM.Transition transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.IDEL, SagaActorState.READY);

      transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.READY, SagaActorState.PARTIALLY_ACTIVE);

      transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.PARTIALLY_ACTIVE, SagaActorState.PARTIALLY_COMMITTED);

      transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.PARTIALLY_COMMITTED, SagaActorState.PARTIALLY_ACTIVE);

      transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.PARTIALLY_ACTIVE, SagaActorState.PARTIALLY_COMMITTED);

      SagaData sagaData = expectMsgClass(SagaData.class);
      assertEquals(sagaData.getGlobalTxId(), globalTxId);
      assertEquals(sagaData.getTxEntityMap().size(), 2);
      sagaData.getTxEntityMap().forEach((k, v) -> {
        assertEquals(v.getState(), TxState.COMMITTED);
      });

      transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.PARTIALLY_COMMITTED, SagaActorState.COMMITTED);

      Terminated terminated = expectMsgClass(Terminated.class);
      assertEquals(terminated.getActor(), saga);

      system.stop(saga);
    }};
  }

  /**
   * 1. SagaStartedEvent
   * 2. TxStartedEvent
   * 3. TxEndedEvent
   * 4. TxStartedEvent
   * 5. TxAbortedEvent
   * 6. TxComponsitedEvent
   * 7. SagaAbortedEvent
   */
  @Test
  @SneakyThrows
  public void secondTxAbortedEventTest() {
    new TestKit(system) {{
      final String globalTxId = UUID.randomUUID().toString();
      final String localTxId_1 = UUID.randomUUID().toString();
      final String localTxId_2 = UUID.randomUUID().toString();

      ActorRef saga = system.actorOf(SagaActor.props(genPersistenceId()), "saga");
      watch(saga);
      saga.tell(new PersistentFSM.SubscribeTransitionCallBack(getRef()), getRef());

      saga.tell(SagaStartedEvent.builder().globalTxId(globalTxId).build(), getRef());
      saga.tell(TxStartedEvent.builder().globalTxId(globalTxId).parentTxId(globalTxId).localTxId(localTxId_1).build(), getRef());
      saga.tell(TxEndedEvent.builder().globalTxId(globalTxId).parentTxId(globalTxId).localTxId(localTxId_1).build(), getRef());
      saga.tell(TxStartedEvent.builder().globalTxId(globalTxId).parentTxId(globalTxId).localTxId(localTxId_2).build(), getRef());
      saga.tell(TxAbortedEvent.builder().globalTxId(globalTxId).parentTxId(globalTxId).localTxId(localTxId_2).build(), getRef());
      saga.tell(TxComponsitedEvent.builder().globalTxId(globalTxId).parentTxId(globalTxId).localTxId(localTxId_1).build(), getRef());
      saga.tell(SagaAbortedEvent.builder().globalTxId(globalTxId).build(), getRef());

      //expect
      CurrentState currentState = expectMsgClass(PersistentFSM.CurrentState.class);
      assertEquals(SagaActorState.IDEL, currentState.state());

      PersistentFSM.Transition transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.IDEL, SagaActorState.READY);

      transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.READY, SagaActorState.PARTIALLY_ACTIVE);

      transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.PARTIALLY_ACTIVE, SagaActorState.PARTIALLY_COMMITTED);

      transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.PARTIALLY_COMMITTED, SagaActorState.PARTIALLY_ACTIVE);

      transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.PARTIALLY_ACTIVE, SagaActorState.FAILED);

      SagaData sagaData = expectMsgClass(SagaData.class);
      assertEquals(sagaData.getGlobalTxId(), globalTxId);
      assertEquals(sagaData.getTxEntityMap().size(), 2);
      assertEquals(sagaData.getTxEntityMap().get(localTxId_1).getState(), TxState.COMPENSATED);
      assertEquals(sagaData.getTxEntityMap().get(localTxId_2).getState(), TxState.FAILED);
      assertEquals(sagaData.getCompensationRunningCounter().intValue(), 0);

      transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.FAILED, SagaActorState.COMPENSATED);

      Terminated terminated = expectMsgClass(Terminated.class);
      assertEquals(terminated.getActor(), saga);

      system.stop(saga);
    }};
  }

  /**
   * 1. SagaStartedEvent
   * 2. TxStartedEvent
   * 3. TxAbortedEvent
   * 4. TxStartedEvent
   * 5. TxEndedEvent
   * 6. TxComponsitedEvent
   * 7. SagaAbortedEvent
   */
  @Test
  @SneakyThrows
  public void failureIsEarlierThanSuccessWithTwoSubTransactionsConcurrentTest() {
    new TestKit(system) {{
      final String globalTxId = UUID.randomUUID().toString();
      final String localTxId_1 = UUID.randomUUID().toString();
      final String localTxId_2 = UUID.randomUUID().toString();

      ActorRef saga = system.actorOf(SagaActor.props(genPersistenceId()), "saga");
      watch(saga);
      saga.tell(new PersistentFSM.SubscribeTransitionCallBack(getRef()), getRef());

      saga.tell(SagaStartedEvent.builder().globalTxId(globalTxId).build(), getRef());
      saga.tell(TxStartedEvent.builder().globalTxId(globalTxId).parentTxId(globalTxId).localTxId(localTxId_1).build(), getRef());
      saga.tell(TxAbortedEvent.builder().globalTxId(globalTxId).parentTxId(globalTxId).localTxId(localTxId_1).build(), getRef());
      saga.tell(TxStartedEvent.builder().globalTxId(globalTxId).parentTxId(globalTxId).localTxId(localTxId_2).build(), getRef());
      saga.tell(TxEndedEvent.builder().globalTxId(globalTxId).parentTxId(globalTxId).localTxId(localTxId_2).build(), getRef());
      saga.tell(TxComponsitedEvent.builder().globalTxId(globalTxId).parentTxId(globalTxId).localTxId(localTxId_2).build(), getRef());
      saga.tell(SagaAbortedEvent.builder().globalTxId(globalTxId).build(), getRef());

      //expect
      CurrentState currentState = expectMsgClass(PersistentFSM.CurrentState.class);
      assertEquals(SagaActorState.IDEL, currentState.state());

      PersistentFSM.Transition transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.IDEL, SagaActorState.READY);

      transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.READY, SagaActorState.PARTIALLY_ACTIVE);

      transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.PARTIALLY_ACTIVE, SagaActorState.FAILED);

      SagaData sagaData = expectMsgClass(SagaData.class);
      assertEquals(sagaData.getGlobalTxId(), globalTxId);
      assertEquals(sagaData.getTxEntityMap().size(), 2);
      assertEquals(sagaData.getTxEntityMap().get(localTxId_1).getState(), TxState.FAILED);
      assertEquals(sagaData.getTxEntityMap().get(localTxId_2).getState(), TxState.COMPENSATED);
      assertEquals(sagaData.getCompensationRunningCounter().intValue(), 0);

      transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.FAILED, SagaActorState.COMPENSATED);

      Terminated terminated = expectMsgClass(Terminated.class);
      assertEquals(terminated.getActor(), saga);

      system.stop(saga);
    }};
  }

  /**
   * 1. SagaStartedEvent
   * 2. TxStartedEvent
   * 3. TxEndedEvent
   * 4. TxStartedEvent
   * 5. TxEndedEvent
   * 6. TxStartedEvent
   * 7. TxAbortedEvent
   * 8. TxComponsitedEvent
   * 9. TxComponsitedEvent
   * 10.SagaAbortedEvent
   */
  @Test
  @SneakyThrows
  public void thirdTxAbortedEventTest() {
    new TestKit(system) {{
      final String globalTxId = UUID.randomUUID().toString();
      final String localTxId_1 = UUID.randomUUID().toString();
      final String localTxId_2 = UUID.randomUUID().toString();
      final String localTxId_3 = UUID.randomUUID().toString();

      ActorRef saga = system.actorOf(SagaActor.props(genPersistenceId()), "saga");
      watch(saga);
      saga.tell(new PersistentFSM.SubscribeTransitionCallBack(getRef()), getRef());

      saga.tell(SagaStartedEvent.builder().globalTxId(globalTxId).build(), getRef());
      saga.tell(TxStartedEvent.builder().globalTxId(globalTxId).parentTxId(globalTxId).localTxId(localTxId_1).build(), getRef());
      saga.tell(TxEndedEvent.builder().globalTxId(globalTxId).parentTxId(globalTxId).localTxId(localTxId_1).build(), getRef());
      saga.tell(TxStartedEvent.builder().globalTxId(globalTxId).parentTxId(globalTxId).localTxId(localTxId_2).build(), getRef());
      saga.tell(TxEndedEvent.builder().globalTxId(globalTxId).parentTxId(globalTxId).localTxId(localTxId_2).build(), getRef());
      saga.tell(TxStartedEvent.builder().globalTxId(globalTxId).parentTxId(globalTxId).localTxId(localTxId_3).build(), getRef());
      saga.tell(TxAbortedEvent.builder().globalTxId(globalTxId).parentTxId(globalTxId).localTxId(localTxId_3).build(), getRef());
      saga.tell(TxComponsitedEvent.builder().globalTxId(globalTxId).parentTxId(globalTxId).localTxId(localTxId_1).build(), getRef());
      saga.tell(TxComponsitedEvent.builder().globalTxId(globalTxId).parentTxId(globalTxId).localTxId(localTxId_2).build(), getRef());
      saga.tell(SagaAbortedEvent.builder().globalTxId(globalTxId).build(), getRef());

      //expect
      CurrentState currentState = expectMsgClass(PersistentFSM.CurrentState.class);
      assertEquals(SagaActorState.IDEL, currentState.state());

      PersistentFSM.Transition transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.IDEL, SagaActorState.READY);

      transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.READY, SagaActorState.PARTIALLY_ACTIVE);

      transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.PARTIALLY_ACTIVE, SagaActorState.PARTIALLY_COMMITTED);

      transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.PARTIALLY_COMMITTED, SagaActorState.PARTIALLY_ACTIVE);

      transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.PARTIALLY_ACTIVE, SagaActorState.PARTIALLY_COMMITTED);

      transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.PARTIALLY_COMMITTED, SagaActorState.PARTIALLY_ACTIVE);

      transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.PARTIALLY_ACTIVE, SagaActorState.FAILED);

      SagaData sagaData = expectMsgClass(SagaData.class);
      assertEquals(sagaData.getGlobalTxId(), globalTxId);
      assertEquals(sagaData.getTxEntityMap().size(), 3);
      assertEquals(sagaData.getTxEntityMap().get(localTxId_1).getState(), TxState.COMPENSATED);
      assertEquals(sagaData.getTxEntityMap().get(localTxId_2).getState(), TxState.COMPENSATED);
      assertEquals(sagaData.getTxEntityMap().get(localTxId_3).getState(), TxState.FAILED);
      assertEquals(sagaData.getCompensationRunningCounter().intValue(), 0);

      transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.FAILED, SagaActorState.COMPENSATED);

      Terminated terminated = expectMsgClass(Terminated.class);
      assertEquals(terminated.getActor(), saga);

      system.stop(saga);
    }};
  }

  /**
   * 1. SagaStartedEvent
   * 2. TxStartedEvent
   * 3. TxEndedEvent
   * 4. TxStartedEvent
   * 5. TxAbortedEvent
   * 6. TxStartedEvent
   * 7. TxEndedEvent
   * 8. TxComponsitedEvent
   * 9. TxComponsitedEvent
   * 10.SagaAbortedEvent
   */
  @Test
  @SneakyThrows
  public void failureIsEarlierThanSuccessWithThreeSubTransactionsConcurrentTest() {
    new TestKit(system) {{
      final String globalTxId = UUID.randomUUID().toString();
      final String localTxId_1 = UUID.randomUUID().toString();
      final String localTxId_2 = UUID.randomUUID().toString();
      final String localTxId_3 = UUID.randomUUID().toString();

      ActorRef saga = system.actorOf(SagaActor.props(genPersistenceId()), "saga");
      watch(saga);
      saga.tell(new PersistentFSM.SubscribeTransitionCallBack(getRef()), getRef());

      saga.tell(SagaStartedEvent.builder().globalTxId(globalTxId).build(), getRef());
      saga.tell(TxStartedEvent.builder().globalTxId(globalTxId).parentTxId(globalTxId).localTxId(localTxId_1).build(), getRef());
      saga.tell(TxEndedEvent.builder().globalTxId(globalTxId).parentTxId(globalTxId).localTxId(localTxId_1).build(), getRef());
      saga.tell(TxStartedEvent.builder().globalTxId(globalTxId).parentTxId(globalTxId).localTxId(localTxId_2).build(), getRef());
      saga.tell(TxAbortedEvent.builder().globalTxId(globalTxId).parentTxId(globalTxId).localTxId(localTxId_2).build(), getRef());
      saga.tell(TxStartedEvent.builder().globalTxId(globalTxId).parentTxId(globalTxId).localTxId(localTxId_3).build(), getRef());
      saga.tell(TxEndedEvent.builder().globalTxId(globalTxId).parentTxId(globalTxId).localTxId(localTxId_3).build(), getRef());
      saga.tell(TxComponsitedEvent.builder().globalTxId(globalTxId).parentTxId(globalTxId).localTxId(localTxId_1).build(), getRef());
      saga.tell(TxComponsitedEvent.builder().globalTxId(globalTxId).parentTxId(globalTxId).localTxId(localTxId_3).build(), getRef());
      saga.tell(SagaAbortedEvent.builder().globalTxId(globalTxId).build(), getRef());

      //expect
      CurrentState currentState = expectMsgClass(PersistentFSM.CurrentState.class);
      assertEquals(SagaActorState.IDEL, currentState.state());

      PersistentFSM.Transition transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.IDEL, SagaActorState.READY);

      transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.READY, SagaActorState.PARTIALLY_ACTIVE);

      transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.PARTIALLY_ACTIVE, SagaActorState.PARTIALLY_COMMITTED);

      transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.PARTIALLY_COMMITTED, SagaActorState.PARTIALLY_ACTIVE);

      transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.PARTIALLY_ACTIVE, SagaActorState.FAILED);

      SagaData sagaData = expectMsgClass(SagaData.class);
      assertEquals(sagaData.getGlobalTxId(), globalTxId);
      assertEquals(sagaData.getTxEntityMap().size(), 3);
      assertEquals(sagaData.getTxEntityMap().get(localTxId_1).getState(), TxState.COMPENSATED);
      assertEquals(sagaData.getTxEntityMap().get(localTxId_2).getState(), TxState.FAILED);
      assertEquals(sagaData.getTxEntityMap().get(localTxId_3).getState(), TxState.COMPENSATED);
      assertEquals(sagaData.getCompensationRunningCounter().intValue(), 0);

      transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.FAILED, SagaActorState.COMPENSATED);

      Terminated terminated = expectMsgClass(Terminated.class);
      assertEquals(terminated.getActor(), saga);

      system.stop(saga);
    }};
  }

  /**
   * 1. SagaStartedEvent
   * 2. TxStartedEvent
   * 3. TxEndedEvent
   * 4. TxStartedEvent
   * 5. TxEndedEvent
   * 5. SagaTimeoutEvent
   */
  @Test
  @SneakyThrows
  public void sagaTimeoutEventAfterTxEndedEventToSuspendedTest() {
    new TestKit(system) {{
      final String globalTxId = UUID.randomUUID().toString();
      final String localTxId_1 = UUID.randomUUID().toString();
      final String localTxId_2 = UUID.randomUUID().toString();

      ActorRef saga = system.actorOf(SagaActor.props(genPersistenceId()), "saga");
      watch(saga);
      saga.tell(new PersistentFSM.SubscribeTransitionCallBack(getRef()), getRef());

      saga.tell(SagaStartedEvent.builder().globalTxId(globalTxId).build(), getRef());

      saga.tell(TxStartedEvent.builder().globalTxId(globalTxId).parentTxId(globalTxId)
          .localTxId(localTxId_1).build(), getRef());
      saga.tell(TxEndedEvent.builder().globalTxId(globalTxId).parentTxId(globalTxId)
          .localTxId(localTxId_1).build(), getRef());

      saga.tell(TxStartedEvent.builder().globalTxId(globalTxId).parentTxId(globalTxId)
          .localTxId(localTxId_2).build(), getRef());
      saga.tell(TxEndedEvent.builder().globalTxId(globalTxId).parentTxId(globalTxId)
          .localTxId(localTxId_2).build(), getRef());

      saga.tell(SagaTimeoutEvent.builder().globalTxId(globalTxId).build(), getRef());

      //expect
      CurrentState currentState = expectMsgClass(PersistentFSM.CurrentState.class);
      assertEquals(SagaActorState.IDEL, currentState.state());

      PersistentFSM.Transition transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.IDEL, SagaActorState.READY);

      transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.READY, SagaActorState.PARTIALLY_ACTIVE);

      transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.PARTIALLY_ACTIVE,
          SagaActorState.PARTIALLY_COMMITTED);

      transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.PARTIALLY_COMMITTED,
          SagaActorState.PARTIALLY_ACTIVE);

      transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.PARTIALLY_ACTIVE,
          SagaActorState.PARTIALLY_COMMITTED);

      SagaData sagaData = expectMsgClass(SagaData.class);
      assertEquals(sagaData.getGlobalTxId(), globalTxId);
      assertEquals(sagaData.getTxEntityMap().size(), 2);
      assertEquals(sagaData.getTxEntityMap().get(localTxId_1).getState(), TxState.COMMITTED);
      assertEquals(sagaData.getTxEntityMap().get(localTxId_2).getState(), TxState.COMMITTED);

      transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.PARTIALLY_COMMITTED,
          SagaActorState.SUSPENDED);

      Terminated terminated = expectMsgClass(Terminated.class);
      assertEquals(terminated.getActor(), saga);

      system.stop(saga);
    }};
  }

  /**
   * 1. SagaStartedEvent
   * 2. TxStartedEvent
   * 3. TxEndedEvent
   * 4. TxStartedEvent
   * 5. TxAbortedEvent
   * 6. SagaTimeoutEvent
   */
  @Test
  @SneakyThrows
  public void sagaTimeoutEventAfterTxAbortedEventToSuspendedTest() {
    new TestKit(system) {{
      final String globalTxId = UUID.randomUUID().toString();
      final String localTxId_1 = UUID.randomUUID().toString();
      final String localTxId_2 = UUID.randomUUID().toString();

      ActorRef saga = system.actorOf(SagaActor.props(genPersistenceId()), "saga");
      watch(saga);
      saga.tell(new PersistentFSM.SubscribeTransitionCallBack(getRef()), getRef());

      saga.tell(SagaStartedEvent.builder().globalTxId(globalTxId).build(), getRef());

      saga.tell(TxStartedEvent.builder().globalTxId(globalTxId).parentTxId(globalTxId)
          .localTxId(localTxId_1).build(), getRef());
      saga.tell(TxEndedEvent.builder().globalTxId(globalTxId).parentTxId(globalTxId)
          .localTxId(localTxId_1).build(), getRef());

      saga.tell(TxStartedEvent.builder().globalTxId(globalTxId).parentTxId(globalTxId)
          .localTxId(localTxId_2).build(), getRef());
      saga.tell(TxAbortedEvent.builder().globalTxId(globalTxId).parentTxId(globalTxId)
          .localTxId(localTxId_2).build(), getRef());

      saga.tell(SagaTimeoutEvent.builder().globalTxId(globalTxId).build(), getRef());

      //expect
      CurrentState currentState = expectMsgClass(PersistentFSM.CurrentState.class);
      assertEquals(SagaActorState.IDEL, currentState.state());

      PersistentFSM.Transition transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.IDEL, SagaActorState.READY);

      transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.READY, SagaActorState.PARTIALLY_ACTIVE);

      transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.PARTIALLY_ACTIVE,
          SagaActorState.PARTIALLY_COMMITTED);

      transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.PARTIALLY_COMMITTED,
          SagaActorState.PARTIALLY_ACTIVE);

      transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.PARTIALLY_ACTIVE,
          SagaActorState.FAILED);

      SagaData sagaData = expectMsgClass(SagaData.class);
      assertEquals(sagaData.getGlobalTxId(), globalTxId);
      assertEquals(sagaData.getTxEntityMap().size(), 2);
      assertEquals(sagaData.getTxEntityMap().get(localTxId_1).getState(), TxState.COMMITTED);
      assertEquals(sagaData.getTxEntityMap().get(localTxId_2).getState(), TxState.FAILED);

      transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.FAILED, SagaActorState.SUSPENDED);

      Terminated terminated = expectMsgClass(Terminated.class);
      assertEquals(terminated.getActor(), saga);

      system.stop(saga);
    }};
  }

  /**
   * 1. SagaStartedEvent
   * 2. TxStartedEvent
   * 3. SagaTimeoutEvent
   */
  @Test
  @SneakyThrows
  public void sagaTimeoutEventAfterTxStartedEventToSuspendedTest() {
    new TestKit(system) {{
      final String globalTxId = UUID.randomUUID().toString();
      final String localTxId_1 = UUID.randomUUID().toString();

      ActorRef saga = system.actorOf(SagaActor.props(genPersistenceId()), "saga");
      watch(saga);
      saga.tell(new PersistentFSM.SubscribeTransitionCallBack(getRef()), getRef());

      saga.tell(SagaStartedEvent.builder().globalTxId(globalTxId).build(), getRef());

      saga.tell(TxStartedEvent.builder().globalTxId(globalTxId).parentTxId(globalTxId)
          .localTxId(localTxId_1).build(), getRef());

      saga.tell(SagaTimeoutEvent.builder().globalTxId(globalTxId).build(), getRef());

      //expect
      CurrentState currentState = expectMsgClass(PersistentFSM.CurrentState.class);
      assertEquals(SagaActorState.IDEL, currentState.state());

      PersistentFSM.Transition transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.IDEL, SagaActorState.READY);

      transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.READY, SagaActorState.PARTIALLY_ACTIVE);

      SagaData sagaData = expectMsgClass(SagaData.class);
      assertEquals(sagaData.getGlobalTxId(), globalTxId);
      assertEquals(sagaData.getTxEntityMap().size(), 1);
      assertEquals(sagaData.getTxEntityMap().get(localTxId_1).getState(), TxState.ACTIVE);

      transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.PARTIALLY_ACTIVE,
          SagaActorState.SUSPENDED);

      Terminated terminated = expectMsgClass(Terminated.class);
      assertEquals(terminated.getActor(), saga);

      system.stop(saga);
    }};
  }

  /**
   * 1. SagaStartedEvent
   * 2. TxStartedEvent
   * 3. TxEndedEvent
   * 4. TxStartedEvent
   * 5. TxEndedEvent
   * 6. SagaAbortedEvent
   * 7. TxComponsitedEvent
   * 8. TxComponsitedEvent
   */
  @Test
  @SneakyThrows
  public void sagaAbortedEventTest() {
    new TestKit(system) {{
      final String globalTxId = UUID.randomUUID().toString();
      final String localTxId_1 = UUID.randomUUID().toString();
      final String localTxId_2 = UUID.randomUUID().toString();

      ActorRef saga = system.actorOf(SagaActor.props(genPersistenceId()), "saga");
      watch(saga);
      saga.tell(new PersistentFSM.SubscribeTransitionCallBack(getRef()), getRef());
      saga.tell(SagaStartedEvent.builder().globalTxId(globalTxId).build(), getRef());
      saga.tell(TxStartedEvent.builder().globalTxId(globalTxId).parentTxId(globalTxId).localTxId(localTxId_1).build(), getRef());
      saga.tell(TxEndedEvent.builder().globalTxId(globalTxId).parentTxId(globalTxId).localTxId(localTxId_1).build(), getRef());
      saga.tell(TxStartedEvent.builder().globalTxId(globalTxId).parentTxId(globalTxId).localTxId(localTxId_2).build(), getRef());
      saga.tell(TxEndedEvent.builder().globalTxId(globalTxId).parentTxId(globalTxId).localTxId(localTxId_2).build(), getRef());
      saga.tell(SagaAbortedEvent.builder().globalTxId(globalTxId).build(), getRef());
      saga.tell(TxComponsitedEvent.builder().globalTxId(globalTxId).parentTxId(globalTxId).localTxId(localTxId_1).build(), getRef());
      saga.tell(TxComponsitedEvent.builder().globalTxId(globalTxId).parentTxId(globalTxId).localTxId(localTxId_2).build(), getRef());

      //expect
      CurrentState currentState = expectMsgClass(PersistentFSM.CurrentState.class);
      assertEquals(SagaActorState.IDEL, currentState.state());

      PersistentFSM.Transition transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.IDEL, SagaActorState.READY);

      transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.READY, SagaActorState.PARTIALLY_ACTIVE);

      transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.PARTIALLY_ACTIVE,
          SagaActorState.PARTIALLY_COMMITTED);

      transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.PARTIALLY_COMMITTED,
          SagaActorState.PARTIALLY_ACTIVE);

      transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.PARTIALLY_ACTIVE,
          SagaActorState.PARTIALLY_COMMITTED);

      transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.PARTIALLY_COMMITTED,
          SagaActorState.FAILED);

      SagaData sagaData = expectMsgClass(SagaData.class);
      assertEquals(sagaData.getGlobalTxId(), globalTxId);
      assertEquals(sagaData.getTxEntityMap().size(), 2);
      assertEquals(sagaData.getTxEntityMap().get(localTxId_1).getState(), TxState.COMPENSATED);
      assertEquals(sagaData.getTxEntityMap().get(localTxId_2).getState(), TxState.COMPENSATED);

      transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.FAILED,
          SagaActorState.COMPENSATED);

      Terminated terminated = expectMsgClass(Terminated.class);
      assertEquals(terminated.getActor(), saga);

      system.stop(saga);
    }};
  }

  /**
   * 1. SagaStartedEvent(5s)
   */
  @Test
  @SneakyThrows
  public void sagaStartAnnotationTimeoutTest() {
    new TestKit(system) {{
      final String globalTxId = UUID.randomUUID().toString();
      final int timeout = 5;

      ActorRef saga = system.actorOf(SagaActor.props(genPersistenceId()), "saga");
      watch(saga);
      saga.tell(new PersistentFSM.SubscribeTransitionCallBack(getRef()), getRef());
      saga.tell(SagaStartedEvent.builder().globalTxId(globalTxId).timeout(timeout).build(), getRef());

      CurrentState currentState = expectMsgClass(PersistentFSM.CurrentState.class);
      assertEquals(SagaActorState.IDEL, currentState.state());
      PersistentFSM.Transition transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.IDEL, SagaActorState.READY);
      transition = expectMsgClass(Duration.ofSeconds(timeout+2),PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.READY, SagaActorState.SUSPENDED);
      Terminated terminated = expectMsgClass(Terminated.class);
      assertEquals(terminated.getActor(), saga);

      system.stop(saga);
    }};
  }

  /**
   * 1. SagaStartedEvent(5s)
   * 2. TxStartedEvent
   */
  @Test
  @SneakyThrows
  public void sagaStartAnnotationTimeoutTest2() {
    new TestKit(system) {{
      final String globalTxId = UUID.randomUUID().toString();
      final String localTxId_1 = UUID.randomUUID().toString();
      final int timeout = 5;

      ActorRef saga = system.actorOf(SagaActor.props(genPersistenceId()), "saga");
      watch(saga);
      saga.tell(new PersistentFSM.SubscribeTransitionCallBack(getRef()), getRef());
      saga.tell(SagaStartedEvent.builder().globalTxId(globalTxId).timeout(timeout).build(), getRef());
      saga.tell(TxStartedEvent.builder().globalTxId(globalTxId).parentTxId(globalTxId).localTxId(localTxId_1).build(), getRef());

      CurrentState currentState = expectMsgClass(PersistentFSM.CurrentState.class);
      assertEquals(SagaActorState.IDEL, currentState.state());
      PersistentFSM.Transition transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.IDEL, SagaActorState.READY);
      transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.READY, SagaActorState.PARTIALLY_ACTIVE);
      transition = expectMsgClass(Duration.ofSeconds(timeout+2),PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.PARTIALLY_ACTIVE, SagaActorState.SUSPENDED);
      Terminated terminated = expectMsgClass(Terminated.class);
      assertEquals(terminated.getActor(), saga);

      system.stop(saga);
    }};
  }

  /**
   * 1. SagaStartedEvent(5s)
   * 2. TxStartedEvent
   * 3. TxEndedEvent
   */
  @Test
  @SneakyThrows
  public void sagaStartAnnotationTimeoutTest3() {
    new TestKit(system) {{
      final String globalTxId = UUID.randomUUID().toString();
      final String localTxId_1 = UUID.randomUUID().toString();
      final int timeout = 5;

      ActorRef saga = system.actorOf(SagaActor.props(genPersistenceId()), "saga");
      watch(saga);
      saga.tell(new PersistentFSM.SubscribeTransitionCallBack(getRef()), getRef());
      saga.tell(SagaStartedEvent.builder().globalTxId(globalTxId).timeout(timeout).build(), getRef());
      saga.tell(TxStartedEvent.builder().globalTxId(globalTxId).parentTxId(globalTxId).localTxId(localTxId_1).build(), getRef());
      saga.tell(TxEndedEvent.builder().globalTxId(globalTxId).parentTxId(globalTxId).localTxId(localTxId_1).build(), getRef());

      CurrentState currentState = expectMsgClass(PersistentFSM.CurrentState.class);
      assertEquals(SagaActorState.IDEL, currentState.state());
      PersistentFSM.Transition transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.IDEL, SagaActorState.READY);
      transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.READY, SagaActorState.PARTIALLY_ACTIVE);
      transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.PARTIALLY_ACTIVE, SagaActorState.PARTIALLY_COMMITTED);
      transition = expectMsgClass(Duration.ofSeconds(timeout+2),PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.PARTIALLY_COMMITTED, SagaActorState.SUSPENDED);
      Terminated terminated = expectMsgClass(Terminated.class);
      assertEquals(terminated.getActor(), saga);

      system.stop(saga);
    }};
  }

  /**
   * 1. SagaStartedEvent(5s)
   * 2. TxStartedEvent
   * 3. TxEndedEvent
   * 4. TxStartedEvent
   */
  @Test
  @SneakyThrows
  public void sagaStartAnnotationTimeoutTest4() {
    new TestKit(system) {{
      final String globalTxId = UUID.randomUUID().toString();
      final String localTxId_1 = UUID.randomUUID().toString();
      final String localTxId_2 = UUID.randomUUID().toString();
      final int timeout = 5;

      ActorRef saga = system.actorOf(SagaActor.props(genPersistenceId()), "saga");
      watch(saga);
      saga.tell(new PersistentFSM.SubscribeTransitionCallBack(getRef()), getRef());
      saga.tell(SagaStartedEvent.builder().globalTxId(globalTxId).timeout(timeout).build(), getRef());
      saga.tell(TxStartedEvent.builder().globalTxId(globalTxId).parentTxId(globalTxId).localTxId(localTxId_1).build(), getRef());
      saga.tell(TxEndedEvent.builder().globalTxId(globalTxId).parentTxId(globalTxId).localTxId(localTxId_1).build(), getRef());
      saga.tell(TxStartedEvent.builder().globalTxId(globalTxId).parentTxId(globalTxId).localTxId(localTxId_2).build(), getRef());

      CurrentState currentState = expectMsgClass(PersistentFSM.CurrentState.class);
      assertEquals(SagaActorState.IDEL, currentState.state());
      PersistentFSM.Transition transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.IDEL, SagaActorState.READY);
      transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.READY, SagaActorState.PARTIALLY_ACTIVE);
      transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.PARTIALLY_ACTIVE, SagaActorState.PARTIALLY_COMMITTED);
      transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.PARTIALLY_COMMITTED, SagaActorState.PARTIALLY_ACTIVE);
      transition = expectMsgClass(Duration.ofSeconds(timeout+2),PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.PARTIALLY_ACTIVE, SagaActorState.SUSPENDED);
      Terminated terminated = expectMsgClass(Terminated.class);
      assertEquals(terminated.getActor(), saga);

      system.stop(saga);
    }};
  }

  /**
   * 1. SagaStartedEvent(5s)
   * 2. TxStartedEvent
   * 3. TxEndedEvent
   * 4. TxStartedEvent
   * 5. TxEndedEvent
   */
  @Test
  @SneakyThrows
  public void sagaStartAnnotationTimeoutTest5() {
    new TestKit(system) {{
      final String globalTxId = UUID.randomUUID().toString();
      final String localTxId_1 = UUID.randomUUID().toString();
      final String localTxId_2 = UUID.randomUUID().toString();
      final int timeout = 5;

      ActorRef saga = system.actorOf(SagaActor.props(genPersistenceId()), "saga");
      watch(saga);
      saga.tell(new PersistentFSM.SubscribeTransitionCallBack(getRef()), getRef());
      saga.tell(SagaStartedEvent.builder().globalTxId(globalTxId).timeout(timeout).build(), getRef());
      saga.tell(TxStartedEvent.builder().globalTxId(globalTxId).parentTxId(globalTxId).localTxId(localTxId_1).build(), getRef());
      saga.tell(TxEndedEvent.builder().globalTxId(globalTxId).parentTxId(globalTxId).localTxId(localTxId_1).build(), getRef());
      saga.tell(TxStartedEvent.builder().globalTxId(globalTxId).parentTxId(globalTxId).localTxId(localTxId_2).build(), getRef());
      saga.tell(TxEndedEvent.builder().globalTxId(globalTxId).parentTxId(globalTxId).localTxId(localTxId_2).build(), getRef());

      CurrentState currentState = expectMsgClass(PersistentFSM.CurrentState.class);
      assertEquals(SagaActorState.IDEL, currentState.state());
      PersistentFSM.Transition transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.IDEL, SagaActorState.READY);
      transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.READY, SagaActorState.PARTIALLY_ACTIVE);
      transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.PARTIALLY_ACTIVE, SagaActorState.PARTIALLY_COMMITTED);
      transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.PARTIALLY_COMMITTED, SagaActorState.PARTIALLY_ACTIVE);
      transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.PARTIALLY_ACTIVE, SagaActorState.PARTIALLY_COMMITTED);
      transition = expectMsgClass(Duration.ofSeconds(timeout+2),PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.PARTIALLY_COMMITTED, SagaActorState.SUSPENDED);
      Terminated terminated = expectMsgClass(Terminated.class);
      assertEquals(terminated.getActor(), saga);

      system.stop(saga);
    }};
  }

  /**
   * 1. SagaStartedEvent(5s)
   * 2. TxStartedEvent
   * 3. TxEndedEvent
   * 4. TxStartedEvent
   * 5. TxEndedEvent
   * 6. SagaEndedEvent
   */
  @Test
  @SneakyThrows
  public void sagaStartAnnotationTimeoutTest6() {
    new TestKit(system) {{
      final String globalTxId = UUID.randomUUID().toString();
      final String localTxId_1 = UUID.randomUUID().toString();
      final String localTxId_2 = UUID.randomUUID().toString();
      final int timeout = 5;

      ActorRef saga = system.actorOf(SagaActor.props(genPersistenceId()), "saga");
      watch(saga);
      saga.tell(new PersistentFSM.SubscribeTransitionCallBack(getRef()), getRef());
      saga.tell(SagaStartedEvent.builder().globalTxId(globalTxId).timeout(timeout).build(), getRef());
      saga.tell(TxStartedEvent.builder().globalTxId(globalTxId).parentTxId(globalTxId).localTxId(localTxId_1).build(), getRef());
      saga.tell(TxEndedEvent.builder().globalTxId(globalTxId).parentTxId(globalTxId).localTxId(localTxId_1).build(), getRef());
      saga.tell(TxStartedEvent.builder().globalTxId(globalTxId).parentTxId(globalTxId).localTxId(localTxId_2).build(), getRef());
      saga.tell(TxEndedEvent.builder().globalTxId(globalTxId).parentTxId(globalTxId).localTxId(localTxId_2).build(), getRef());
      Thread.sleep(6000);
      saga.tell(SagaEndedEvent.builder().globalTxId(globalTxId).build(), getRef());

      CurrentState currentState = expectMsgClass(PersistentFSM.CurrentState.class);
      assertEquals(SagaActorState.IDEL, currentState.state());
      PersistentFSM.Transition transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.IDEL, SagaActorState.READY);
      transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.READY, SagaActorState.PARTIALLY_ACTIVE);
      transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.PARTIALLY_ACTIVE, SagaActorState.PARTIALLY_COMMITTED);
      transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.PARTIALLY_COMMITTED, SagaActorState.PARTIALLY_ACTIVE);
      transition = expectMsgClass(PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.PARTIALLY_ACTIVE, SagaActorState.PARTIALLY_COMMITTED);
      transition = expectMsgClass(Duration.ofSeconds(timeout+2),PersistentFSM.Transition.class);
      assertSagaTransition(transition, saga, SagaActorState.PARTIALLY_COMMITTED, SagaActorState.SUSPENDED);
      Terminated terminated = expectMsgClass(Terminated.class);
      assertEquals(terminated.getActor(), saga);

      system.stop(saga);
    }};
  }

  private static void assertSagaTransition(PersistentFSM.Transition transition, ActorRef actorRef,
      SagaActorState from, SagaActorState to) {
    assertEquals(transition.fsmRef(), actorRef);
    assertEquals(transition.from(), from);
    assertEquals(transition.to(), to);
  }
}
