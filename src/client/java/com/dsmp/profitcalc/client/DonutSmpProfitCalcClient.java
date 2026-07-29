package com.dsmp.profitcalc.client;

import com.dsmp.profitcalc.DonutSmpProfitCalc;
import com.dsmp.profitcalc.client.config.ProfitConfig;
import com.dsmp.profitcalc.client.handler.AutoFlipCalcHandler;
import com.dsmp.profitcalc.client.listener.OrderChatListener;
import com.dsmp.profitcalc.client.listener.OrderScreenHandler;
import com.dsmp.profitcalc.client.tracker.ProfitTracker;
import com.dsmp.profitcalc.client.ui.ProfitDetailsScreen;
import com.dsmp.profitcalc.client.ui.ProfitHudOverlay;
import com.dsmp.profitcalc.client.ui.StatusHudOverlay;
import com.dsmp.profitcalc.client.updater.AutoUpdater;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class DonutSmpProfitCalcClient implements ClientModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger("donut-smp-profit-calc/Client");

	private static KeyMapping toggleHudKey;
	private static KeyMapping openGuiKey;
	private static KeyMapping debugF6Key;
	private static KeyMapping autoFlipKey;

	@Override
	public void onInitializeClient() {
		LOGGER.info("Initializing Donut SMP Profit Calculator Client...");

		// 1. Load Configurations
		ProfitConfig.load();

		// 2. Initialize Profit Tracker & UI Overlays
		ProfitTracker.getInstance();
		ProfitHudOverlay.initialize();
		StatusHudOverlay.initialize();

		// 3. Register Container Screen & Chat Listeners
		OrderScreenHandler screenHandler = new OrderScreenHandler();
		screenHandler.register();

		OrderChatListener chatListener = new OrderChatListener();
		chatListener.register();

		// 4. Check for GitHub Updates on Startup
		AutoUpdater.checkOnStartup();

		// 5. Register Keybindings (O: Toggle HUD, P: Open Dashboard, F6: Log Dialogue Debug, F7: Auto Flip Scan)
		KeyMapping.Category modCategory = KeyMapping.Category.register(DonutSmpProfitCalc.id("general"));

		toggleHudKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.donut-smp-profit-calc.toggle_hud",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_O,
				modCategory
		));

		openGuiKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.donut-smp-profit-calc.open_gui",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_P,
				modCategory
		));

		debugF6Key = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.donut-smp-profit-calc.debug_f6",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_F6,
				modCategory
		));

		autoFlipKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.donut-smp-profit-calc.auto_flip",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_F7,
				modCategory
		));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (toggleHudKey.consumeClick()) {
				boolean newState = !ProfitConfig.getInstance().isHudEnabled();
				ProfitConfig.getInstance().setHudEnabled(newState);
				ProfitHudOverlay.refreshHud();
				if (client.player != null) {
					client.player.displayClientMessage(Component.literal("§a[Donut Profit Calc] HUD Overlay " + (newState ? "Enabled" : "Disabled")), true);
				}
			}
			while (openGuiKey.consumeClick()) {
				client.setScreen(new ProfitDetailsScreen());
			}
			while (debugF6Key.consumeClick()) {
				if (client.screen != null) {
					logDialogueScreenDetails(client.screen);
				} else if (client.player != null) {
					client.player.displayClientMessage(Component.literal("§c[Donut Debug] No GUI or Dialogue screen is currently open."), false);
				}
			}
			while (autoFlipKey.consumeClick()) {
				com.dsmp.profitcalc.client.dumper.PriceDumperHandler.stop();
				AutoFlipCalcHandler.stop();
				com.dsmp.profitcalc.client.handler.BestDealFinderHandler.stop();
				if (client.player != null) {
					client.player.displayClientMessage(Component.literal("§c[Donut Profit] Force stopped active search & scan!"), true);
				}
			}

			AutoFlipCalcHandler.onTick(client);
			com.dsmp.profitcalc.client.dumper.PriceDumperHandler.onTick(client);
			com.dsmp.profitcalc.client.handler.BestDealFinderHandler.onTick(client);
		});

		// 6. Register /profit Client Commands
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			dispatcher.register(ClientCommandManager.literal("profit")
					.executes(context -> {
						Minecraft.getInstance().execute(() -> Minecraft.getInstance().setScreen(new ProfitDetailsScreen()));
						return 1;
					})
					.then(ClientCommandManager.literal("reset").executes(context -> {
						ProfitTracker.getInstance().resetSession();
						context.getSource().sendFeedback(Component.literal("§a[Donut Profit Calc] Session statistics reset!"));
						return 1;
					}))
					.then(ClientCommandManager.literal("toggle").executes(context -> {
						boolean newState = !ProfitConfig.getInstance().isHudEnabled();
						ProfitConfig.getInstance().setHudEnabled(newState);
						ProfitHudOverlay.refreshHud();
						context.getSource().sendFeedback(Component.literal("§a[Donut Profit Calc] HUD Overlay " + (newState ? "Enabled" : "Disabled")));
						return 1;
					}))
					.then(ClientCommandManager.literal("autoflip").executes(context -> {
						Minecraft.getInstance().execute(AutoFlipCalcHandler::start);
						return 1;
					}))
					.then(ClientCommandManager.literal("update").executes(context -> {
						AutoUpdater.downloadAndInstall();
						return 1;
					}))
			);
		});

		LOGGER.info("Donut SMP Profit Calculator Client initialized successfully!");
	}

	public static void logDialogueScreenDetails(Screen screen) {
		LOGGER.info("=== [DONUT PROFIT DEBUG: SCREEN INSPECTION] ===");
		LOGGER.info("Screen Class: {}", screen.getClass().getName());
		LOGGER.info("Screen Title: {}", screen.getTitle() != null ? screen.getTitle().getString() : "NULL");

		List<String> textLines = OrderScreenHandler.extractAllTextFromScreen(screen);
		LOGGER.info("--- Extracted Text Lines ({}) ---", textLines.size());
		for (String line : textLines) {
			LOGGER.info("  Line: '{}'", line);
		}

		int widgetIndex = 0;
		for (var child : screen.children()) {
			LOGGER.info("Child #{}: Class: {}", widgetIndex, child.getClass().getName());
			if (child instanceof AbstractWidget widget) {
				LOGGER.info("  Widget Message: '{}'", widget.getMessage() != null ? widget.getMessage().getString() : "NULL");
				LOGGER.info("  Widget Bounds: x={}, y={}, w={}, h={}", widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight());
				if (child instanceof EditBox editBox) {
					LOGGER.info("  EditBox Value: '{}'", editBox.getValue());
				}
			}
			widgetIndex++;
		}

		if (screen instanceof AbstractContainerScreen<?> containerScreen) {
			LOGGER.info("Container Menu: {}", containerScreen.getMenu().getClass().getName());
			for (Slot slot : containerScreen.getMenu().slots) {
				if (!slot.getItem().isEmpty()) {
					LOGGER.info("  Slot #{}: Item: '{}'", slot.index, slot.getItem().getHoverName().getString());
				}
			}
		}
		LOGGER.info("=== [END DONUT PROFIT DEBUG] ===");

		if (Minecraft.getInstance().player != null) {
			Minecraft.getInstance().player.displayClientMessage(Component.literal("§a[Donut Debug] Logged screen details! Title: " + (screen.getTitle() != null ? screen.getTitle().getString() : "None")), false);
		}
	}
}