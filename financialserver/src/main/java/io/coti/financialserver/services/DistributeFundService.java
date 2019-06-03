package io.coti.financialserver.services;

import io.coti.basenode.crypto.CryptoHelper;
import io.coti.basenode.crypto.NodeCryptoHelper;
import io.coti.basenode.data.Hash;
import io.coti.basenode.data.SignatureData;
import io.coti.basenode.http.Response;
import io.coti.basenode.http.interfaces.IResponse;
import io.coti.basenode.services.BaseNodeBalanceService;
import io.coti.basenode.services.interfaces.INetworkService;
import io.coti.financialserver.crypto.FundDistributionFileCrypto;
import io.coti.financialserver.crypto.FundDistributionFileResultCrypto;
import io.coti.financialserver.data.*;
import io.coti.financialserver.http.*;
import io.coti.financialserver.http.data.*;
import io.coti.financialserver.model.DailyFundDistributionFiles;
import io.coti.financialserver.model.DailyFundDistributions;
import io.coti.financialserver.model.FailedFundDistributions;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.*;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static io.coti.basenode.http.BaseNodeHttpStringConstants.INVALID_SIGNATURE;
import static io.coti.basenode.http.BaseNodeHttpStringConstants.STATUS_ERROR;
import static io.coti.financialserver.http.HttpStringConstants.*;

@Slf4j
@Service
public class DistributeFundService {

    private static final int NUMBER_OF_DISTRIBUTION_LINE_DETAILS = 7;
    private static final int NUMBER_OF_DISTRIBUTION_SIGNATURE_LINE_DETAILS = 2;
    private static final String DAILY_DISTRIBUTION_RESULT_FILE_PREFIX = "distribution_results_";
    private static final String DAILY_DISTRIBUTION_RESULT_FILE_SUFFIX = ".csv";
    private static final String COMMA_SEPARATOR = ",";

    @Value("${financialserver.seed}")
    private String seed;
    @Value("${kycserver.public.key}")
    private String kycServerPublicKey;
    @Autowired
    TransactionCreationService transactionCreationService;
    @Autowired
    private NodeCryptoHelper nodeCryptoHelper;
    @Autowired
    private BaseNodeBalanceService baseNodeBalanceService;
    @Autowired
    protected INetworkService networkService;
    @Autowired
    private AwsService awsService;
    @Autowired
    private FundDistributionFileCrypto fundDistributionFileCrypto;
    @Autowired
    private FundDistributionFileResultCrypto fundDistributionFileResultCrypto;
    @Autowired
    private DailyFundDistributions dailyFundDistributions;
    @Autowired
    private FailedFundDistributions failedFundDistributions;
    @Autowired
    private DailyFundDistributionFiles dailyFundDistributionFiles;
    private Map<Hash, FundDistributionReservedBalanceData> fundReservedBalanceMap;
    private Map<Hash, ReservedBalanceData> addressToReservedBalanceMap;

    public void initReservedBalance() {
        fundReservedBalanceMap = new ConcurrentHashMap<>();
        addressToReservedBalanceMap = new ConcurrentHashMap<>();
        for (Fund fund : Fund.values()) {
            Hash fundAddress = (fund.getFundHash() == null) ? getFundAddressHash(fund) : fund.getFundHash();
            FundDistributionReservedBalanceData fundDistributionReservedBalanceData = new FundDistributionReservedBalanceData(fund, BigDecimal.ZERO);
            fundReservedBalanceMap.put(fundAddress, fundDistributionReservedBalanceData);
        }
        updateReservedAmountsFromPendingTransactions();
    }

