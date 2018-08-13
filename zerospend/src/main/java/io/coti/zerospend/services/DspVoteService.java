package io.coti.zerospend.services;

import io.coti.common.communication.interfaces.IPropagationPublisher;
import io.coti.common.crypto.DspVoteCrypto;
import io.coti.common.data.*;
import io.coti.common.model.TransactionVotes;
import io.coti.common.model.Transactions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
@Service
public class DspVoteService {
    @Value("#{'${dsp.server.addresses}'.split(',')}")
    private List<String> dspServerAddresses;
    @Autowired
    private IPropagationPublisher propagationPublisher;
    @Autowired
    private TransactionVotes transactionVotes;
    @Autowired
    private Transactions transactions;
    @Autowired
    private TransactionIndexService transactionIndexService;
    private ConcurrentMap<Hash, List<DspVote>> transactionHashToVotesListMapping;
    private static final String NODE_HASH_ENDPOINT = "/nodeHash";
    private List<Hash> currentLiveDspNodes;

    @PostConstruct
    public void init() {
        transactionHashToVotesListMapping = new ConcurrentHashMap<>();
    }

    public void preparePropagatedTransactionForVoting(TransactionData transactionData) {
        log.info("Received new transaction. Live DSP Nodes: ");
        currentLiveDspNodes.forEach(hash -> log.info(hash.toHexString()));
        TransactionVoteData transactionVoteData = new TransactionVoteData(transactionData.getHash(), currentLiveDspNodes);
        transactionVotes.put(transactionVoteData);
        transactionHashToVotesListMapping.put(transactionData.getHash(), new LinkedList<>());
    }

    public String receiveDspVote(DspVote dspVote) {
        log.info("Received new Dsp Vote: {}", dspVote);
        Hash transactionHash = dspVote.getTransactionHash();
        synchronized (transactionHash.toHexString()) {
            TransactionVoteData transactionVoteData = transactionVotes.getByHash(transactionHash);
            if (transactionVoteData == null) {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                transactionVoteData = transactionVotes.getByHash(transactionHash);
                if (transactionVoteData == null) {
                    return "Transaction does not exist";
                }
            }

            if (!transactionVoteData.getLegalVoterDspHashes().contains(dspVote.getVoterDspHash())) {
                log.error("Unauthorized Dsp vote received. Dsp hash: {}, Transaction hash: {}", dspVote.getVoterDspHash(), dspVote.getTransactionHash());
                log.error("Keyset count: " + transactionVoteData.getDspHashToVoteMapping().keySet().size());
                transactionVoteData.getDspHashToVoteMapping().keySet().forEach(hash -> log.info(hash.toHexString()));
                return "Unauthorized";
            }

         /*   if (dspVoteCrypto.verifySignature(dspVote)) {
                log.error("Invalid vote signature. Dsp hash: {}, Transaction hash: {}", dspVote.getVoterDspHash(), dspVote.getTransactionHash());
                return "Invalid Signature";
            }*/
            if (transactionHashToVotesListMapping.get(transactionHash) == null) {
                log.error("Transaction Not existing in mapping!!"); // TODO: Solve and delete!
                return "Vote already processed";
            }
            log.info("Adding new vote: {}", dspVote);
            transactionHashToVotesListMapping.get(transactionHash).add(dspVote);
        }
        return "Ok";
    }

    @Scheduled(fixedDelay = 10000, initialDelay = 5000)
    private void updateLiveDspNodesList() {
        List<Hash> onlineDspHashes = new LinkedList<>();
        RestTemplate restTemplate = new RestTemplate();
        for (String dspServerAddress : dspServerAddresses) {
            try {
                Hash voterHash = restTemplate.getForObject(dspServerAddress + NODE_HASH_ENDPOINT, Hash.class);
                if (voterHash == null) {
                    log.info("Voter hash received is null: {}", dspServerAddress);
                } else {
                    onlineDspHashes.add(voterHash);
                }
            } catch (RestClientException e) {
                log.info("Unresponsive Dsp Node: {}", dspServerAddress);
            }
        }
        if (onlineDspHashes.isEmpty()) {
            log.error("No Dsp Nodes are online...");
        }
        currentLiveDspNodes = onlineDspHashes;
        log.info("Updated live dsp nodes list. Count: {}", currentLiveDspNodes.size());
    }

    @Scheduled(fixedDelay = 1000)
    private void sumAndSaveVotes() {
        for (Map.Entry<Hash, List<DspVote>> transactionHashToVotesListEntrySet :
                transactionHashToVotesListMapping.entrySet()) {
            synchronized (transactionHashToVotesListEntrySet.getKey().toHexString()) {
                if (transactionHashToVotesListEntrySet.getValue() != null && transactionHashToVotesListEntrySet.getValue().size() > 0) {
                    Hash transactionHash = transactionHashToVotesListEntrySet.getKey();
                    TransactionVoteData currentVotes = transactionVotes.getByHash(transactionHash);

                    for (DspVote additionalVote : transactionHashToVotesListEntrySet.getValue()) {
                        currentVotes.getDspHashToVoteMapping().putIfAbsent(additionalVote.getVoterDspHash(), additionalVote);
                    }

                    if (isPositiveMajorityAchieved(currentVotes)) {
                        publishDecision(transactionHash, true);
                        log.info("Valid vote majority achieved for: {}", currentVotes.getHash());
                    } else if (isNegativeMajorityAchieved(currentVotes)) {
                        publishDecision(transactionHash, false);
                        log.info("Invalid vote majority achieved for: {}", currentVotes.getHash());
                    } else {
                        transactionVotes.put(currentVotes);
                        log.info("Undecided majority: {}", currentVotes.getHash());
                    }
                }
            }
        }
    }

    private void publishDecision(Hash transactionHash, boolean isLegalTransaction) {
        TransactionData transactionData = transactions.getByHash(transactionHash);
        DspConsensusResult dspConsensusResult = new DspConsensusResult(transactionHash);
        dspConsensusResult.setIndex(transactionIndexService.generateTransactionIndex(transactionData));
        dspConsensusResult.setDspConsensus(isLegalTransaction);
        dspConsensusResult.setIndexingTime(new Date());
        propagationPublisher.propagate(dspConsensusResult, DspConsensusResult.class.getName() + "Dsp Result");
        transactionData.setDspConsensusResult(dspConsensusResult);
        transactions.put(transactionData);
        transactionHashToVotesListMapping.remove(transactionHash);
    }

    private boolean isPositiveMajorityAchieved(TransactionVoteData currentVotes) {
        long positiveVotersCount = currentVotes
                .getDspHashToVoteMapping()
                .values()
                .stream()
                .filter(vote -> vote.isValidTransaction())
                .count();
        long totalVotersCount = currentVotes.getLegalVoterDspHashes().size();
        if (positiveVotersCount > totalVotersCount / 2) {
            return true;
        }
        return false;
    }

    private boolean isNegativeMajorityAchieved(TransactionVoteData currentVotes) {
        long negativeVotersCount = currentVotes
                .getDspHashToVoteMapping()
                .values()
                .stream()
                .filter(vote -> !vote.isValidTransaction())
                .count();
        long totalVotersCount = currentVotes
                .getDspHashToVoteMapping()
                .size();
        if (negativeVotersCount > totalVotersCount / 2) {
            return true;
        }
        return false;
    }
}