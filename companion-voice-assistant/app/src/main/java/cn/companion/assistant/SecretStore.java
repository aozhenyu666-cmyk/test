package cn.companion.assistant;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class SecretStore {
    private static final String ALIAS = "companion-api-token";
    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            gen.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            return gen.generateKey();
        }
        return (SecretKey) ks.getKey(ALIAS, null);
    }
    static void save(Context c, String token) throws Exception {
        if (token.isBlank()) { c.getSharedPreferences("secrets", 0).edit().clear().apply(); return; }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
        String value = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipher.doFinal(token.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        c.getSharedPreferences("secrets", 0).edit().putString("token", value).apply();
    }
    static String read(Context c) throws Exception {
        String value = c.getSharedPreferences("secrets", 0).getString("token", "");
        if (value.isEmpty()) return "";
        String[] p = value.split(":", 2);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(p[0], Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(p[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }
}
