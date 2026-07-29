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
    private static Screen lastClickedItemScreen = null; // used to detect screen change after clicking item

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
        lastClickedItemScreen = null;

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

        // Timeout check (12 seconds per step max for server latency)
        if (state != State.IDLE && state != State.FINISHED && state != State.FAILED) {
            if (stateTicks > 240 || (now - stateStartTime > 12000)) {
                LOGGER.warn("[Auto Order Creator] Timed out in state: {} (ticks={}, elapsed={}ms)", state, stateTicks, now - stateStartTime);
                fail("Server lag timeout in step " + state.name() + " (took >12s)");
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

                        // Grid filters live as you type — go straight to item click, the 20-tick
                        // settle wait in CLICKING_ITEM_BUTTON covers the filter refresh delay.
                        state = State.CLICKING_ITEM_BUTTON;
                        stateStartTime = now;
                        stateTicks = 0;
                    }
                }
            }

            case CLICKING_ITEM_BUTTON -> {
                // Wait 20 ticks (1s) for grid filter to settle after pressing Search
                if (stateTicks >= 20 && screen != null) {
                    Button matchingBtn = findMatchingItemGridButton(screen, targetItemName);
                    if (matchingBtn != null) {
                        LOGGER.info("[Auto Order Creator] Found matching item button '{}', clicking",
                                matchingBtn.getMessage().getString());
                        lastClickedItemScreen = screen; // record screen so we can detect the next one
                        pressButton(matchingBtn);

                        state = State.WAIT_AMOUNT_SCREEN;
                        stateStartTime = now;
                        stateTicks = 0;
                    } else {
                        // Keep waiting up to 80 ticks (4s) for filter to populate then fail
                        if (stateTicks >= 80) {
                            String query = formatSearchQuery(targetItemName);
                            fail("Item search for '" + query + "' did not resolve to any matching grid item after filter");
                        }
                    }
                }
            }

            case WAIT_AMOUNT_SCREEN -> {
                // Diagnostic: log once per second so stalls are visible
                if (stateTicks % 20 == 0) {
                    LOGGER.info("[Auto Order Creator] Still waiting for amount screen — current screen: {} / title: '{}' / same={}",
                            screen == null ? "null" : screen.getClass().getSimpleName(),
                            getScreenTitle(screen),
                            screen == lastClickedItemScreen);
                }
                // Wait for a NEW screen to open (different from the Choose Item screen)
                if (screen != null && screen != lastClickedItemScreen) {
                    String title = getScreenTitle(screen);
                    if (title.contains("how many") || title.contains("amount") || title.contains("quantity")) {
                        LOGGER.info("[Auto Order Creator] 'How Many?' dialog screen detected by title: '{}'", title);
                        state = State.TYPING_AMOUNT;
                        stateStartTime = now;
                        stateTicks = 0;
                    } else if (!(screen instanceof AbstractContainerScreen<?>) && hasEditBox(screen) && stateTicks >= 5) {
                        // New non-chest dialog with an EditBox = probably amount screen
                        LOGGER.info("[Auto Order Creator] 'How Many?' dialog screen detected by EditBox on new screen: '{}'", title);
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

        // Identity-based visited set to prevent circular reference loops (parent <-> child)
        java.util.Set<Object> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        visited.add(screen);

        for (GuiEventListener child : screen.children()) {
            collectWidgetsDeep(child, list, visited, 0);
        }
        return list;
    }

    private static void collectWidgetsDeep(Object obj, List<GuiEventListener> list, java.util.Set<Object> visited, int depth) {
        if (obj == null || depth > 6 || !visited.add(obj)) return;

        String className = obj.getClass().getName();
        if (shouldSkipObject(obj, className)) return;

        if (obj instanceof GuiEventListener listener) {
            if (!list.contains(listener)) {
                list.add(listener);
            }
        }

        // 1. If it implements ContainerEventHandler, check children()
        if (obj instanceof ContainerEventHandler container) {
            try {
                for (GuiEventListener child : container.children()) {
                    collectWidgetsDeep(child, list, visited, depth + 1);
                }
            } catch (Exception ignored) {}
        }

        // 2. Reflection check for layout containers (e.g. ScrollableLayoutWidget.Container)
        try {
            Class<?> clazz = obj.getClass();
            while (clazz != null && clazz != Object.class) {
                String cName = clazz.getName();
                if (cName.startsWith("java.") || cName.startsWith("sun.") || cName.startsWith("org.lwjgl.")) {
                    break;
                }

                for (java.lang.reflect.Field f : clazz.getDeclaredFields()) {
                    Class<?> fType = f.getType();
                    if (isWidgetOrContainerType(fType)) {
                        try {
                            f.setAccessible(true);
                            Object val = f.get(obj);
                            processReflectedValue(val, list, visited, depth + 1);
                        } catch (Exception ignored) {}
                    }
                }
                clazz = clazz.getSuperclass();
            }
        } catch (Exception ignored) {}
    }

    private static void processReflectedValue(Object val, List<GuiEventListener> list, java.util.Set<Object> visited, int depth) {
        if (val == null || depth > 6) return;

        if (val instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (item != null) processReflectedValue(item, list, visited, depth + 1);
            }
        } else if (val.getClass().isArray()) {
            int len = java.lang.reflect.Array.getLength(val);
            for (int i = 0; i < len; i++) {
                Object item = java.lang.reflect.Array.get(val, i);
                if (item != null) processReflectedValue(item, list, visited, depth + 1);
            }
        } else if (val instanceof GuiEventListener || val instanceof ContainerEventHandler) {
            collectWidgetsDeep(val, list, visited, depth + 1);
        } else {
            String cName = val.getClass().getName();
            if (cName.contains("Layout") || cName.contains("Container") || cName.contains("Widget") || cName.contains("$")) {
                collectWidgetsDeep(val, list, visited, depth + 1);
            }
        }
    }

    private static boolean shouldSkipObject(Object obj, String className) {
        if (obj instanceof Minecraft || obj instanceof net.minecraft.client.multiplayer.ClientLevel ||
            obj instanceof net.minecraft.world.entity.Entity || obj instanceof java.nio.file.Path ||
            obj instanceof java.lang.Thread || obj instanceof java.lang.ClassLoader) {
            return true;
        }
        return className.startsWith("java.nio.") || className.startsWith("sun.nio.") ||
               className.startsWith("org.lwjgl.") || className.startsWith("com.mojang.blaze3d.");
    }

    private static boolean isWidgetOrContainerType(Class<?> type) {
        if (GuiEventListener.class.isAssignableFrom(type) ||
            ContainerEventHandler.class.isAssignableFrom(type) ||
            java.util.Collection.class.isAssignableFrom(type) ||
            type.isArray()) {
            return true;
        }
        String name = type.getName();
        return name.contains("Widget") || name.contains("Layout") || name.contains("Container") || name.contains("$");
    }

    public static boolean hasEditBox(Screen screen) {
        return findEditBox(screen) != null;
    }

    public static EditBox findEditBox(Screen screen) {
        List<GuiEventListener> widgets = flattenWidgets(screen);
        for (GuiEventListener listener : widgets) {
            if (listener instanceof EditBox editBox) {
                LOGGER.info("[Auto Order Creator] findEditBox found EditBox (val: '{}', class: {})",
                        editBox.getValue(), editBox.getClass().getName());
                return editBox;
            }
        }
        LOGGER.warn("[Auto Order Creator] findEditBox returned NULL (searched {} flattened widgets)", widgets.size());
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
                        LOGGER.info("[Auto Order Creator] findButtonByLabel matched button '{}' for target '{}' (class: {})",
                                msg, label, button.getClass().getName());
                        return button;
                    }
                }
            }
        }
        LOGGER.warn("[Auto Order Creator] findButtonByLabel returned NULL for targets {} (searched {} flattened widgets)",
                java.util.Arrays.toString(labels), widgets.size());
        return null;
    }

    public static Button findMatchingItemGridButton(Screen screen, String itemQuery) {
        List<GuiEventListener> widgets = flattenWidgets(screen);
        String cleanQuery = itemQuery.trim().replace("_", " ").toLowerCase(Locale.ROOT);

        Button exactMatch = null;
        List<Button> containsMatches = new ArrayList<>();

        for (GuiEventListener listener : widgets) {
            if (listener instanceof Button button) {
                String btnText = getCleanWidgetText(button);
                if (btnText.isEmpty()) continue;

                String lowerText = btnText.toLowerCase(Locale.ROOT);

                // Ignore navigation and standard control buttons
                if (isControlButtonLabel(lowerText)) {
                    continue;
                }

                if (lowerText.equals(cleanQuery)) {
                    // Perfect exact match — return immediately
                    LOGGER.info("[Auto Order Creator] findMatchingItemGridButton: exact match found '{}'", btnText);
                    return button;
                }

                if (lowerText.contains(cleanQuery)) {
                    containsMatches.add(button);
                }
            }
        }

        LOGGER.info("[Auto Order Creator] findMatchingItemGridButton for query '{}': exact={}, contains={} (total widgets={})",
                cleanQuery, exactMatch != null, containsMatches.size(), widgets.size());

        if (containsMatches.size() == 1) {
            LOGGER.info("[Auto Order Creator] Unique contains candidate: '{}'", getCleanWidgetText(containsMatches.get(0)));
            return containsMatches.get(0);
        } else if (containsMatches.size() > 1) {
            // Pick the shortest text match (most specific) — e.g. "Oak Log" wins over "Oak Log Stripped"
            Button best = containsMatches.get(0);
            for (Button b : containsMatches) {
                if (getCleanWidgetText(b).length() < getCleanWidgetText(best).length()) {
                    best = b;
                }
            }
            LOGGER.info("[Auto Order Creator] Multiple contains candidates — picking shortest: '{}'", getCleanWidgetText(best));
            return best;
        }

        LOGGER.warn("[Auto Order Creator] findMatchingItemGridButton returned NULL for query '{}'", cleanQuery);
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
        String raw = widget.getMessage().getString();
        // Strip minecraft color codes and item icon tags like [item/acacia_boat@items] or [block/oak_log]
        String clean = raw.replaceAll("§[0-9a-fk-or]", "").replaceAll("\\[.*?\\]", "").trim();
        return clean;
    }

    public static void pressButton(Button btn) {
        if (btn == null) return;
        LOGGER.info("[Auto Order Creator] Pressing button '{}' (class: {})",
                getCleanWidgetText(btn), btn.getClass().getName());
        try {
            btn.onPress(null);
        } catch (Throwable t) {
            LOGGER.error("[Auto Order Creator] Error executing btn.onPress(null)", t);
        }
    }
}
