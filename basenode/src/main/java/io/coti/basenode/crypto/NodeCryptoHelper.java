package io.coti.basenode.crypto;

import io.coti.basenode.data.Hash;
import io.coti.basenode.data.SignatureData;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class NodeCryptoHelper {

    private static String nodePrivateKey;
    private static String nodePublicKey;
    private static String seed;

    @Value("#{'${global.private.key}'}")
    private void nodePrivateKey(String privateKey) {
        nodePrivateKey = privateKey;
        nodePublicKey = CryptoHelper.GetPublicKeyFromPrivateKey(nodePrivateKey);
    }

    public static SignatureData signMessage(byte[] message) {
        return CryptoHelper.SignBytes(message, nodePrivateKey);
    }

    public static SignatureData signMessage(byte[] message, Integer index) {
        return CryptoHelper.SignBytes(message, CryptoHelper.generatePrivateKey(seed, index).toHexString());
    }

    public Hash generateAddress(String seed, Integer index) {
        if (this.seed == null) {
            this.seed = seed;
        }
        this.seed = seed;
        return CryptoHelper.generateAddress(seed, index);
    }

    public static Hash getNodeHash() {
        return new Hash(nodePublicKey);
    }

    public static Hash getNodeAddress() {
        return CryptoHelper.getAddressFromPrivateKey(nodePrivateKey);
    }
}