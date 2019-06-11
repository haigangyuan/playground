package coolbeevip.playgroud.statemachine.saga.actors.event.base;

import lombok.Getter;

@Getter
public class TxEvent extends BaseEvent {
  private String parentTxId;
  private String localTxId;

  public TxEvent(String globalTxId, String parentTxId, String localTxId) {
    super(globalTxId);
    this.parentTxId = parentTxId;
    this.localTxId = localTxId;
  }
}