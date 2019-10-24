package io.coti.trustscore.http;

import io.coti.basenode.data.Hash;
import io.coti.trustscore.data.tsenums.DebtBalanceBasedEventRequestType;
import lombok.Data;

import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

@Data
public class InsertDebtBalanceBasedScoreRequest extends SignedRequest {
    private static final long serialVersionUID = 3578558707689546957L;
    @NotNull
    public DebtBalanceBasedEventRequestType eventType;
    @NotNull
    public BigDecimal amount;
    @NotNull
    public Hash eventIdentifier;
    @NotNull
    public Hash otherUserHash;
}