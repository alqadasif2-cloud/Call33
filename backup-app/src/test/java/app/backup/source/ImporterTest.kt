package app.backup.source

import org.junit.Assert.assertEquals
import org.junit.Test

class ImporterTest {
    @Test fun callsCsvParsesArabicAndSkipsInvalidRows() {
        val csv = "number,type,date,duration,name\n" +
            "+966500000001,incoming,2024-05-01T10:00:00Z,35,\"أحمد، العمل\"\n" +
            "+966500000002,3,1714557600000,0,\n" +
            "+966500000003,outgoing,not-a-date,10,x\n"
        val out = Importer.callsFromCsv("dev", csv)
        assertEquals(2, out.records.size)
        assertEquals(1, out.skipped)
        assertEquals("أحمد، العمل", out.records[0].fields.getString("name"))
        assertEquals("missed", out.records[1].fields.getString("type"))
    }

    @Test fun duplicateRowsProduceSameId() {
        val csv = "number,type,date,duration\n123,incoming,1714557600000,5\n123,incoming,1714557600000,5\n"
        val ids = Importer.callsFromCsv("dev", csv).records.map { it.id }.toSet()
        assertEquals(1, ids.size)
    }

    @Test fun smsCsvRequiresAddressAndDate() {
        val csv = "address,body,date,type\n+1555,\"مرحبا\nسطر ثان\",1714557600000,inbox\n,بدون عنوان,1714557600000,sent\n"
        val out = Importer.smsFromCsv("dev", csv)
        assertEquals(1, out.records.size)
        assertEquals(1, out.skipped)
        assertEquals("مرحبا\nسطر ثان", out.records[0].fields.getString("body"))
    }
}