    private void updateReservedAmountsFromPendingTransactions() {
        dailyFundDistributions.forEach(dailyFundDistributionData ->
                dailyFundDistributionData.getFundDistributionEntries().values().forEach(fundDistributionData -> {
                    if (fundDistributionData.isLockingAmount()) {
                        Hash fundAddress = (fundDistributionData.getDistributionPoolFund().getFundHash() == null) ?
                                getFundAddressHash(fundDistributionData.getDistributionPoolFund()) :
                                fundDistributionData.getDistributionPoolFund().getFundHash();
                        FundDistributionReservedBalanceData fundDistributionReservedBalanceData = fundReservedBalanceMap.get(fundAddress);
                        BigDecimal updatedFundLockedAmount = fundDistributionReservedBalanceData.getReservedAmount().add(fundDistributionData.getAmount());
                        fundDistributionReservedBalanceData.setReservedAmount(updatedFundLockedAmount);

                        updateAddressToReservedBalanceMap(fundDistributionData.getReceiverAddress(), fundDistributionData.getAmount());
                    }
                })
        );
    }

    private void updateAddressToReservedBalanceMap(Hash receiverAddress, BigDecimal distributionAmount) {
        ReservedBalanceData reservedBalanceData = addressToReservedBalanceMap.get(receiverAddress);
        if (reservedBalanceData == null) {
            reservedBalanceData = new ReservedBalanceData(BigDecimal.ZERO);
            addressToReservedBalanceMap.put(receiverAddress, reservedBalanceData);
        }
        reservedBalanceData.setReservedAmount(reservedBalanceData.getReservedAmount().add(distributionAmount));
    }

    private Hash getFundAddressHash(Fund fund) {
        Hash fundAddress = fund.getFundHash();
        if (fundAddress == null) {
            fundAddress = nodeCryptoHelper.generateAddress(seed, Math.toIntExact(fund.getReservedAddress().getIndex()));
            fund.setFundHash(fundAddress);
        }
        return fundAddress;
    }

    public ResponseEntity<IResponse> getFundBalances() {
        List<FundDistributionBalanceResultData> fundDistributionBalanceResultDataList = new ArrayList<>();
        fundReservedBalanceMap.values().forEach(fundDistributionReservedBalanceData -> {
            Hash fundAddress = (fundDistributionReservedBalanceData.getFund().getFundHash() == null) ?
                    getFundAddressHash(fundDistributionReservedBalanceData.getFund()) : fundDistributionReservedBalanceData.getFund().getFundHash();
            fundDistributionBalanceResultDataList.add(
                    new FundDistributionBalanceResultData(fundDistributionReservedBalanceData.getFund().getText(),
                            baseNodeBalanceService.getBalanceByAddress(fundAddress),
                            baseNodeBalanceService.getPreBalanceByAddress(fundAddress),
                            fundDistributionReservedBalanceData.getReservedAmount()));
        });
        return ResponseEntity.status(HttpStatus.OK)
                .body(new FundDistributionBalanceResponse(new FundDistributionBalanceResponseData(fundDistributionBalanceResultDataList)));
    }

    public ResponseEntity<IResponse> getReservedBalances(GetReservedBalancesRequest getReservedBalancesRequest) {
        Set<ReservedBalanceResponseData> reservedBalances = new HashSet<>();
        getReservedBalancesRequest.getAddresses().forEach(address -> {
            if (addressToReservedBalanceMap.containsKey(address)) {
                reservedBalances.add(new ReservedBalanceResponseData(address, addressToReservedBalanceMap.get(address).getReservedAmount()));
            }
        });
        return ResponseEntity.status(HttpStatus.OK).body(new GetReservedBalancesResponse(reservedBalances));
    }

    public ResponseEntity<IResponse> distributeFundFromFile(FundDistributionRequest request) {

        Instant now = Instant.now();
        Hash hashOfToday = getHashOfDate(now);

        DailyFundDistributionFileData fundDistributionFileByDayByHash = dailyFundDistributionFiles.getByHash(hashOfToday);
        if (fundDistributionFileByDayByHash != null) {
            return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).body(new Response(DISTRIBUTION_FILE_ALREADY_PROCESSED, STATUS_ERROR));
        }

        List<FundDistributionData> fundDistributionFileDataEntries = new ArrayList<>();
        ResponseEntity<IResponse> distributionFileVerificationResponse = verifyDailyDistributionFile(request, fundDistributionFileDataEntries);
        if (distributionFileVerificationResponse != null) {
            return distributionFileVerificationResponse;
        }

