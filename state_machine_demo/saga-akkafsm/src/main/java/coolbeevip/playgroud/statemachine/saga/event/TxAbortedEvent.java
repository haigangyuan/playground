package coolbeevip.playgroud.statemachine.saga.event;

import coolbeevip.playgroud.statemachine.saga.event.base.TxEvent;
import lombok.Builder;
import lombok.Getter;


@Getter
public class TxAbortedEvent extends TxEvent {

  @Builder
  public TxAbortedEvent(String globalTxId, String parentTxId, String localTxId) {
    super(globalTxId, parentTxId, localTxId);
  }
}
