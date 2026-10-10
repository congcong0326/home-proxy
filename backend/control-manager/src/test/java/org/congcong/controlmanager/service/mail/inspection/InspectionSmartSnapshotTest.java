package org.congcong.controlmanager.service.mail.inspection;

import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import static org.junit.jupiter.api.Assertions.*;

class InspectionSmartSnapshotTest {
    @Test
    void distinguishesMissingFieldsFromRealZeroAndPreservesNvmeUnits() {
        var missing = InspectionSmartSnapshot.parse("Serial Number: SN", "NVME_SSD");
        assertNull(missing.lifeUsed()); assertNull(missing.temperature());
        assertNull(missing.errors().get("媒体与数据完整性错误"));
        var present = InspectionSmartSnapshot.parse("""
                Serial Number: SN
                SMART overall-health self-assessment test result: PASSED
                Temperature: 36 Celsius
                Percentage Used: 0%
                Critical Warning: 0x00
                Unsafe Shutdowns: 0
                Media and Data Integrity Errors: 0
                Data Units Written: 18,446,744,073,709,551,615
                """, "NVME_SSD");
        assertEquals(0, present.lifeUsed()); assertEquals(BigInteger.ZERO, present.criticalWarning());
        assertEquals(BigInteger.ZERO, present.errors().get("异常断电次数"));
        assertEquals(new BigInteger("18446744073709551615").multiply(BigInteger.valueOf(512000)), present.writtenBytes());
    }

    @Test
    void readsHddPendingSectorsWithoutCallingRetractsUnsafeShutdowns() {
        var value = InspectionSmartSnapshot.parse("""
                192 Power-Off_Retract_Count 0x0032 100 100 000 Old_age Always - 505
                194 Temperature_Celsius 0x0002 171 171 000 Old_age Always - 35 (Min/Max 23/51)
                197 Current_Pending_Sector 0x0022 100 100 000 Old_age Always - 2
                198 Offline_Uncorrectable 0x0008 100 100 000 Old_age Offline - 0
                199 UDMA_CRC_Error_Count 0x000a 200 200 000 Old_age Always - 7
                """, "HDD");
        assertEquals(35, value.temperature()); assertEquals(BigInteger.valueOf(2), value.errors().get("待处理扇区"));
        assertEquals(BigInteger.ZERO, value.errors().get("不可修复扇区"));
        assertFalse(value.errors().containsKey("异常断电次数"));
        assertNull(value.errors().get("重映射扇区"));
    }

    @Test
    void readsAttribute194WhenVendorAttribute231HasTheSameTemperatureName() {
        String measured = "194 Temperature_Celsius 0x0002 171 171 000 Old_age Always - 35 (Min/Max 23/51)\n";
        String vendor = "231 Temperature_Celsius 0x0032 100 100 000 Old_age Always - 0\n";
        assertEquals(35, InspectionSmartSnapshot.parse(measured + vendor, "HDD").temperature());
        assertEquals(35, InspectionSmartSnapshot.parse(vendor + measured, "HDD").temperature());
    }

    @Test
    void doesNotTreatVendorAttribute231AsAMeasuredTemperature() {
        var value = InspectionSmartSnapshot.parse(
                "231 Temperature_Celsius 0x0032 100 100 000 Old_age Always - 0\n", "HDD");
        assertNull(value.temperature());
    }

    @Test
    void preservesAGenuineZeroTemperatureInAttribute194OrNvmeTemperature() {
        assertEquals(0, InspectionSmartSnapshot.parse(
                "194 Temperature_Celsius 0x0002 171 171 000 Old_age Always - 0\n"
                        + "231 Temperature_Celsius 0x0032 100 100 000 Old_age Always - 35\n", "HDD").temperature());
        assertEquals(0, InspectionSmartSnapshot.parse("Temperature: 0 Celsius\n", "NVME_SSD").temperature());
    }

    @Test
    void acceptsExplicitKingstonUnitsButDoesNotGuessAttributeIds() {
        String attributes = """
                231 SSD_Life_Left 0x0000 082 082 000 Old_age Offline - 82
                241 Lifetime_Writes_GiB 0x0032 100 100 000 Old_age Always - 14087
                """;
        var value = InspectionSmartSnapshot.parse(attributes, "SATA_SSD");
        assertEquals(18, value.lifeUsed());
        assertEquals(BigInteger.valueOf(14087).shiftLeft(30), value.writtenBytes());
        var other = InspectionSmartSnapshot.parse(attributes.replace("SSD_Life_Left", "Temperature_Celsius")
                .replace("Lifetime_Writes_GiB", "Vendor_Writes"), "SATA_SSD");
        assertNull(other.lifeUsed()); assertNull(other.writtenBytes());
    }
}
