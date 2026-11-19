package com.ndspack.generator;

import com.android.apksig.ApkSigner;

import java.io.File;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;

/** Assina o APK (esquemas v1, v2 e v3) com a chave embutida no gerador. */
public final class ApkSign {
    private static final char[] PASSWORD = "ndspack".toCharArray();

    private ApkSign() {}

    public static void sign(InputStream keystore, File in, File out) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(keystore, PASSWORD);
        String alias = ks.aliases().nextElement();
        PrivateKey key = (PrivateKey) ks.getKey(alias, PASSWORD);
        X509Certificate cert = (X509Certificate) ks.getCertificate(alias);
        ApkSigner.SignerConfig cfg =
                new ApkSigner.SignerConfig.Builder("ndspack", key, Collections.singletonList(cert)).build();
        new ApkSigner.Builder(Collections.singletonList(cfg))
                .setInputApk(in)
                .setOutputApk(out)
                .setMinSdkVersion(26)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setCreatedBy("NDS to APK")
                .build()
                .sign();
    }
}
