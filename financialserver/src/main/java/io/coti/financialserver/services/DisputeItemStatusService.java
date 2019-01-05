package io.coti.financialserver.services;

import io.coti.basenode.data.TransactionData;
import io.coti.basenode.model.Transactions;
import io.coti.financialserver.data.ActionSide;
import io.coti.financialserver.data.DisputeData;
import io.coti.financialserver.data.DisputeItemData;
import io.coti.financialserver.data.DisputeItemStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.coti.financialserver.http.HttpStringConstants.*;

public enum DisputeItemStatusService {
    AcceptedByMerchant(DisputeItemStatus.AcceptedByMerchant, EnumSet.of(DisputeItemStatus.Recall), ActionSide.Merchant, true, true) {
        public void changeDisputeItemsStatuses(DisputeData disputeData, Long itemId) {
            changePreClaimDisputeItemsStatuses(disputeData, itemId);
        }

        @Override
        public void changeDisputeStatus(DisputeData disputeData) throws Exception {
            this.CanceledByConsumer.changeDisputeStatus(disputeData);

        }
    },
    RejectedByMerchant(DisputeItemStatus.RejectedByMerchant, EnumSet.of(DisputeItemStatus.Recall), ActionSide.Merchant, false, false) {
        public void changeDisputeItemsStatuses(DisputeData disputeData, Long itemId) {
            changePreClaimDisputeItemsStatuses(disputeData, itemId);
        }

        @Override
        public void changeDisputeStatus(DisputeData disputeData) throws Exception {
            this.Claim.changeDisputeStatus(disputeData);
        }
    },
    CanceledByConsumer(DisputeItemStatus.CanceledByConsumer, EnumSet.of(DisputeItemStatus.Recall), ActionSide.Consumer, true, false) {
        public void changeDisputeItemsStatuses(DisputeData disputeData, Long itemId) {
            changePreClaimDisputeItemsStatuses(disputeData, itemId);
        }

        public void changeDisputeStatus(DisputeData disputeData) throws Exception {
            if (!existRecallorRejectedDisputeItems(disputeData)) {
                DisputeStatusService.Closed.changeStatus(disputeData);
            } else {
                this.Claim.changeDisputeStatus(disputeData);
            }
        }
    },
    Claim(DisputeItemStatus.Claim, EnumSet.of(DisputeItemStatus.Recall, DisputeItemStatus.RejectedByMerchant), ActionSide.FinancialServer, false, false) {
        public void changeDisputeStatus(DisputeData disputeData) throws Exception {
            if(existRecallDisputeItems(disputeData)){
                return;
            }
            DisputeStatusService.Claim.changeStatus(disputeData);
        }
    },
    AcceptedByArbitrators(DisputeItemStatus.AcceptedByArbitrators, EnumSet.of(DisputeItemStatus.Claim), ActionSide.Arbitrator, true, true) {
        public void changeDisputeStatus(DisputeData disputeData) throws Exception {
            changeDisputeStatusForPostClaimDisputeItem(disputeData);
        }
    },
    RejectedByArbitrators(DisputeItemStatus.RejectedByArbitrators, EnumSet.of(DisputeItemStatus.Claim), ActionSide.Arbitrator, true, false) {
        public void changeDisputeStatus(DisputeData disputeData) throws Exception {
            changeDisputeStatusForPostClaimDisputeItem(disputeData);
        }
    };

    private DisputeItemStatus newDisputeItemStatus;
    private Set<DisputeItemStatus> previousDisputeItemStatuses;
    private ActionSide actionSide;
    private boolean finalStatus;
    private boolean refundable;
    protected Transactions transactions;
    protected RollingReserveService rollingReserveService;

    DisputeItemStatusService(DisputeItemStatus newDisputeItemStatus, Set<DisputeItemStatus> previousDisputeItemStatuses, ActionSide actionSide, boolean finalStatus, boolean refundable) {
        this.newDisputeItemStatus = newDisputeItemStatus;
        this.previousDisputeItemStatuses = previousDisputeItemStatuses;
        this.actionSide = actionSide;
        this.finalStatus = finalStatus;
        this.refundable = refundable;
    }

    @Component
    public static class DisputeItemStatusServiceInjector {
        @Autowired
        private Transactions transactions;
        @Autowired
        private RollingReserveService rollingReserveService;

        @PostConstruct
        public void postConstruct() {
            for (DisputeItemStatusService disputeItemStatusService : EnumSet.allOf(DisputeItemStatusService.class)) {
                disputeItemStatusService.transactions = transactions;
                disputeItemStatusService.rollingReserveService = rollingReserveService;
            }
        }
    }

