package com.bgeforce.tv;

import android.content.*;
import android.security.keystore.*;
import android.util.Base64;
import java.security.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** Account tokens never enter catalog caches, intents, logs or the WebView bridge. */
public final class SecureStore implements AccountStorage {
  private static final String ALIAS = "bgef-account-v1";
  private final SharedPreferences p;

  public SecureStore(Context c) {
    p = c.getSharedPreferences("account", Context.MODE_PRIVATE);
  }

  private javax.crypto.SecretKey key() throws Exception {
    KeyStore k = KeyStore.getInstance("AndroidKeyStore");
    k.load(null);
    if (!k.containsAlias(ALIAS)) {
      KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
      g.init(
          new KeyGenParameterSpec.Builder(
                  ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
              .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
              .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
              .build());
      g.generateKey();
    }
    return (javax.crypto.SecretKey) k.getKey(ALIAS, null);
  }

  public synchronized JSONObject read() throws Exception {
    String value = p.getString("sealed", "");
    if (value.isEmpty()) return null;
    String[] bits = value.split(":", 2);
    if (bits.length != 2) throw new GeneralSecurityException("Invalid account storage");
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        Cipher.DECRYPT_MODE,
        key(),
        new GCMParameterSpec(128, Base64.decode(bits[0], Base64.NO_WRAP)));
    return Json.parse(
        new String(
            cipher.doFinal(Base64.decode(bits[1], Base64.NO_WRAP)),
            java.nio.charset.StandardCharsets.UTF_8));
  }

  public synchronized void write(JSONObject j) throws Exception {
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, key());
    String v =
        Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)
            + ":"
            + Base64.encodeToString(
                cipher.doFinal(j.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                Base64.NO_WRAP);
    if (!p.edit().putString("sealed", v).commit())
      throw new java.io.IOException("Account storage failed");
  }

  public synchronized String deviceId() {
    String s = p.getString("device", "");
    if (s.isEmpty()) {
      s = java.util.UUID.randomUUID().toString();
      p.edit().putString("device", s).commit();
    }
    return s;
  }

  public synchronized void clear() {
    p.edit().remove("sealed").commit();
  }
}
