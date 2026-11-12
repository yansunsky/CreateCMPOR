package com.yansunsky.createcmpor.block;

import java.nio.file.Files;
import java.nio.file.Path;

/** Source-wiring tripwire only; chunk/capability behavior still requires an in-game test. */
public final class IOExtensionCapabilityWiringTest {
    private IOExtensionCapabilityWiringTest() {
    }

    public static void main(String[] args) throws Exception {
        String source = Files.readString(Path.of(args[0]));
        String compact = source.replaceAll("\\s+", "");
        String start = "publicstaticvoidregisterCapabilities(RegisterCapabilitiesEventevent){";
        String end = "event.registerBlockEntity(Capabilities.ItemHandler.BLOCK,ModBlockEntities.INPUT.get()";
        int from = compact.indexOf(start);
        int to = compact.indexOf(end, from);
        check(from >= 0 && to > from, "cannot locate IOExtension capability registrations");
        String providers = compact.substring(from, to);
        check(!providers.contains("getReachableFactories(")
                        && !providers.contains("getBlockEntity(") && !providers.contains("hasEnergyIo("),
                "initial remote unavailability must not produce a cacheable null capability");
        verifyProvider(providers, "ItemHandler", "item");
        verifyProvider(providers, "FluidHandler", "fluid");
        verifyProvider(providers, "EnergyStorage", "energy");
        String proxy = Files.readString(Path.of(args[1])).replaceAll("\\s+", "");
        int localLoaded = proxy.indexOf("!level.isLoaded(extensionPos)");
        int localIdentity = proxy.indexOf("level.getBlockEntity(extensionPos)!=extension");
        int localFace = proxy.indexOf("!extension.isIoFace(side)");
        check(localLoaded >= 0 && localIdentity > localLoaded && localFace > localIdentity,
                "loaded and identity checks must precede direction access on a cached extension");
        int targetLoaded = proxy.indexOf("!level.isLoaded(targetPos)");
        int targetLookup = proxy.indexOf("level.getBlockEntity(targetPos)");
        check(targetLoaded >= 0 && targetLookup > targetLoaded,
                "target lookup must retain its no-chunk-loading guard");
        System.out.println("IOExtension capability wiring checks passed (3 providers + 2 guard orders; source only).");
    }

    private static void verifyProvider(String providers, String capability, String proxy) {
        String expected = "event.registerBlockEntity(Capabilities." + capability
                + ".BLOCK,ModBlockEntities.IO_EXTENSION.get(),"
                + "(be,side)->be.getLevel()!=null&&be.isIoFace(side)?IOExtensionHandlerProxy."
                + proxy + "(be,side):null);";
        check(providers.contains(expected), capability
                + " must return a dynamic proxy based only on the local level and IO face");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
