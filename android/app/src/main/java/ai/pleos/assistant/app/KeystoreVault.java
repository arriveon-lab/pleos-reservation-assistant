package ai.pleos.assistant.app;

import ai.pleos.assistant.ux.AuthSession;
import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import android.util.Base64;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** Non-exportable key; atomic ciphertext in noBackupFilesDir. Never replaces a missing key. */
final class KeystoreVault implements AuthSession.Vault {
  private final String alias;
  private final AtomicFile file;
  private final SharedPreferences marker;
  private final KeyStore keys;

  KeystoreVault(Context context) throws Exception {
    this(context, "pleos-auth-v1", "auth-session.enc", "auth-marker");
  }

  KeystoreVault(Context context, String alias, String filename, String markerName)
      throws Exception {
    this.alias = alias;
    file = new AtomicFile(new File(context.getNoBackupFilesDir(), filename));
    marker = context.getSharedPreferences(markerName, Context.MODE_PRIVATE);
    keys = KeyStore.getInstance("AndroidKeyStore");
    keys.load(null);
  }

  @Override
  public synchronized String read() throws Exception {
    byte[] bytes;
    try {
      bytes = file.readFully();
    } catch (FileNotFoundException missing) {
      if (marker.getBoolean("initialized", false) || keys.containsAlias(alias))
        throw new IllegalStateException("AUTH_STORAGE_MISSING");
      return null;
    }
    if (!keys.containsAlias(alias)) throw new IllegalStateException("AUTH_KEY_MISSING");
    JSONObject envelope = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
    if (envelope.getInt("version") != 1) throw new IllegalStateException("AUTH_STORAGE_INVALID");
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        Cipher.DECRYPT_MODE,
        keys.getKey(alias, null),
        new GCMParameterSpec(128, Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)));
    cipher.updateAAD(alias.getBytes(StandardCharsets.UTF_8));
    return new String(
        cipher.doFinal(Base64.decode(envelope.getString("data"), Base64.NO_WRAP)),
        StandardCharsets.UTF_8);
  }

  @Override
  public synchronized void save(String value) throws Exception {
    if (!keys.containsAlias(alias)) {
      if (marker.getBoolean("initialized", false) || file.getBaseFile().exists())
        throw new IllegalStateException("AUTH_KEY_MISSING");
      KeyGenerator generator =
          KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
      generator.init(
          new KeyGenParameterSpec.Builder(
                  alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
              .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
              .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
              .setKeySize(256)
              .build());
      generator.generateKey();
    }
    if (!marker.edit().putBoolean("initialized", true).commit())
      throw new IllegalStateException("AUTH_STORAGE_FAILED");
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, (SecretKey) keys.getKey(alias, null));
    cipher.updateAAD(alias.getBytes(StandardCharsets.UTF_8));
    byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
    byte[] envelope =
        new JSONObject()
            .put("version", 1)
            .put("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
            .put("data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .toString()
            .getBytes(StandardCharsets.UTF_8);
    FileOutputStream output = null;
    try {
      output = file.startWrite();
      output.write(envelope);
      file.finishWrite(output);
      output = null;
      if (!java.util.Arrays.equals(envelope, file.readFully()))
        throw new IllegalStateException("AUTH_STORAGE_FAILED");
    } catch (Exception failure) {
      if (output != null) file.failWrite(output);
      throw new IllegalStateException("AUTH_STORAGE_FAILED");
    }
  }
}
