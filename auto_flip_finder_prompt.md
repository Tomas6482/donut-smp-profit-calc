# Prompt for Antigravity: Auto Flip Finder

I want to add a new flip mode called **"Auto Flip Finder"** to the mod. Unlike
the existing hardcoded flips (Bone, Kelp, Oak Log, Sticky Piston, Golden
Apple, Bookshelf), this mode should automatically discover profitable flips
across many recipes instead of requiring one hardcoded per item.

Please implement this in the parts below, in order. Show me the diff for each
part before moving to the next if anything is ambiguous — don't guess on
recipe ambiguity or pricing edge cases, ask or skip and log it instead.

---

## Part 1: Recipe data model

Create `FlipRecipe.java` in `com.dsmp.profitcalc.client.flipfinder`:

```java
public class FlipRecipe {
    public final String outputItem;       // canonical item ID, e.g. "bookshelf"
    public final double outputQtyPerCraft; // usually 1, but some recipes yield >1
    public final List<Ingredient> ingredients;

    public record Ingredient(String itemId, double qtyPerCraft) {}
}
```

Create `FlipRecipeRegistry.java` that builds the full list of candidate
recipes from two sources:

1. **Vanilla recipes**, pulled live from
   `mc.level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)` —
   extract `getResultItem()` and `getIngredients()` for each
   `CraftingRecipe`. Skip recipes with an ingredient that resolves to
   multiple valid items (tag-based ingredients like "any plank") unless it
   can be resolved to the single variant already being scanned. For
   ambiguous cases, skip the recipe entirely rather than guessing, and log
   which ones were skipped and why.

2. **Custom recipes**, loaded from a JSON file at
   `config/dsmp-profitcalc/custom_recipes.json` (same config folder pattern
   as the rest of the mod), for recipes Donut's plugin adds that aren't real
   registered Minecraft recipes. Format:

   ```json
   [
     { "output": "bookshelf", "outputQty": 1, "ingredients": [
         { "item": "oak_planks", "qty": 6 },
         { "item": "book", "qty": 3 }
     ]}
   ]
   ```

   If a recipe's `outputItem` appears in both sources, the JSON entry wins
   (plugin override). If the file doesn't exist yet, create it seeded with
   the 6 flips already known (Bone → Bone Block, Raw Kelp → Dried Kelp →
   Dried Kelp Block, Oak Log → Oak Planks, Slimeball + Piston → Sticky
   Piston, Gold Ingot + Apple → Golden Apple, Oak Planks + Book →
   Bookshelf), and log a message telling the user where the file is so they
   can add more by hand later.

---

## Part 2: Scanning engine

Create `FlipFinderHandler.java`. Reuse the exact scanning/pagination logic
already proven in `AutoFlipCalcHandler` and `PriceDumperHandler` — don't
reinvent it:

- Same tick-based state machine pattern (`SEND_COMMAND` →
  `WAITING_FOR_SCREEN` → `SCANNING_PAGE` → `PAGING_CLICK`) as
  `PriceDumperHandler`.
- Same stopping condition just implemented in `AutoFlipCalcHandler`: stop
  paging once 3 matches are found for the current key, OR 2 consecutive
  empty pages, OR no next page available, OR a `MAX_PAGES_SAFETY = 25` page
  cap. Named constants, not magic numbers.
- Same qty-threshold split as `PriceDumperHandler`
  (`DEFAULT_ORDER_MIN_QTY = 16`, `DEFAULT_AH_MIN_QTY = 64`, with fallback to
  lower-qty listings if nothing meets the threshold — same pattern as
  `recordResult()`).
- Build the full scan list by walking `FlipRecipeRegistry`: every unique
  `outputItem` needs an ORDER scan (what we sell into), every unique
  ingredient `itemId` needs an AH scan (what we buy from). Deduplicate — if
  an item is both an output in one recipe and an ingredient in another, only
  scan it once per source type.
- Store the full sorted price list per item per source (not just an
  average) — same as `PriceDumperHandler`'s `currentRawPrices` — because
  Part 3 needs the whole list, not just a top-N average.
