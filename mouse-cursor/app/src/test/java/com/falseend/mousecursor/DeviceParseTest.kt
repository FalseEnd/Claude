package com.falseend.mousecursor

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceParseTest {
    private val sample = """
I: Bus=0003 Vendor=046d Product=c077 Version=0111
N: Name="Logitech USB Optical Mouse"
P: Phys=usb-xhci-hcd.0.auto-1/input0
H: Handlers=event5 mouse0
B: PROP=0
B: EV=17
B: KEY=ff0000 0 0 0 0
B: REL=903
B: MSC=10

I: Bus=0003 Vendor=046d Product=c52b Version=0111
N: Name="Logitech Unifying Receiver"
H: Handlers=sysrq kbd event6 mouse1
B: EV=12001f
B: KEY=3007f 0 0 483ffff17aff32d bf54444600000000 ffff0001 130f938b17c007 ffe77bfad9415fff ffbeffdf7bfeffff fffffffffffffffe
B: REL=1943

I: Bus=0000 Vendor=0000 Product=0000 Version=0000
N: Name="sec_touchscreen"
H: Handlers=event2
B: EV=b
B: KEY=400 0 0 0 0 0
B: ABS=6e18000 0

I: Bus=0005 Vendor=045e Product=0b05 Version=0001
N: Name="BT Keyboard"
H: Handlers=sysrq kbd event7
B: EV=12001f
B: KEY=3007f 0 0 483ffff17aff32d bf54444600000000 ffff0001 130f938b17c007 ffe77bfad9415fff ffbeffdf7bfeffff fffffffffffffffe
""".trimIndent()

    @Test fun findsOnlyPointingDevices() {
        val list = MouseReaderService.parseProcDevices(sample)
        assertEquals(listOf("/dev/input/event5", "/dev/input/event6"), list.map { it.path })
    }

    @Test fun detectsKeyboardComboReceiver() {
        val list = MouseReaderService.parseProcDevices(sample).associateBy { it.path }
        assertEquals(false, list.getValue("/dev/input/event5").isKeyboard)
        assertEquals(true, list.getValue("/dev/input/event6").isKeyboard)
    }
}
