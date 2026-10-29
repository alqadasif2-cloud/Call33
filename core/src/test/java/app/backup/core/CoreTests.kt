package app.backup.core

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CryptoTest {
    private val salt = ByteArray(16) { it.toByte() }

    @Test fun roundTripWithArabicText() {
        val key = Crypto.deriveKey("عبارة-مرور-قوية".toCharArray(), salt, 1000)
        val plain = "مرحبا، هذه رسالة تجريبية 123".toByteArray()
        val box = Crypto.encrypt(key, plain, "a".toByteArray())
        assertArrayEquals(plain, Crypto.decrypt(key, box.iv, box.data, "a".toByteArray()))
    }

    @Test fun wrongKeyFails() {
        val k1 = Crypto.deriveKey("one-passphrase".toCharArray(), salt, 1000)
        val k2 = Crypto.deriveKey("two-passphrase".toCharArray(), salt, 1000)
        val box = Crypto.encrypt(k1, "x".toByteArray(), "a".toByteArray())
        try { Crypto.decrypt(k2, box.iv, box.data, "a".toByteArray()); fail() } catch (_: Exception) {}
    }

    @Test fun tamperedAadFails() {
        val k = Crypto.deriveKey("one-passphrase".toCharArray(), salt, 1000)
        val box = Crypto.encrypt(k, "x".toByteArray(), "id1".toByteArray())
        try { Crypto.decrypt(k, box.iv, box.data, "id2".toByteArray()); fail() } catch (_: Exception) {}
    }

    @Test fun ivIsRandom() {
        val k = Crypto.deriveKey("one-passphrase".toCharArray(), salt, 1000)
        val a = Crypto.encrypt(k, "x".toByteArray(), "a".toByteArray())
        val b = Crypto.encrypt(k, "x".toByteArray(), "a".toByteArray())
        assertFalse(a.iv.contentEquals(b.iv))
    }
}

class RecordCodecTest {
    private val key = Crypto.deriveKey("one-passphrase".toCharArray(), ByteArray(16), 1000)

    @Test fun encryptDecryptRecord() {
        val f = JSONObject().put("address", "+966500000000").put("body", "السلام عليكم").put("type", "inbox")
        val id = RecordIds.make("dev", RecordType.SMS, "+966500000000", 5L, "inbox", "السلام عليكم")
        val enc = RecordCodec.encrypt(key, PlainRecord(id, "dev", RecordType.SMS, 5L, f))
        assertFalse(enc.data.contains("السلام"))
        val dec = RecordCodec.decrypt(key, "dev", enc)
        assertEquals("السلام عليكم", dec.fields.getString("body"))
        assertEquals(5L, dec.ts)
    }

    @Test fun changingTimestampBreaksDecryption() {
        val id = RecordIds.make("dev", RecordType.CLIP, "t")
        val enc = RecordCodec.encrypt(key, PlainRecord(id, "dev", RecordType.CLIP, 10L, JSONObject().put("text", "t")))
        try { RecordCodec.decrypt(key, "dev", enc.copy(ts = 11L)); fail() } catch (_: Exception) {}
    }

    @Test fun idsAreDeterministicAndDeviceScoped() {
        val a = RecordIds.make("d1", RecordType.CALL, "123", 1L)
        assertEquals(a, RecordIds.make("d1", RecordType.CALL, "123", 1L))
        assertNotEquals(a, RecordIds.make("d2", RecordType.CALL, "123", 1L))
        assertNotEquals(a, RecordIds.make("d1", RecordType.CALL, "123", 2L))
    }
}

class CsvTest {
    @Test fun roundTripSpecialCharacters() {
        val cells = listOf("a,b", "سطر\nثانٍ", "اقتباس \"نص\"", "", "عادي")
        val parsed = Csv.parse(Csv.row(cells) + "\r\n" + Csv.row(listOf("1", "2", "3", "4", "5")) + "\r\n")
        assertEquals(2, parsed.size)
        assertEquals(cells, parsed[0])
    }

    @Test fun bomIsIgnored() {
        assertEquals(listOf(listOf("x", "y")), Csv.parse("\uFEFFx,y\n"))
    }
}

class SensitiveTest {
    @Test fun blocksOtpPasswordsAndCards() {
        assertTrue(Sensitive.looksSensitive("123456"))
        assertTrue(Sensitive.looksSensitive("رمز التحقق الخاص بك 4821"))
        assertTrue(Sensitive.looksSensitive("my password is abc"))
        assertTrue(Sensitive.looksSensitive("4111 1111 1111 1111"))
        assertTrue(Sensitive.looksSensitive("٤٨٢١٣٩"))
    }

    @Test fun allowsOrdinaryText() {
        assertFalse(Sensitive.looksSensitive("عنوان المنزل: شارع الملك فهد"))
        assertFalse(Sensitive.looksSensitive("https://example.com/page"))
        assertFalse(Sensitive.looksSensitive("1234567890123456")) // طول بطاقة لكن Luhn خاطئ
    }
}

class TimeFmtTest {
    @Test fun parsesCommonFormats() {
        assertEquals(1_700_000_000_000L, TimeFmt.parse("1700000000000"))
        assertEquals(1_700_000_000_000L, TimeFmt.parse("1700000000"))
        assertEquals(1_700_000_000_000L, TimeFmt.parse("2023-11-14T22:13:20Z"))
        assertNotNull(TimeFmt.parse("2023-11-14 22:13:20"))
        assertNull(TimeFmt.parse("not a date"))
    }
}

class UsernameAuthTest {
    @Test fun normalizesUsernameDeterministicallyAcrossApps() {
        val a = UsernameAuth.normalizeUsername("  sami123  ")
        val b = UsernameAuth.normalizeUsername("SAMI123")
        assertEquals("sami123", a.username)
        assertEquals("sami123", a.usernameLower)
        assertEquals(40, a.usernameId.length)
        assertEquals(a.usernameId, b.usernameId)
        assertEquals(a.syntheticEmail, b.syntheticEmail)
        assertEquals("MyBackup2026!", UsernameAuth.validatePassword("MyBackup2026!"))

        val uidBackupApp = Crypto.deriveCapabilityUid(a.usernameId, "MyBackup2026!", iterations = 2000)
        val uidViewerApp = Crypto.deriveCapabilityUid(b.usernameId, "MyBackup2026!", iterations = 2000)
        val uidWrongPw = Crypto.deriveCapabilityUid(a.usernameId, "WrongPass2026!", iterations = 2000)
        assertEquals(50, uidBackupApp.length)
        assertTrue(uidBackupApp.startsWith("v_"))
        assertEquals(uidBackupApp, uidViewerApp)
        assertNotEquals(uidBackupApp, uidWrongPw)
    }

    @Test fun rejectsInvalidUsernamesAndShortPasswords() {
        try { UsernameAuth.normalizeUsername("ab"); fail() } catch (_: UsernameAuthException) {}
        try { UsernameAuth.normalizeUsername("sami 123"); fail() } catch (_: UsernameAuthException) {}
        try { UsernameAuth.validatePassword("1234567"); fail() } catch (_: UsernameAuthException) {}
    }
}