- Log progress the same way `PriceDumperHandler` does: chat message like
  `(%d/%d) Running /order <item>...` so the user can see it's alive during a
  long scan.

---

## Part 3: Profit computation — do NOT just average top-3 prices

This is the most important part to get right, and it's deliberately
**different** from how the existing per-flip calculator works. The
calculator currently multiplies craft quantity by a flat top-3 average
price, which produces wildly wrong numbers when the order book is thin (we
saw this: a 434-unit bookshelf craft priced at the top-3 average implied
$2M+ profit, but only 44 buy orders existed total, most far below that
price). Since the Finder ranks many recipes automatically with no human
sanity check in between, it needs to model this correctly by default:

**Buying ingredients (AH, ascending price):**
For each ingredient, simulate filling the required `qtyPerCraft × craftQty`
by buying the cheapest listings first, one at a time, until either the
quantity is filled or listings run out. Sum the actual cost paid, don't just
take an average price and multiply.

**Selling output (ORDER, descending price):**
Same idea in reverse — simulate selling `outputQtyPerCraft × craftQty` into
the order book by filling the highest-priced orders first until either the
quantity is filled or orders run out. Sum the actual revenue received.

**Known limitation to note in a code comment:** listing quantity is
currently always parsed as `qty: 1` for every listing (open bug, tracked
separately). Until that's fixed, treat each listing as supplying exactly 1
unit in this simulation — this is a conservative worst case, not an
overestimate, so it's safe to ship with. Once quantity parsing is fixed,
this simulation should automatically start using real per-listing
quantities with no other logic changes needed.

**Per recipe, compute:**

```java
public class FlipResult {
    String outputItem;
    double craftQtyRequested;      // what the user/finder attempted
    double maxRealisticCraftQty;   // the actual fillable amount, capped by
                                    // the scarcest ingredient's AH depth
                                    // AND the output's ORDER depth
    double totalCost;
    double totalRevenue;
    double totalProfit;
    double marginPct;              // totalProfit / totalCost * 100
    boolean lowConfidence;         // true if any leg (ingredient or output)
                                    // had a thin sample (below some
                                    // configurable threshold, e.g. 5)
}
```

`maxRealisticCraftQty` should default to something reasonable to attempt
per scan (e.g. 100), then get clamped down by whichever ingredient/output
runs out of matching listings first during the fill simulation above.

---

## Part 4: UI — new dropdown entry

In `ProfitDetailsScreen.java`:

- Add `"Auto Flip Finder"` as a 7th entry (index 6) in both the
  `options` array in the dropdown menu and the `activeFlipTitle` switch
  statement.
- Unlike the other 6 tabs, this tab should **not** show price/qty text
  boxes. Instead show a scrollable ranked results list, styled the same way
  as `openPriceDumperResultsModal()`'s table (`tableHeader` +
  `listContainer` inside a `ScrollContainer`). Columns: Item, Total Profit,
  Margin %, Realistic Qty, Confidence (⚠ icon if `lowConfidence`).
- Default sort: descending by `totalProfit` (not `marginPct` — a recipe with
  huge margin but only 2 realistic units isn't as useful as one with
  moderate margin across 80 units). Add a button to toggle sort between
  "By Total Profit" and "By Margin %".
- Replace the "Auto Check Prices" button with "Scan All Flips", which calls
  `FlipFinderHandler.start(FlipRecipeRegistry.getAll())`.
- Persist the last scan's `List<FlipResult>` the same way
  `PriceDumperHandler.latestResults` persists, so results are still visible
  if the user closes and reopens the screen without rescanning.

---

## Part 5: Sanity checks before calling this done

- Recipes skipped for ambiguous ingredients should be visible somewhere
  (log line is fine for now) so it's not a silent gap.
- Zero-match items should be flagged distinctly from "matched but
  unprofitable" — reuse the same `LOGGER.warn("Zero matches...")` pattern
  already in place.
- A recipe where cost data or revenue data is entirely missing should be
  excluded from the ranked list, not shown with a fabricated $0 or infinite
  margin.

Please output the full new files and the diff for `ProfitDetailsScreen.java`
so I can review before applying.
