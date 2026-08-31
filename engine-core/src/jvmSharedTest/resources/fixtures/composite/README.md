# Composite fixture conventions

Shared by every `*.txt` in this directory. `CompositeTest` reads the data
rows; this file explains how to check one by hand. It lives here, once,
because the same block used to be copied into all eight files and drifted
twice — rounds 9 and 11 both fixed headers in some files and not others.

Convention, so a row can be checked by hand: the source is scaled by the
opacity column first (premultiplied, so colour and alpha together), then
combined per docs/plan/05-layers.md section 4 --
Co = s.rgb*(1 - d.a) + d.rgb*(1 - s.a) + both(s, d)
Ao = s.a + d.a*(1 - s.a)
both(s, d) = s.a*d.a*B(d.rgb/d.a, s.rgb/s.a)
-- with round-to-nearest on store, ties away from zero. NORMAL.txt's last
row is the one that discriminates that from half-even: 126.5 stores as 127,
where half-even would give 126. It is the only file with that row; every
other file ends on the zero-opacity row, which is an exact pass-through and
settles no tie.

both() divides by BOTH alphas -- d.rgb/d.a and s.rgb/s.a -- so it is 0 by
definition, not 0/0, whenever either one is zero. Three rows pin that:

- the fully-transparent-source row and the zero-opacity row (s.a = 0), where
  the result is the destination unchanged;
- the transparent-destination row, row 4 of every file (d.a = 0), where the
  result is the source. Note it is NOT "the destination unchanged" -- the
  destination is nothing there.

That is exactly the hazard they pin: an implementation that divides without
guarding zero alpha propagates NaN. Row 4 catches it for every mode except
NORMAL, which ignores B's first argument and survives an unguarded d.a.

In both(), the mode B is applied to the UNPREMULTIPLIED channels and
weighted by the joint coverage. Section 4's table writes that out already
simplified into premultiplied terms (e.g. DARKEN's min(s.rgb*d.a,
d.rgb*s.a)), which is the same arithmetic, so every row here also equals
the W3C result. Applying the mode straight to the stored premultiplied
bytes is a THIRD thing and gives different values -- do not hand-verify
that way.
