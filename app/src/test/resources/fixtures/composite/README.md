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
-- with round-to-nearest on store, ties away from zero. The last row here
is the one that discriminates that from half-even: 126.5 stores as 127,
where half-even would give 126.

When s.a = 0 -- the fully-transparent-source row and the zero-opacity row --
both() is 0 by definition, not 0/0: the unpremultiplied source is undefined
there and must not be computed. Those rows are the destination unchanged.
That is exactly the hazard they pin: an implementation that divides without
guarding s.a = 0 propagates NaN instead of passing the destination through.

In both(), the mode B is applied to the UNPREMULTIPLIED channels and
weighted by the joint coverage. Section 4's table writes that out already
simplified into premultiplied terms (e.g. DARKEN's min(s.rgb*d.a,
d.rgb*s.a)), which is the same arithmetic, so every row here also equals
the W3C result. Applying the mode straight to the stored premultiplied
bytes is a THIRD thing and gives different values -- do not hand-verify
that way.
