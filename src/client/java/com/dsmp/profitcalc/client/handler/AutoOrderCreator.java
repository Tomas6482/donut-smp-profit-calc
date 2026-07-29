package com.dsmp.profitcalc.client.handler;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class AutoOrderCreator {

    private static final Logger LOGGER = LoggerFactory.getLogger("donut-smp-profit-calc/AutoOrderCreator");

    public enum State {
        IDLE,
        OPEN_ORDER_GUI,           // Executed /order, waiting for "Orders (Page 1)" chest GUI
        CLICK_YOUR_ORDERS_SLOT,   // Click Slot #51 ("Your Orders")
        WAIT_YOUR_ORDERS_GUI,     // Waiting for "Orders -> Your Orders" screen
        CLICK_NEW_ORDER_SLOT,     // In "Orders -> Your Orders", click first "New Order" slot in 0..17
        WAIT_CHOOSE_ITEM_SCREEN,  // Waiting for "Choose Item" dialog screen
        TYPING_ITEM_SEARCH,       // In Choose Item screen, typing item into search box
        CLICKING_SEARCH_BUTTON,   // Clicking the "Search" button below text box
        CLICKING_ITEM_BUTTON,     // Finding and clicking matching item button in grid
        WAIT_AMOUNT_SCREEN,       // Waiting for "How many?" dialog screen
        TYPING_AMOUNT,            // Setting amount in EditBox
        CLICKING_AMOUNT_NEXT,     // Clicking "Next" button
        WAIT_PRICE_SCREEN,        // Waiting for "Price per item?" dialog screen
        TYPING_PRICE,             // Setting price per item in EditBox
        CLICKING_PRICE_REVIEW,    // Clicking "Review Order" button
        WAIT_REVIEW_SCREEN,       // Waiting for "Review Order" dialog screen
        CLICKING_CREATE_ORDER,    // Clicking "Create Order" button
        FINISHED,
        FAILED
    }

    private static boolean running = false;
    private static State state = State.IDLE;

    private static String targetItemName = "";
    private static int targetAmount = 0;
    private static double targetPrice = 0.0;

    private static long stateStartTime = 0;
    private static int stateTicks = 0;

    public static void start(String itemName, int amount, double pricePerItem) {
        if (itemName == null || itemName.trim().isEmpty()) {
            notifyUser("§c[Auto Order] Error: Item name cannot be empty!");
            return;
        }
        if (amount <= 0) {
            notifyUser("§c[Auto Order] Error: Amount must be greater than 0!");
            return;
        }
        if (pricePerItem <= 0) {
            notifyUser("§c[Auto Order] Error: Price per item must be greater than 0!");
            return;
        }

        running = true;
        targetItemName = itemName.trim();
        targetAmount = amount;
        targetPrice = pricePerItem;

        state = State.OPEN_ORDER_GUI;
        stateStartTime = System.currentTimeMillis();
        stateTicks = 0;

        LOGGER.info("[Auto Order Creator] Started creation flow: Item='{}', Amount={}, Price=${}",
                targetItemName, targetAmount, targetPrice);

        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            mc.player.closeContainer();
            mc.player.connection.sendCommand("order");
            notifyUser("§a[Auto Order] Opening /order GUI...");
        }
    }

    public static boolean isRunning() {
        return running;
    }

    public static State getState() {
        return state;
    }

    public static void stop() {
        running = false;
        state = State.IDLE;
    }

    public static void onTick(Minecraft mc) {
        if (!running || mc == null) return;

        stateTicks++;
        long now = System.currentTimeMillis();
        Screen screen = mc.screen;

        // Timeout check (3 seconds per step max)
        if (state != State.IDLE && state != State.FINISHED && state != State.FAILED) {
            if (stateTicks > 60 || (now - stateStartTime > 3500)) {
                LOGGER.warn("[Auto Order Creator] Timed out in state: {}", state);
                fail("Timed out waiting in step " + state.name());
                return;
            }
        }

        switch (state) {
            case OPEN_ORDER_GUI -> {
                if (screen instanceof AbstractContainerScreen<?> containerScreen) {
                    String title = getScreenTitle(screen);
                    if (title.contains("your orders")) {
                        LOGGER.info("[Auto Order Creator] Already in 'Orders -> Your Orders' GUI: '{}'", title);
                        state = State.CLICK_NEW_ORDER_SLOT;
                        stateStartTime = now;
                        stateTicks = 0;
                    } else if (title.contains("order")) {
                        LOGGER.info("[Auto Order Creator] /order main screen detected: '{}'", title);
                        state = State.CLICK_YOUR_ORDERS_SLOT;
                        stateStartTime = now;
                        stateTicks = 0;
                    }
                }
            }

            case CLICK_YOUR_ORDERS_SLOT -> {
                if (screen instanceof AbstractContainerScreen<?> containerScreen) {
                    int slotToClick = findYourOrdersSlot(containerScreen);
                    if (slotToClick < 0) {
                        fail("Could not find 'Your Orders' button (slot 51)");
                        return;
                    }

                    LOGGER.info("[Auto Order Creator] Clicking 'Your Orders' slot #{}", slotToClick);
                    int containerId = containerScreen.getMenu().containerId;
                    mc.gameMode.handleInventoryMouseClick(containerId, slotToClick, 0, ClickType.PICKUP, mc.player);

                    state = State.WAIT_YOUR_ORDERS_GUI;
                    stateStartTime = now;
                    stateTicks = 0;
                }
            }

            case WAIT_YOUR_ORDERS_GUI -> {
                if (screen instanceof AbstractContainerScreen<?> containerScreen) {
                    String title = getScreenTitle(screen);
                    if (title.contains("your orders")) {
                        LOGGER.info("[Auto Order Creator] 'Orders -> Your Orders' GUI detected: '{}'", title);
                        state = State.CLICK_NEW_ORDER_SLOT;
                        stateStartTime = now;
                        stateTicks = 0;
                    }
                }
            }

            case CLICK_NEW_ORDER_SLOT -> {
                if (screen instanceof AbstractContainerScreen<?> containerScreen) {
                    int slotToClick = findAvailableNewOrderSlot(containerScreen);
                    if (slotToClick < 0) {
                        fail("No available 'New Order' slots found (All 18 slots full or max limit reached)");
                        return;
                    }

                    LOGGER.info("[Auto Order Creator] Clicking available New Order slot #{}", slotToClick);
                    int containerId = containerScreen.getMenu().containerId;
                    mc.gameMode.handleInventoryMouseClick(containerId, slotToClick, 0, ClickType.PICKUP, mc.player);

                    state = State.WAIT_CHOOSE_ITEM_SCREEN;
                    stateStartTime = now;
                    stateTicks = 0;
                }
            }

            case WAIT_CHOOSE_ITEM_SCREEN -> {
                if (screen != null && !(screen instanceof AbstractContainerScreen<?>)) {
                    String title = getScreenTitle(screen);
                    if (title.contains("choose") || title.contains("item") || title.contains("select") || hasEditBox(screen)) {
                        LOGGER.info("[Auto Order Creator] 'Choose Item' dialog screen detected: '{}'", title);
                        state = State.TYPING_ITEM_SEARCH;
                        stateStartTime = now;
                        stateTicks = 0;
                    }
                }
            }

            case TYPING_ITEM_SEARCH -> {
                if (screen != null) {
                    EditBox editBox = findEditBox(screen);
                    if (editBox != null) {
                        String searchQuery = formatSearchQuery(targetItemName);
                        LOGGER.info("[Auto Order Creator] Typing item search query '{}' into EditBox", searchQuery);
                        editBox.setValue(searchQuery);
                        editBox.setFocused(true);

                        state = State.CLICKING_SEARCH_BUTTON;
                        stateStartTime = now;
                        stateTicks = 0;
                    }
                }
            }

            case CLICKING_SEARCH_BUTTON -> {
                if (stateTicks >= 2 && screen != null) {
                    Button searchBtn = findButtonByLabel(screen, "Search");
                    if (searchBtn != null) {
                        LOGGER.info("[Auto Order Creator] Clicking 'Search' button to filter item grid");
                        pressButton(searchBtn);

                        state = State.CLICKING_ITEM_BUTTON;
                        stateStartTime = now;
                        stateTicks = 0;
                    } else {
                        // Fallback if no Search button exists
                        state = State.CLICKING_ITEM_BUTTON;
                        stateStartTime = now;
                        stateTicks = 0;
                    }
                }
            }

            case CLICKING_ITEM_BUTTON -> {
                // Wait 2 ticks for live filter to populate grid
                if (stateTicks >= 2 && screen != null) {
                    Button matchingBtn = findMatchingItemGridButton(screen, targetItemName);
                    if (matchingBtn != null) {
                        LOGGER.info("[Auto Order Creator] Found matching item button '{}', clicking",
                                matchingBtn.getMessage().getString());
                        pressButton(matchingBtn);

                        state = State.WAIT_AMOUNT_SCREEN;
                        stateStartTime = now;
                        stateTicks = 0;
                    } else {
                        // Keep waiting up to tick limit or fail if search returns 0/multi
                        if (stateTicks >= 10) {
                            String query = formatSearchQuery(targetItemName);
                            fail("Item search for '" + query + "' did not resolve to a unique grid item");
                        }
                    }
                }
            }

            case WAIT_AMOUNT_SCREEN -> {
                if (screen != null) {
                    String title = getScreenTitle(screen);
                    if (title.contains("how many") || title.contains("amount") || title.contains("quantity") || hasEditBox(screen)) {
                        LOGGER.info("[Auto Order Creator] 'How Many?' dialog screen detected: '{}'", title);
                        state = State.TYPING_AMOUNT;
                        stateStartTime = now;
                        stateTicks = 0;
                    }
                }
            }

            case TYPING_AMOUNT -> {
                if (screen != null) {
                    EditBox editBox = findEditBox(screen);
                    if (editBox != null) {
                        LOGGER.info("[Auto Order Creator] Setting amount '{}' in EditBox", targetAmount);
                        editBox.setValue(String.valueOf(targetAmount));
                        editBox.setFocused(true);

                        state = State.CLICKING_AMOUNT_NEXT;
                        stateStartTime = now;
                        stateTicks = 0;
                    }
                }
            }

            case CLICKING_AMOUNT_NEXT -> {
                if (stateTicks >= 2 && screen != null) {
                    Button nextBtn = findButtonByLabel(screen, "Next", "Confirm", "Continue");
                    if (nextBtn != null) {
                        LOGGER.info("[Auto Order Creator] Clicking 'Next' button");
                        pressButton(nextBtn);

                        state = State.WAIT_PRICE_SCREEN;
                        stateStartTime = now;
                        stateTicks = 0;
                    }
                }
            }

            case WAIT_PRICE_SCREEN -> {
                if (screen != null) {
                    String title = getScreenTitle(screen);
                    if (title.contains("price") || title.contains("cost") || title.contains("per item") || hasEditBox(screen)) {
                        LOGGER.info("[Auto Order Creator] 'Price Per Item?' dialog screen detected: '{}'", title);
                        state = State.TYPING_PRICE;
                        stateStartTime = now;
                        stateTicks = 0;
                    }
                }
            }

            case TYPING_PRICE -> {
                if (screen != null) {
                    EditBox editBox = findEditBox(screen);
                    if (editBox != null) {
                        String priceStr = formatPriceString(targetPrice);
                        LOGGER.info("[Auto Order Creator] Setting price '{}' in EditBox", priceStr);
                        editBox.setValue(priceStr);
                        editBox.setFocused(true);

                        state = State.CLICKING_PRICE_REVIEW;
                        stateStartTime = now;
                        stateTicks = 0;
                    }
                }
            }

            case CLICKING_PRICE_REVIEW -> {
                if (stateTicks >= 2 && screen != null) {
                    Button reviewBtn = findButtonByLabel(screen, "Review Order", "Review", "Next", "Confirm");
                    if (reviewBtn != null) {
                        LOGGER.info("[Auto Order Creator] Clicking 'Review Order' button");
                        pressButton(reviewBtn);

                        state = State.WAIT_REVIEW_SCREEN;
                        stateStartTime = now;
                        stateTicks = 0;
                    }
                }
            }

            case WAIT_REVIEW_SCREEN -> {
                if (screen != null) {
                    String title = getScreenTitle(screen);
                    if (title.contains("review") || title.contains("summary") || hasButtonLabel(screen, "Create Order", "Create")) {
                        LOGGER.info("[Auto Order Creator] 'Review Order' dialog screen detected: '{}'", title);
                        state = State.CLICKING_CREATE_ORDER;
                        stateStartTime = now;
                        stateTicks = 0;
                    }
                }
            }

            case CLICKING_CREATE_ORDER -> {
                if (stateTicks >= 1 && screen != null) {
                    Button createBtn = findButtonByLabel(screen, "Create Order", "Create", "Confirm Order");
                    if (createBtn != null) {
                        LOGGER.info("[Auto Order Creator] Clicking 'Create Order' button! Finalizing order creation.");
                        pressButton(createBtn);

                        state = State.FINISHED;
                        stop();

                        String priceStr = formatPriceString(targetPrice);
                        notifyUser("§a[Auto Order] Successfully created order for " + targetAmount + "x " + targetItemName + " @ $" + priceStr + "/ea!");
                    }
                }
            }
        }
    }

    private static void fail(String reason) {
        LOGGER.warn("[Auto Order Creator] Order creation failed: {}", reason);
        stop();
        state = State.FAILED;
        notifyUser("§c[Auto Order] Order creation failed: " + reason);
    }

    private static void notifyUser(String msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            mc.player.displayClientMessage(Component.literal(msg), false);
        }
    }

    private static String getScreenTitle(Screen screen) {
        if (screen == null || screen.getTitle() == null) return "";
        return screen.getTitle().getString().toLowerCase(Locale.ROOT);
    }

    private static int findYourOrdersSlot(AbstractContainerScreen<?> containerScreen) {
        if (containerScreen == null || containerScreen.getMenu() == null) return -1;
        List<Slot> slots = containerScreen.getMenu().slots;

        // Check slot 51 first (standard location for 'Your Orders')
        if (slots.size() > 51) {
            String name = slots.get(51).getItem().getHoverName().getString().toLowerCase(Locale.ROOT);
            if (name.contains("your order") || name.contains("order")) {
                return 51;
            }
        }

        // Check bottom bar slots 45-53
        for (int i = 45; i < Math.min(54, slots.size()); i++) {
            String name = slots.get(i).getItem().getHoverName().getString().toLowerCase(Locale.ROOT);
            if (name.contains("your order")) {
                return i;
            }
        }
        return 51; // Fallback to slot 51
    }

    private static int findAvailableNewOrderSlot(AbstractContainerScreen<?> containerScreen) {
        if (containerScreen == null || containerScreen.getMenu() == null) return -1;
        List<Slot> slots = containerScreen.getMenu().slots;

        for (int i = 0; i < Math.min(18, slots.size()); i++) {
            ItemStack stack = slots.get(i).getItem();
            String name = stack.getHoverName().getString().toLowerCase(Locale.ROOT);

            // Skip locked or max orders slots
            if (name.contains("max order") || name.contains("locked") || name.contains("limit") || name.contains("buy donut")) {
                continue;
            }

            // Available new order slot indicators
            if (name.contains("new order") || name.contains("create order") || name.contains("click to create") || name.contains("new") || stack.isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    private static String formatSearchQuery(String rawItemName) {
        if (rawItemName == null) return "";
        String clean = rawItemName.trim().replace("_", " ");
        // Title Case capitalization for search query
        String[] parts = clean.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            sb.append(Character.toUpperCase(part.charAt(0)))
              .append(part.substring(1).toLowerCase())
              .append(" ");
        }
        return sb.toString().trim();
    }

    private static String formatPriceString(double price) {
        if (price % 1.0 == 0) {
            return String.valueOf((long) price);
        } else {
            return String.format(Locale.US, "%.2f", price);
        }
    }

    // --- WIDGET TRAVERSAL & SEARCH HELPERS ---

    public static List<GuiEventListener> flattenWidgets(Screen screen) {
        List<GuiEventListener> list = new ArrayList<>();
        if (screen == null) return list;
        for (GuiEventListener child : screen.children()) {
            collectWidgets(child, list);
        }
        return list;
    }

    private static void collectWidgets(GuiEventListener element, List<GuiEventListener> list) {
        if (element == null) return;
        list.add(element);
        if (element instanceof ContainerEventHandler container) {
            try {
                for (GuiEventListener child : container.children()) {
                    collectWidgets(child, list);
                }
            } catch (Exception ignored) {}
        }
    }

    public static boolean hasEditBox(Screen screen) {
        return findEditBox(screen) != null;
    }

    public static EditBox findEditBox(Screen screen) {
        for (GuiEventListener listener : flattenWidgets(screen)) {
            if (listener instanceof EditBox editBox) {
                return editBox;
            }
        }
        return null;
    }

    public static boolean hasButtonLabel(Screen screen, String... labels) {
        return findButtonByLabel(screen, labels) != null;
    }

    public static Button findButtonByLabel(Screen screen, String... labels) {
        List<GuiEventListener> widgets = flattenWidgets(screen);
        for (String label : labels) {
            String target = label.toLowerCase(Locale.ROOT).trim();
            for (GuiEventListener listener : widgets) {
                if (listener instanceof Button button) {
                    String msg = getCleanWidgetText(button);
                    if (msg.equalsIgnoreCase(target) || msg.contains(target)) {
                        return button;
                    }
                }
            }
        }
        return null;
    }

    public static Button findMatchingItemGridButton(Screen screen, String itemQuery) {
        List<GuiEventListener> widgets = flattenWidgets(screen);
        String cleanQuery = itemQuery.trim().replace("_", " ").toLowerCase(Locale.ROOT);

        List<Button> candidateButtons = new ArrayList<>();

        for (GuiEventListener listener : widgets) {
            if (listener instanceof Button button) {
                String btnText = getCleanWidgetText(button);
                if (btnText.isEmpty()) continue;

                String lowerText = btnText.toLowerCase(Locale.ROOT);

                // Ignore navigation and standard control buttons
                if (isControlButtonLabel(lowerText)) {
                    continue;
                }

                if (lowerText.equals(cleanQuery) || lowerText.contains(cleanQuery) || cleanQuery.contains(lowerText)) {
                    candidateButtons.add(button);
                }
            }
        }

        if (candidateButtons.size() == 1) {
            return candidateButtons.get(0);
        } else if (candidateButtons.size() > 1) {
            // Pick exact match if one exists among candidates
            for (Button b : candidateButtons) {
                String text = getCleanWidgetText(b).toLowerCase(Locale.ROOT);
                if (text.equals(cleanQuery)) {
                    return b;
                }
            }
        }

        return null;
    }

    private static boolean isControlButtonLabel(String labelText) {
        String l = labelText.toLowerCase(Locale.ROOT).trim();
        return l.equals("search") || l.contains("cancel") || l.contains("back") || l.contains("close") ||
               l.contains("next") || l.contains("previous") || l.contains("page") ||
               l.contains("review") || l.contains("create") || l.equals("✕") || l.equals("?");
    }

    private static String getCleanWidgetText(AbstractWidget widget) {
        if (widget == null || widget.getMessage() == null) return "";
        return widget.getMessage().getString().replaceAll("§[0-9a-fk-or]", "").trim();
    }

    public static void pressButton(Button btn) {
        if (btn == null) return;
        try {
            Class<?> clazz = btn.getClass();
            while (clazz != null && clazz != Object.class) {
                for (java.lang.reflect.Method m : clazz.getDeclaredMethods()) {
                    if (m.getName().equals("onPress")) {
                        m.setAccessible(true);
                        Class<?>[] pTypes = m.getParameterTypes();
                        if (pTypes.length == 0) {
                            m.invoke(btn);
                            return;
                        } else if (pTypes.length == 1) {
                            m.invoke(btn, (Object) null);
                            return;
                        }
                    }
                }
                clazz = clazz.getSuperclass();
            }
        } catch (Exception e) {
            LOGGER.error("[Auto Order Creator] Error pressing button via reflection", e);
        }
    }
}
