package com.createcmpor.init;

import com.createcmpor.CreateCMPOR;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModCreativeTabs {

    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, CreateCMPOR.MOD_ID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN = TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.createcmpor"))
                    .icon(() -> ModItems.IO_EXTENSION.get().getDefaultInstance())
                    .displayItems((parameters, output) -> {
                        output.accept(ModItems.LAUNCHER_STICK.get());
                        output.accept(ModItems.IO_EXTENSION.get());
                        output.accept(ModItems.FACTORY.get());
                        output.accept(ModItems.INPUT.get());
                        output.accept(ModItems.OUTPUT.get());
                        output.accept(ModItems.STRESS_INPUT.get());
                        output.accept(ModItems.STRESS_OUTPUT.get());
                    })
                    .build());

    public static void register(IEventBus modEventBus) {
        TABS.register(modEventBus);
    }
}
