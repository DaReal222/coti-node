package io.coti.common.services;

import io.coti.common.model.Transactions;
import io.coti.common.services.LiveView.LiveViewService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;

@Slf4j
@Service
public class InitializationService {
    @Autowired
    private Transactions transactions;
    @Autowired
    private BalanceService balanceService;
    @Autowired
    private ClusterService clusterService;
    @Autowired
    private LiveViewService liveViewService;

    @PostConstruct
    public void init() {
        transactions.forEach(transactionData -> {
            if (!transactionData.isTrustChainConsensus()) {
                clusterService.addUnconfirmedTransaction(transactionData);
            }
            liveViewService.addNode(transactions.getByHash(transactionData.getHash()));
            balanceService.insertSavedTransaction(transactionData);
        });
        log.info("Transactions Load completed");
        balanceService.finalizeInit();
        clusterService.finalizeInit();
    }
}