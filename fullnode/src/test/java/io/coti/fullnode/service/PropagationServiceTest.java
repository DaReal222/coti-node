package io.coti.fullnode.service;

import io.coti.common.services.PropagationService;
import io.coti.fullnode.controllers.PropagationController;
import io.coti.common.data.Hash;
import io.coti.common.http.GetTransactionRequest;
import lombok.extern.slf4j.Slf4j;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit4.SpringRunner;

@RunWith(SpringRunner.class)
//@ContextConfiguration(classes = io.coti.fullnode.AppConfig.class)
@Slf4j
public class PropagationServiceTest {
    @Autowired
    private PropagationService propagationService;

    @Autowired
    private PropagationController propagationController;

    @Before
    public void setUp() throws Exception {

    }

    @Test
    public void propagateToNeighbors() {

    }

    @Test
    public void propagateFromNeighbors() {
        GetTransactionRequest getTransactionRequest = new GetTransactionRequest();
        getTransactionRequest.transactionHash = new Hash("01");
        propagationService.propagateFromNeighbors(getTransactionRequest.transactionHash);
    }


    @Test
    public void getTransactionFromCurrentNode() {
    }

    @Test
    public void loadNodesList() {
    }

    @Test
    public void loadCurrentNode() {
    }
}