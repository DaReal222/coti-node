package io.coti.trustscore.http;

import io.coti.basenode.http.BaseResponse;
import io.coti.trustscore.data.TrustScoreData;
import io.coti.trustscore.data.UserTrustScoreData;
import lombok.Data;

@Data
public class SetKycTrustScoreResponse extends BaseResponse {
    private String userHash;
    private double kycTrustScore;

    public SetKycTrustScoreResponse(String userHash, double kycTrustScore) {
        super();
        this.userHash = userHash;
        this.kycTrustScore = kycTrustScore;

    }
}
