# seed_foods.json

Prototype seed: ~70 everyday foods with per-100 g values typical of USDA SR Legacy,
rounded, with common household portions. Values are approximate and were written
by hand for the prototype.

Design basis §4.3 calls for the full USDA Foundation + SR Legacy datasets built
into a prepopulated database by a script. That replaces this file; until then,
any miss here falls through to the FDC API, so nothing is unreachable — it is
just one network call slower.

Ids are stable (`seed:*`) so re-seeding is an upsert, never a duplicate.
