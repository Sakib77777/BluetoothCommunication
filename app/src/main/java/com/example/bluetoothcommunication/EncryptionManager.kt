package com.example.bluetoothcommunication

// ─────────────────────────────────────────────────────────────────────────────
// FILE LOCATION:
// app/src/main/java/com/example/bluetoothcommunication/EncryptionManager.kt
// ─────────────────────────────────────────────────────────────────────────────

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// ─────────────────────────────────────────────────────────────────────────────
// ENCRYPTION MANAGER
// Handles AES-GCM end-to-end encryption for all messages
//
// HOW IT WORKS:
// 1. Both devices derive a shared key from their full usernames (e.g. "Sakib#A3F7")
// 2. Sender encrypts message with key → sends encrypted bytes over BLE
// 3. Receiver decrypts bytes with same key → reads original message
//
// KEY DERIVATION (no key exchange needed over BLE!):
//   Phone A username: "Sakib#A3F7"
//   Phone B username: "Rahul#B9K2"
//   Key = SHA-256(sorted("Sakib#A3F7", "Rahul#B9K2") joined)
//       = SHA-256("Rahul#B9K2Sakib#A3F7")   ← alphabetically sorted
//
//   Phone B computes:
//   Key = SHA-256(sorted("Rahul#B9K2", "Sakib#A3F7") joined)
//       = SHA-256("Rahul#B9K2Sakib#A3F7")   ← same result!
//
// The sort guarantees both sides produce identical key material even though
// each phone passes the arguments in a different order.
//
// For mesh relay: relay nodes (Phone C) forward the encrypted content without
// decrypting — they don't have the key and don't need it.
// ─────────────────────────────────────────────────────────────────────────────
object EncryptionManager {

    private const val ALGORITHM  = "AES/GCM/NoPadding"
    private const val KEY_SIZE   = 256  // 256-bit AES key
    private const val IV_SIZE    = 12   // 12 bytes IV for GCM
    private const val TAG_LENGTH = 128  // 128-bit auth tag

    // ─────────────────────────────────────────────────────────────────────────
    // GENERATE KEY
    // Creates a new random 256-bit AES encryption key
    // ─────────────────────────────────────────────────────────────────────────
    fun generateKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance("AES")
        keyGenerator.init(KEY_SIZE)
        return keyGenerator.generateKey()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // KEY TO STRING / STRING TO KEY
    // Converts key to Base64 string for easy storage
    // ─────────────────────────────────────────────────────────────────────────
    fun keyToString(key: SecretKey): String =
        Base64.encodeToString(key.encoded, Base64.NO_WRAP)

    fun stringToKey(keyString: String): SecretKey {
        val keyBytes = Base64.decode(keyString, Base64.NO_WRAP)
        return SecretKeySpec(keyBytes, "AES")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENCRYPT MESSAGE
    // Takes plain text → returns encrypted Base64 string
    // Format: Base64(IV[12 bytes] + AES-GCM ciphertext)
    // ─────────────────────────────────────────────────────────────────────────
    fun encrypt(plainText: String, key: SecretKey): String {
        val cipher = Cipher.getInstance(ALGORITHM)

        // Random IV — unique per message, sent alongside ciphertext
        val iv = ByteArray(IV_SIZE)
        java.security.SecureRandom().nextBytes(iv)

        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH, iv))
        val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        // Prepend IV so receiver can extract it for decryption
        return Base64.encodeToString(iv + encryptedBytes, Base64.NO_WRAP)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DECRYPT MESSAGE
    // Takes encrypted Base64 string → returns original plain text
    // ─────────────────────────────────────────────────────────────────────────
    fun decrypt(encryptedText: String, key: SecretKey): String {
        val combined       = Base64.decode(encryptedText, Base64.NO_WRAP)
        val iv             = combined.copyOfRange(0, IV_SIZE)
        val encryptedBytes = combined.copyOfRange(IV_SIZE, combined.size)

        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH, iv))

        return String(cipher.doFinal(encryptedBytes), Charsets.UTF_8)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SAFE DECRYPT
    // Returns null if decryption fails (wrong key, corrupted data, relay
    // message not meant for this device, etc.)
    // ─────────────────────────────────────────────────────────────────────────
    fun safeDecrypt(encryptedText: String, key: SecretKey): String? =
        runCatching { decrypt(encryptedText, key) }.getOrNull()

    // ─────────────────────────────────────────────────────────────────────────
    // GENERATE SHARED KEY FROM FULL USERNAMES
    //
    // Pass the full "DisplayName#TAG" username strings for both users.
    // Both phones will derive the identical key because the list is sorted
    // before hashing — order of arguments does not matter.
    //
    // Usage:
    //   val key = EncryptionManager.generateSharedKey(myFullName, contactFullName)
    //   // myFullName     = "Sakib#A3F7"  (from SharedPreferences "username")
    //   // contactFullName = "Rahul#B9K2" (from intent "contactFullName")
    // ─────────────────────────────────────────────────────────────────────────
    fun generateSharedKey(myFullUsername: String, theirFullUsername: String): SecretKey {
        val combined = listOf(myFullUsername, theirFullUsername)
            .sorted()
            .joinToString("")

        val keyBytes = java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(combined.toByteArray(Charsets.UTF_8))

        return SecretKeySpec(keyBytes, "AES")
    }
}
