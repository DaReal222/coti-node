package io.coti.common.services;

import io.coti.common.data.Hash;
import io.coti.common.data.TccInfo;
import io.coti.common.services.interfaces.IQueueService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.concurrent.ConcurrentLinkedQueue;

@Component
@Slf4j
public class QueueService implements IQueueService {

    private ConcurrentLinkedQueue<Hash> tccQueue;

    private ConcurrentLinkedQueue<TccInfo> updateBalanceQueue;

    @PostConstruct
    private void init(){
        tccQueue = new ConcurrentLinkedQueue<>();
        updateBalanceQueue = new ConcurrentLinkedQueue<>();
    }

    @Override
    public void addToTccQueue(Hash hash) {
        tccQueue.add(hash);
        log.info("Hash {} , was added to tccQueue", hash);
    }

    @Override
    public ConcurrentLinkedQueue<Hash> getTccQueue() {
        return tccQueue;
    }

    @Override
    public void  removeTccQueue() {
        tccQueue.clear();
    }

    @Override
    public void addToUpdateBalanceQueue(TccInfo tccInfo) {
        updateBalanceQueue.add(tccInfo);
        log.info("TccInfo {} , was added to updateBalanceQueue", tccInfo);
    }

    @Override
    public ConcurrentLinkedQueue<TccInfo> getUpdateBalanceQueue() {
        return updateBalanceQueue;
    }
}