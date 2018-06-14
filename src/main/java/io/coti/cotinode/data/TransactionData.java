package io.coti.cotinode.data;

import java.util.List;

public class TransactionData {
    public List<BaseTransactionObject> baseTransactions;
    public String hash;
    public boolean isAttached;

    public TransactionData(List<BaseTransactionObject> baseTransactions){
        this.baseTransactions = baseTransactions;
    }
}
