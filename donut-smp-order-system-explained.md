# How the Donut SMP `/order` System Works

This is a plain description of the system itself — no mod/code talk, just what it is and
how it behaves. Based on confirmed in-game screenshots.

## The core model

`/order` is a **standing BUY-order board** — not a two-sided marketplace/auction house.

There is only ever one direction you can *post*: a request to buy. There is no "create a
sell listing" option anywhere in the system. The two things you can actually do are:

1. **Post a BUY order** — "I want N of item X, I'll pay $Y each" — and wait for other
   players to deliver the items to you.
2. **Fulfill someone else's BUY order** — deliver items into an existing order someone
   else posted, and get paid instantly for it.

So from your own perspective: posting an order = you buying. Delivering into someone
else's order = you selling. There's no third option and no ambiguity about which
direction a given action is.

---

## Posting a BUY order

Reached through an **"Orders -> Your Orders"** screen — a chest-style GUI showing the
orders you've personally posted, with a filler background of gray glass and one special
slot:

- **`New Order`** — clicking it starts a short wizard.

The wizard is four separate full-screen prompts, one after another:

1. **"Choose Item"** — a search box plus a big alphabetical grid with every item in the
   game as its own button (e.g. Acacia Boat, Bone, Beacon...). You either type in the
   search box or scroll and click the item you want.
2. **"How many?"** — a plain number field, defaults empty/editable, labeled `Amount`.
   `Cancel` or `Next`.
3. **"Price per item?"** — shows your chosen `Amount` back to you, shows a `Minimum: $1`
   floor, and a `Price` field for what you're offering to pay per item. `Cancel` or
   `Review Order`.
4. **"Review Order"** — a final summary: `Item: Bone`, `Amount: 10`, `Price: $50 each`,
   `Total: $500`. Buttons to `Cancel`, `Change Item`, `Change Amount`, `Change Price`, or
   finally **`Create Order`**.

Clicking `Create Order` posts it. Presumably (not directly confirmed by these
screenshots) the total cost is escrowed from your balance up front, since the system is
"other players deliver items and get paid" — implying the money has to already be set
aside somewhere for that payout to be instant.

---

## Browsing and fulfilling other players' orders

Running `/order <item>` (e.g. `/order bone`) opens a different kind of screen — an
ordinary chest/inventory GUI titled **"Orders (Page N)"**. This is not a wizard, it's a
scrollable, paginated grid where **every slot is one individual player's posted order**
for that item. If lots of people are buying bone at once, you'll see many near-identical
Bone icons filling the grid — one per listing, not one aggregated slot per item type.

Hovering a listing shows a tooltip with:
- The item's display name (e.g. "Bone Blocks")
- The price being paid: `$403.21 each`
- Progress so far: `341k/1.5m Delivered` — i.e. 341,000 of the 1,500,000 total requested
  have already been delivered by other players; the order stays open until it's fully
  filled
- `Click to deliver items`

Clicking a listing opens a second screen: **"Orders -> Deliver Items"** — an empty grid
of deposit slots with your own inventory shown underneath. You place item stacks (or
shulker boxes containing the item) up into the empty slots to deliver against that
order. Presumably you get paid per item delivered, at the price shown on the listing,
up to however much of the order is still unfilled.

---

## Summary of the two flows side by side

| | Posting an order | Fulfilling an order |
|---|---|---|
| Entry point | "Your Orders" → `New Order` | `/order <item>` → click a listing |
| Screen type | 4-step wizard (Choose Item → How many? → Price per item? → Review Order) | Two chest GUIs: browse grid → deposit grid |
| What you're doing | Requesting to buy N of an item at a price you set | Delivering items into someone else's existing request |
| Money direction | You pay (presumably escrowed at posting) | You get paid, per item delivered |
| Equivalent to | Buying | Selling |

---

## Things that are still unconfirmed

- Whether the total cost is actually escrowed the moment you click `Create Order`, or
  charged some other way.
- Whether delivering items in the "Deliver Items" screen pays you out instantly per
  stack placed, or only once you close the screen / hit some confirm step not visible in
  these screenshots.
- Whether there's a running "you will receive $X" total shown as you place items into
  the deposit grid.
- Whether you get any in-game notification (toast/chat message) when someone else fully
  or partially fulfills a BUY order you posted.
- Whether an order can be cancelled once posted, and if so, whether the escrowed money
  is refunded.