        ResponseEntity<IResponse> responseEntity = updateWithTransactionsEntriesFromVerifiedFile(fundDistributionFileDataEntries);

        if (responseEntity.getStatusCode().equals(HttpStatus.OK)) {
            String fileName = request.getFileName();
            DailyFundDistributionFileData fundDistributionFileOfDay = new DailyFundDistributionFileData(now, fileName);
            dailyFundDistributionFiles.put(fundDistributionFileOfDay);
        }

        return responseEntity;
    }

    public LocalDateTime getStartOfYesterday() {
        return LocalDate.now().minusDays(1).atStartOfDay();
    }

    private ResponseEntity<IResponse> verifyDailyDistributionFile(FundDistributionRequest request, List<FundDistributionData> fundDistributionFileDataEntries) {
        FundDistributionFileData fundDistributionFileData = request.getFundDistributionFileData(new Hash(kycServerPublicKey));
        String fileName = request.getFileName();

        ResponseEntity<IResponse> response = verifyDailyDistributionFileByName(fundDistributionFileDataEntries, fundDistributionFileData, fileName);
        if (response != null) {
            return response;
        }

        return null;
    }

    private ResponseEntity<IResponse> verifyDailyDistributionFileByName(List<FundDistributionData> fundDistributionFileDataEntries, FundDistributionFileData fundDistributionFileData, String fileName) {
        try {
            awsService.downloadFundDistributionFile(fileName);
        } catch (IOException e) {
            log.error(CANT_SAVE_FILE_ON_DISK, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new Response(CANT_SAVE_FILE_ON_DISK, STATUS_ERROR));
        }

        ResponseEntity<IResponse> responseEntityForFileHandling = handleFundDistributionFile(fundDistributionFileData, fileName, fundDistributionFileDataEntries);
        if (responseEntityForFileHandling != null) {
            return responseEntityForFileHandling;
        }
        // Verify signature of Request
        if (fundDistributionFileData.getUserSignature() == null || !fundDistributionFileCrypto.verifySignature(fundDistributionFileData)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new Response(INVALID_SIGNATURE, STATUS_ERROR));
        }
        return null;
    }

    public ResponseEntity<IResponse> handleFundDistributionFile(FundDistributionFileData fundDistributionFileData, String fileName, List<FundDistributionData> fundDistributionEntries) {
        // Parse file and process each line as a new Initial type transaction
        String line = "";
        try (BufferedReader bufferedReader = new BufferedReader(new FileReader(fileName))) {
            while ((line = bufferedReader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty())
                    break;
                String[] distributionDetails;
                distributionDetails = line.split(COMMA_SEPARATOR);
                if (distributionDetails.length != NUMBER_OF_DISTRIBUTION_LINE_DETAILS) {
                    if (distributionDetails.length == NUMBER_OF_DISTRIBUTION_SIGNATURE_LINE_DETAILS) {
                        String rHex = distributionDetails[FundDistributionEntry.SIGNATURE_R.getIndex()];
                        String sHex = distributionDetails[FundDistributionEntry.SIGNATURE_S.getIndex()];
                        fundDistributionFileData.setSignature(new SignatureData(rHex, sHex));
                    } else {
                        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new Response(line, PARSED_WITH_ERROR));
                    }
                } else {
                    FundDistributionData entryData = handleFundDistributionFileLine(fundDistributionFileData, distributionDetails, fileName);
                    if (entryData != null) {
                        fundDistributionEntries.add(entryData);
                    } else {
                        log.error(BAD_CSV_FILE_LINE_FORMAT);
                        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new Response(line, BAD_CSV_FILE_LINE_FORMAT));
                    }
                }
            }
        } catch (Exception e) {
            log.error("Errors on distribution funds service: {}", e);
            return ResponseEntity.status(HttpStatus.EXPECTATION_FAILED).body(new Response(line, BAD_CSV_FILE_LINE_FORMAT));
        }
        return null;
    }

    private FundDistributionData handleFundDistributionFileLine(FundDistributionFileData fundDistributionFileData, String[] distributionDetails, String fileName) {
        try {
            // Load details for new transaction
            long id = Long.parseLong(distributionDetails[FundDistributionEntry.ID.getIndex()]);
            Hash receiverAddress = new Hash(distributionDetails[FundDistributionEntry.RECEIVER_ADDRESS.getIndex()]);
            String distributionPool = distributionDetails[FundDistributionEntry.DISTRIBUTION_POOL.getIndex()];
            BigDecimal amount = new BigDecimal(distributionDetails[FundDistributionEntry.AMOUNT.getIndex()]);
            Instant createTime = Instant.parse(distributionDetails[FundDistributionEntry.CREATION_TIME.getIndex()]);
            Instant transactionTime = Instant.parse(distributionDetails[FundDistributionEntry.RELEASE_TIME.getIndex()]);
            String source = distributionDetails[FundDistributionEntry.SOURCE.getIndex()];

            FundDistributionData entryData =
                    new FundDistributionData(id, receiverAddress, Fund.getFundByText(distributionPool),
                            amount, createTime, transactionTime, source);

            // Update signature message of file according to current line
            byte[] receiverAddressInBytes = receiverAddress.getBytes();
            byte[] distributionPoolInBytes = distributionPool.getBytes();
            byte[] amountInBytes = amount.stripTrailingZeros().toPlainString().getBytes();
            byte[] sourceInBytes = source.getBytes();

            byte[] entryDataInBytes = ByteBuffer.allocate(Long.BYTES + receiverAddressInBytes.length + distributionPoolInBytes.length
                    + amountInBytes.length + Long.BYTES + Long.BYTES + sourceInBytes.length)
                    .putLong(id).put(receiverAddressInBytes).put(distributionPoolInBytes).put(amountInBytes)
                    .putLong(createTime.toEpochMilli()).putLong(transactionTime.toEpochMilli()).put(sourceInBytes)
                    .array();
            fundDistributionFileData.getSignatureMessage().add(entryDataInBytes);
            fundDistributionFileData.incrementMessageByteSize(entryDataInBytes.length);

            entryData.setFileName(fileName);
            return entryData;
        } catch (Exception e) {
            log.error(e.getMessage());
            return null;
        }
    }

    private ResponseEntity<IResponse> updateWithTransactionsEntriesFromVerifiedFile(List<FundDistributionData> fundDistributionFileEntriesData) {
        List<FundDistributionFileEntryResultData> fundDistributionFileEntryResults = new ArrayList<>();
        fundDistributionFileEntriesData.forEach(entryData -> {
                    boolean isAddressValid;
                    boolean isLockupDateValid = false;
                    boolean uniqueByDate = false;
                    boolean passedPreBalanceCheck = false;
                    boolean accepted = (isAddressValid = isAddressValid(entryData)) && (isLockupDateValid = isLockupDateValid(entryData)) && (uniqueByDate = isEntryDataUniquePerDate(entryData)) &&
                            (passedPreBalanceCheck = updateFundAvailableLockedBalances(entryData));

                    if (accepted) {
                        entryData.setStatus(DistributionEntryStatus.ACCEPTED);
                        DailyFundDistributionData fundDistributionOfDay = dailyFundDistributions.getByHash(entryData.getHashByDate());
                        if (fundDistributionOfDay == null) {
                            LinkedHashMap<Hash, FundDistributionData> fundDistributionEntries = new LinkedHashMap<>();
                            fundDistributionOfDay = new DailyFundDistributionData(entryData.getReleaseTime(), fundDistributionEntries);
                        }
                        fundDistributionOfDay.getFundDistributionEntries().put(entryData.getHash(), entryData);
                        dailyFundDistributions.put(fundDistributionOfDay);
                    }
                    String statusByChecks = getTransactionEntryStatusByChecks(isAddressValid, isLockupDateValid, uniqueByDate, passedPreBalanceCheck);
                    fundDistributionFileEntryResults.add(new FundDistributionFileEntryResultData(entryData.getId(), entryData.getReceiverAddress().toString(),
                            entryData.getDistributionPoolFund().getText(), entryData.getSource(), accepted, statusByChecks));
                }
        );
        return ResponseEntity.status(HttpStatus.OK)
                .body(new FundDistributionResponse(new FundDistributionResponseData(fundDistributionFileEntryResults)));
    }

    private String getTransactionEntryStatusByChecks(boolean isAddressValid, boolean isLockupDateValid, boolean uniqueByDate, boolean passedPreBalanceCheck) {
        if (!isAddressValid) {
            return RECEIVER_ADDRESS_INVALID;
        }
        if (!isLockupDateValid) {
            return LOCK_UP_DATE_IS_INVALID;
        }
        if (!uniqueByDate) {
            return DATE_UNIQUENESS_WAS_NOT_MAINTAINED;
        }
        if (!passedPreBalanceCheck) {
            return DISTRIBUTION_POOL_BALANCE_CHECKS_FAILED;
        }
        return ACCEPTED;
    }


    private boolean updateFundAvailableLockedBalances(FundDistributionData entryData) {
        if (entryData.getAmount().compareTo(BigDecimal.ZERO) < 0) {
            return false;
        }
        Hash fundAddress = entryData.getDistributionPoolFund().getFundHash();
        FundDistributionReservedBalanceData fundDistributionReservedBalanceData = fundReservedBalanceMap.get(fundAddress);
        BigDecimal updatedAmountToLock = fundDistributionReservedBalanceData.getReservedAmount().add(entryData.getAmount());
        if (updatedAmountToLock.compareTo(baseNodeBalanceService.getPreBalanceByAddress(fundAddress)) > 0 ||
                updatedAmountToLock.compareTo(baseNodeBalanceService.getBalanceByAddress(fundAddress)) > 0) {   // Not enough money in pre-balance
            return false;
        } else {
            fundDistributionReservedBalanceData.setReservedAmount(updatedAmountToLock);
            updateAddressToReservedBalanceMap(entryData.getReceiverAddress(), entryData.getAmount());
        }
        return true;
    }

    private boolean isEntryDataUniquePerDate(FundDistributionData entryData) {
        //Verify no duplicate source->target transactions are scheduled for the same date
        Instant transactionReleaseDate = entryData.getReleaseTime();
        Hash hashOfDate = getHashOfDate(transactionReleaseDate);
        if (dailyFundDistributions.getByHash(hashOfDate) != null &&
                dailyFundDistributions.getByHash(hashOfDate).getFundDistributionEntries().get(entryData.getHash()) != null)
            return false;
        return true;
    }

    private boolean isAddressValid(FundDistributionData entryData) {
        return CryptoHelper.IsAddressValid(entryData.getReceiverAddress());
    }

    private boolean isLockupDateValid(FundDistributionData entryData) {
        // Verify lock-up date is not null nor prior to yesterday
        Instant transactionReleaseDate = entryData.getReleaseTime();
        return transactionReleaseDate != null && !LocalDateTime.ofInstant(transactionReleaseDate, ZoneOffset.UTC).isBefore(getStartOfYesterday());
    }


    @Scheduled(cron = "${distribution.cron.time}", zone = "UTC")
    public void scheduleTaskUsingCronExpression() {
        log.info("Starting scheduled action for creating pending transactions");
        createPendingTransactions();
        log.info("Finished scheduled action for creating pending transactions");

    }

    private void createPendingTransactions() {
        List<FundDistributionFileEntryResultData> fundDistributionFileEntryResultDataList = new ArrayList<>();
        // Failed transactions should be ran before the non failed ones in order to avoid trying to run them twice
        createPendingFailedTransactions(fundDistributionFileEntryResultDataList);
        createPendingNonFailedTransactionsByDate(fundDistributionFileEntryResultDataList);
        createFundDistributionFileResult(fundDistributionFileEntryResultDataList);
    }

    private void createPendingNonFailedTransactionsByDate(List<FundDistributionFileEntryResultData> fundDistributionFileEntryResultDataList) {
        Hash hashOfYesterday = getHashOfDate(getStartOfYesterday());
        if (dailyFundDistributions.getByHash(hashOfYesterday) == null) {
            return;
        }
        DailyFundDistributionData dailyFundDistributionData = dailyFundDistributions.getByHash(hashOfYesterday);
        LinkedHashMap<Hash, FundDistributionData> fundDistributionEntries = dailyFundDistributionData.getFundDistributionEntries();
        for (FundDistributionData fundDistributionData : fundDistributionEntries.values()) {
            boolean isSuccessful = false;
            Hash initialTransactionHash = null;
            // Create a new Initial transaction if status allows it
            if (fundDistributionData.isReadyToInitiate()) {
                initialTransactionHash = createInitialTransactionToDistributionEntry(fundDistributionData);
                if (initialTransactionHash != null) {
                    // Update DB with new transaction
                    isSuccessful = true;
                    fundDistributionData.setStatus(DistributionEntryStatus.CREATED);
                    fundDistributionEntries.put(fundDistributionData.getHash(), fundDistributionData);
                    updateReservedBalanceAfterTransactionCreated(fundDistributionData);
                } else {
                    fundDistributionData.setStatus(DistributionEntryStatus.FAILED);
                    fundDistributionEntries.put(fundDistributionData.getHash(), fundDistributionData);
                    FailedFundDistributionData failedFundDistributionData = failedFundDistributions.getByHash(hashOfYesterday);
                    if (failedFundDistributionData == null) {
                        failedFundDistributionData = new FailedFundDistributionData(hashOfYesterday);
                    }
                    failedFundDistributionData.getFundDistributionHashes().put(fundDistributionData.getHash(), fundDistributionData.getHash());
                    failedFundDistributions.put(failedFundDistributionData);
                }
                dailyFundDistributions.put(dailyFundDistributionData);
                String status = isSuccessful ? TRANSACTION_CREATED_SUCCESSFULLY : TRANSACTION_CREATION_FAILED;
                FundDistributionFileEntryResultData fundDistributionFileEntryResultData = new FundDistributionFileEntryResultData(fundDistributionData.getId(),
                        fundDistributionData.getReceiverAddress().toString(), fundDistributionData.getDistributionPoolFund().getText(),
                        fundDistributionData.getSource(), isSuccessful, status);
                if (initialTransactionHash != null) {
                    fundDistributionFileEntryResultData.setTransactionHash(initialTransactionHash.toString());
                }
                fundDistributionFileEntryResultDataList.add(fundDistributionFileEntryResultData);

            }
        }
    }

    private void updateReservedBalanceAfterTransactionCreated(FundDistributionData fundDistributionData) {

        FundDistributionReservedBalanceData fundReserveBalanceData = fundReservedBalanceMap.get(fundDistributionData.getDistributionPoolFund().getFundHash());
        BigDecimal updatedFundReservedAmount = fundReserveBalanceData.getReservedAmount().subtract(fundDistributionData.getAmount());
        if (updatedFundReservedAmount.compareTo(BigDecimal.ZERO) < 0) {
            log.error("Fund reserved amount can not be negative.");
            updatedFundReservedAmount = BigDecimal.ZERO;
        }
        fundReserveBalanceData.setReservedAmount(updatedFundReservedAmount);

        Hash receiverAddress = fundDistributionData.getReceiverAddress();
        ReservedBalanceData reservedBalanceData = addressToReservedBalanceMap.get(receiverAddress);
        if (reservedBalanceData == null) {
            log.error("Receiver reserved balance doesn't exist");
            return;
        }
        BigDecimal updatedReservedAmount = reservedBalanceData.getReservedAmount().subtract(fundDistributionData.getAmount());
        if (updatedReservedAmount.compareTo(BigDecimal.ZERO) <= 0) {
            addressToReservedBalanceMap.remove(receiverAddress);
            return;
        }
        reservedBalanceData.setReservedAmount(updatedReservedAmount);
    }

    private Hash getHashOfDate(Instant dayInstant) {
        return getHashOfDate(LocalDateTime.ofInstant(dayInstant, ZoneOffset.UTC));
    }

    private Hash getHashOfDate(LocalDateTime localDateTime) {
        return CryptoHelper.cryptoHash((localDateTime.getYear() + localDateTime.getMonth().toString() +
                localDateTime.getDayOfMonth()).getBytes());
    }

    private void createPendingFailedTransactions(List<FundDistributionFileEntryResultData> fundDistributionFileEntryResultDataList) {
        failedFundDistributions.forEach(failedFundDistributionData ->
        {
            Hash hashOfDay = failedFundDistributionData.getHash();
            DailyFundDistributionData dailyFundDistributionData = dailyFundDistributions.getByHash(hashOfDay);
            Iterator<Hash> failedEntryHashKeys = failedFundDistributionData.getFundDistributionHashes().keySet().iterator();
            while (failedEntryHashKeys.hasNext()) {
                Hash failedFundDistributionHash = failedEntryHashKeys.next();
                Hash initialTransactionHash;
                boolean isSuccessful = false;
                FundDistributionData fundDistributionData = dailyFundDistributionData.getFundDistributionEntries().get(failedFundDistributionHash);
                if (fundDistributionData.getStatus().equals(DistributionEntryStatus.FAILED)) {
                    initialTransactionHash = createInitialTransactionToDistributionEntry(fundDistributionData);
                    if (initialTransactionHash != null) {
                        // Update DB with new transaction
                        isSuccessful = true;
                        fundDistributionData.setStatus(DistributionEntryStatus.CREATED);
                        failedEntryHashKeys.remove();
                        // Update reserved balance, once transaction is created
                        updateReservedBalanceAfterTransactionCreated(fundDistributionData);
                    }
                    String status = isSuccessful ? TRANSACTION_CREATED_SUCCESSFULLY : TRANSACTION_CREATION_FAILED;
                    FundDistributionFileEntryResultData fundDistributionFileEntryResultData = new FundDistributionFileEntryResultData(fundDistributionData.getId(), fundDistributionData.getReceiverAddress().toString(),
                            fundDistributionData.getDistributionPoolFund().getText(), fundDistributionData.getSource(), isSuccessful, status);
                    if (initialTransactionHash != null) {
                        fundDistributionFileEntryResultData.setTransactionHash(initialTransactionHash.toString());
                    }
                    fundDistributionFileEntryResultDataList.add(fundDistributionFileEntryResultData);
                } else {
                    failedEntryHashKeys.remove();
                }
            }
            dailyFundDistributions.put(dailyFundDistributionData);
            failedFundDistributions.put(failedFundDistributionData);
        });
    }

    private String createDistributionResultFileNameForToday() {
        LocalDateTime ldt = LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
        String today = ldt.getYear() + "-" + StringUtils.leftPad(Integer.toString(ldt.getMonthValue()), 2, "0") + "-" + StringUtils.leftPad(Integer.toString(ldt.getDayOfMonth()), 2, "0");
        return DAILY_DISTRIBUTION_RESULT_FILE_PREFIX + today + DAILY_DISTRIBUTION_RESULT_FILE_SUFFIX;
    }

    private Hash getEntryResultSourceFundAddress(FundDistributionFileEntryResultData entryResult) {
        int sourceAddressIndex = Math.toIntExact(Fund.getFundByText(entryResult.getDistributionPool()).getReservedAddress().getIndex());
        return nodeCryptoHelper.generateAddress(seed, sourceAddressIndex);
    }

    private Hash createInitialTransactionToDistributionEntry(FundDistributionData fundDistributionData) {
        Hash initialTransactionHash = null;
        try {
            int sourceAddressIndex = Math.toIntExact(fundDistributionData.getDistributionPoolFund().getReservedAddress().getIndex());
            Hash sourceAddress = fundDistributionData.getDistributionPoolFund().getFundHash();
            initialTransactionHash = transactionCreationService.createInitialTransactionToFund(fundDistributionData.getAmount(),
                    sourceAddress, fundDistributionData.getReceiverAddress(), sourceAddressIndex);
        } catch (Exception e) {
            log.error("Failed to create transaction ", e);
        }
        return initialTransactionHash;
    }

    private void createFundDistributionFileResult(List<FundDistributionFileEntryResultData> fundDistributionFileEntryResultDataList) {
        // Create results file locally according to given fileNameForToday, Sign file and upload it to S3
        String resultsFileNameForToday = createDistributionResultFileNameForToday();
        File file = new File(resultsFileNameForToday);
        // Create results file based on fundDistributionFileEntryResultDataList including signature
        FundDistributionFileResultData fundDistributionFileResultData = new FundDistributionFileResultData();
        try (Writer fileWriter = new FileWriter(resultsFileNameForToday, false)) {
            for (FundDistributionFileEntryResultData entryResult : fundDistributionFileEntryResultDataList) {
                fileWriter.write(getEntryResultAsCommaDelimitedLine(entryResult));
                updateFundDistributionFileResultData(fundDistributionFileResultData, entryResult);
            }
            fundDistributionFileResultData.setFinancialServerHash(NodeCryptoHelper.getNodeHash());
            fundDistributionFileResultCrypto.signMessage(fundDistributionFileResultData);
            SignatureData signature = fundDistributionFileResultCrypto.getSignature(fundDistributionFileResultData);
            fileWriter.write(signature.getR() + COMMA_SEPARATOR + signature.getS()
                    + COMMA_SEPARATOR + fundDistributionFileResultData.getFinancialServerHash());
        } catch (IOException e) {
            log.error(CANT_SAVE_FILE_ON_DISK, e);
            return;
        }

        awsService.uploadFundDistributionResultFile(resultsFileNameForToday, file, "application/vnd.ms-excel");
    }

    private String getEntryResultAsCommaDelimitedLine(FundDistributionFileEntryResultData entryResult) {
        return Long.toString(entryResult.getId()) + COMMA_SEPARATOR + entryResult.getDistributionPool() + COMMA_SEPARATOR +
                entryResult.getSource() + COMMA_SEPARATOR + getEntryResultSourceFundAddress(entryResult).toString() + COMMA_SEPARATOR +
                entryResult.getReceiverAddress() + COMMA_SEPARATOR + ((Boolean) entryResult.isAccepted()).toString() + COMMA_SEPARATOR +
                entryResult.getStatus() + COMMA_SEPARATOR + entryResult.getTransactionHash() + "\n";
    }

    private void updateFundDistributionFileResultData(FundDistributionFileResultData fundDistributionFileResultData, FundDistributionFileEntryResultData entryResult) {
        byte[] distributionPoolNameInBytes = entryResult.getDistributionPool().getBytes();
        byte[] sourceInBytes = entryResult.getSource().getBytes();
        byte[] distributionPoolAddressInBytes = entryResult.getReceiverAddress().getBytes();
        byte[] receiverAddressInBytes = new Hash(entryResult.getReceiverAddress()).getBytes();
        byte[] isAcceptedInBytes = ((Boolean) entryResult.isAccepted()).toString().getBytes();
        byte[] statusInBytes = entryResult.getStatus().getBytes();
        byte[] transactionHashInBytes = entryResult.getTransactionHash() != null ? new Hash(entryResult.getTransactionHash()).getBytes() : new byte[0];

        byte[] resultLineInBytes = ByteBuffer.allocate(Long.BYTES + distributionPoolNameInBytes.length + sourceInBytes.length + distributionPoolAddressInBytes.length +
                receiverAddressInBytes.length + isAcceptedInBytes.length + statusInBytes.length + transactionHashInBytes.length).
                putLong(entryResult.getId()).put(distributionPoolNameInBytes).put(sourceInBytes).put(distributionPoolAddressInBytes).put(receiverAddressInBytes).
                put(statusInBytes).put(transactionHashInBytes).array();

        fundDistributionFileResultData.getSignatureMessage().add(resultLineInBytes);
        fundDistributionFileResultData.incrementMessageByteSize(resultLineInBytes.length);
    }


}