    private boolean isActionSideValid(ActionSide actionSide) {
        return this.actionSide.equals(actionSide);
    }

    public boolean isFinalStatus() {
        return finalStatus;
    }

    public boolean isRefundable() {
        return refundable;
    }

    public void changeStatus(DisputeData disputeData, Long itemId, ActionSide actionSide) throws Exception {
        if (!isActionSideValid(actionSide)) {
            throw new Exception(String.format(DISPUTE_ITEM_STATUS_INVALID_ACTIONSIDE, actionSide.toString(), toString()));
        }
        DisputeItemData disputeItemData = disputeData.getDisputeItem(itemId);
        if (disputeItemData == null) {
            throw new Exception(DISPUTE_ITEM_NOT_FOUND);
        }
        if (!disputeItemData.getStatus().equals(DisputeItemStatus.Recall) && valueOf(disputeItemData.getStatus().toString()).isFinalStatus()) {
            throw new Exception(DISPUTE_ITEM_STATUS_FINAL);
        }
        if (!previousDisputeItemStatuses.contains(disputeItemData.getStatus())) {
            throw new Exception(DISPUTE_ITEM_STATUS_INVALID_CHANGE);
        }
        changeStatus(disputeItemData);
        changeDisputeItemsStatuses(disputeData, itemId);
        if (isFinalStatusForAllItems(disputeData)) {
            createChargeBackTransaction(disputeData);
        }
        changeDisputeStatus(disputeData);
    }

    private void changeStatus(DisputeItemData disputeItemData) {
        disputeItemData.setStatus(newDisputeItemStatus);
        if(actionSide.equals(ActionSide.Arbitrator)){
            disputeItemData.setArbitratorsDecisionTime(Instant.now());
        }
    }

    public void changeDisputeItemsStatuses(DisputeData disputeData, Long itemId) {

    }

    public void changePreClaimDisputeItemsStatuses(DisputeData disputeData, Long itemId) {
        if (existRecallDisputeItems(disputeData)) {
            return;
        }
        disputeData.getDisputeItems().forEach(disputeItemData -> {
            if (disputeItemData.getStatus().equals(DisputeItemStatus.RejectedByMerchant)) {
                disputeItemData.setStatus(DisputeItemStatus.Claim);
            }
        });
    }

    public boolean existRecallDisputeItems(DisputeData disputeData) {
        Stream<DisputeItemData> disputeItemDataStream = disputeData.getDisputeItems().stream().filter(disputeItemData -> disputeItemData.getStatus().equals(DisputeItemStatus.Recall));
        return disputeItemDataStream.count() != 0;
    }

    public boolean existRecallorRejectedDisputeItems(DisputeData disputeData) {
        Stream<DisputeItemData> disputeItemDataStream = disputeData.getDisputeItems().stream().filter(disputeItemData -> disputeItemData.getStatus().equals(DisputeItemStatus.Recall) || disputeItemData.getStatus().equals(DisputeItemStatus.RejectedByMerchant));
        return disputeItemDataStream.count() != 0;
    }

    public void changeDisputeStatusForPostClaimDisputeItem(DisputeData disputeData) throws Exception {
        for (DisputeItemData disputeItemData : disputeData.getDisputeItems()) {
            if (disputeItemData.getStatus().equals(DisputeItemStatus.Claim)) {
                return;
            }
        }
        DisputeStatusService.Closed.changeStatus(disputeData);
    }

    public void createChargeBackTransaction(DisputeData disputeData) {
        List<DisputeItemData> refundableDisputeItems = disputeData.getDisputeItems().stream().filter(disputeItemData -> valueOf(disputeItemData.getStatus().toString()).isRefundable()).collect(Collectors.toList());
        if (refundableDisputeItems.size() != 0) {
            BigDecimal refundAmount = refundableDisputeItems.stream().map(DisputeItemData::getPrice).reduce(BigDecimal.ZERO, BigDecimal::add);
            TransactionData transactionData = transactions.getByHash(disputeData.getTransactionHash());
            rollingReserveService.chargebackConsumer(disputeData, transactionData.getSenderHash(), refundAmount);
        }

    }

    abstract void changeDisputeStatus(DisputeData disputeData) throws Exception;


    public boolean isFinalStatusForAllItems(DisputeData disputeData) {
        List<DisputeItemData> disputeItems = disputeData.getDisputeItems();
        for (DisputeItemData disputeItemData : disputeItems) {
            if (disputeItemData.getStatus().equals(DisputeItemStatus.Recall) || !valueOf(disputeItemData.getStatus().toString()).isFinalStatus()) {
                return false;
            }
        }
        return true;
    }
}
