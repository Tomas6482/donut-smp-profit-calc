# Prompt for Antigravity: "Check All" button + profit summary popup

Add a **"Check All"** button to the header bar in `ProfitDetailsScreen.java`
(alongside Settings, Price Dumper, Undo, Reset). When clicked, it should
run the Auto Check scan for **every** flip mode in sequence (Bone, Kelp,
Oak Log, Sticky Piston, Golden Apple, Bookshelf, Trapdoor), then show a
popup summarizing all 7 flips' profit, sorted from highest to lowest.

Please implement in the parts below and show me the diff for each before
moving on if anything is ambiguous.

---

## Part 1: Extract each flip's profit formula into a reusable method

Each tab in `ProfitDetailsScreen.updateCalc()` already computes profit and
margin from that flip's saved config prices — this logic currently lives
embedded in the UI's calc/label-update code. For "Check All" to work, this
needs to be callable **without** that tab being the active/visible one.

Please extract each flip mode's profit calculation into a standalone method
— e.g. `AutoFlipCalcHandler.computeProfitForMode(FlipMode mode)` — that:
- Reads the same saved `ProfitConfig` prices and quantities each tab's
  `updateCalc()` already reads (e.g. `getSavedBonePrice()`,
  `getSavedBonesQty()`, etc. — and for Trapdoor, the baseline/ceiling/stack
  size fields added in the last update).
- Runs the **exact same formula** each tab already uses — do not
  re-derive, approximate, or simplify it. Copy the logic verbatim out of
  `updateCalc()`'s per-tab branches into this new method, then have
  `updateCalc()` call the new method instead of duplicating the math
  inline. This guarantees the "Check All" summary numbers can never drift
  from what a user sees on the individual tab.
- Returns a small result object, e.g.:
  ```java
  public class FlipProfitResult {
      FlipMode mode;
      String displayName;   // "Bone Flip", "Trapdoor Flip", etc.
      double profit;
      double marginPct;
      boolean hasData;       // false if required saved prices are missing/zero
  }
  ```
- If required prices for a mode aren't populated yet (e.g. user never ran
  that flip before), return `hasData = false` rather than computing off
  zeros — this should surface in the popup as "No data" rather than a fake
  $0 or negative-infinity profit.

---

## Part 2: Sequential batch scanning

`AutoFlipCalcHandler` currently only scans one `FlipMode` at a time, and
`finishAutoScan()` always switches `ProfitDetailsScreen.selectedTab` and
reopens the screen when a scan finishes — that's fine for a single manual
check, but would cause 7 jarring tab switches/screen rebuilds in a row
during a batch run.

Add a batch mode:
```java
public static void startBatch(List<FlipMode> modes, Runnable onAllComplete)
```
- Runs each mode's existing scan sequence one after another, reusing all
  the current scanning/pagination logic unchanged.
- While in batch mode, `finishAutoScan()` should **skip** the
  tab-switch-and-reopen-screen step (add a flag, e.g. `batchModeActive`,
  checked at the point where it currently calls
  `mc.setScreen(new ProfitDetailsScreen())`) — just save the prices to
  config silently and move to the next queued mode.
- After the last mode in the batch finishes, call `onAllComplete.run()`.
- Show a chat progress message between each mode, same pattern as existing
  scans: `"§a[Auto Flip] Checking All Flips... (%d/7) %s"`.
- Guard against overlapping runs: if `isRunning()` is already true,
  "Check All" (and the individual "Auto Check Prices" buttons) should do
  nothing / show a message that a scan is already in progress, rather than
  queuing on top of each other.

---

## Part 3: "Check All" button

In `ProfitDetailsScreen.java`'s header bar (`headerRight`), add a button
next to the existing ones:
```java
ButtonComponent checkAllBtn = UIComponents.button(Component.literal("Check All"), btn -> {
    AutoFlipCalcHandler.startBatch(
        List.of(FlipMode.BONE, FlipMode.KELP, FlipMode.OAK_LOG, FlipMode.STICKY_PISTON,
                FlipMode.GOLDEN_APPLE, FlipMode.BOOKSHELF, FlipMode.TRAPDOOR),
        this::openCheckAllResultsModal
    );
});
```
While a batch scan is running, disable the button or change its label to
something like `"Scanning... (%d/7)"` so it's clear it's mid-run, and
re-enable it once `onAllComplete` fires.

---

## Part 4: Results popup

Add `openCheckAllResultsModal()`, styled the same way as the existing
`openPriceDumperResultsModal()` (overlay + `modalCard` +
`ScrollContainer` + row list) — reuse that visual pattern, don't invent a
new one.

- Header: `"Flip Profitability (7 Flips Checked)"` with a close button.
- List rows, one per flip, **sorted descending by `profit`** (highest
  first). If a mode has `hasData = false`, sort it to the bottom
  regardless of any stale/zero profit value, and show `"No data"` in place
  of the numbers for that row.
- Each row shows: flip name, profit (green if positive, red if negative —
  same color convention as `netProfitLabel` elsewhere in the file), and
  margin %.
- A "Close" button at the bottom, same pattern as the Price Dumper modal's
  close button.

---

## Part 5: Sanity checks before calling this done

- Confirm the extracted `computeProfitForMode()` for at least 2 modes
  produces numbers that match what's currently shown when you manually
  open that tab — take a screenshot of the tab and the popup row
  side-by-side to confirm they agree exactly, not just approximately.
- Confirm `finishAutoScan()`'s existing single-flip behavior (tab switch +
  screen reopen) is unchanged when `AutoFlipCalcHandler.start(mode)` is
  called directly (i.e. outside of batch mode) — this should keep working
  exactly as it does today for the individual "Auto Check Prices" buttons.
- Confirm clicking "Check All" while a scan is already running does not
  start a second overlapping scan.

Please output the full diff for the affected files so I can review before
applying.
