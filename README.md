# GlassCut

A liquid-glass background remover for Android. One HTML file is the entire app; the APK is a thin WebView shell that adds the two things a browser tab can't do — a real photo picker and a real *save to gallery*.

Everything runs on the device. No uploads, no accounts, no ads, and the APK asks for **no internet permission at all**.

| | |
|---|---|
| **APK** | [`GlassCut.apk`](GlassCut.apk) — signed, 206 KB, Android 7.0+ |
| **Web app** | [`index.html`](index.html) — open it in any browser, no build step |
| **Package** | `com.glasscut.app` |
| **Version** | 1.1.7 (versionCode 7) |

---

## What it does

Cut a person or object out of a photo, then put it on a colour, a gradient, a blurred version of the original, or another photo of your own.

- **Auto runs by itself** — pick a photo and the background is already gone; open Background and choose. The Auto button is there if you want to run it again after changing tolerance. Finds the background from the border, floods it out, and snaps the edge back at full resolution. Objects standing in the background (a chair, a plant, a second person) stay — see [Objects inside the background](#objects-inside-the-background).
- **Wand** — tap any colour to knock it out (tolerance adjustable).
- **Erase / Restore / Keep** — paint with a finger, brush size up to 140 px. **Keep** protects an object so nothing can remove it.
- **Edge Grow / Shrink / Smooth** — fix a halo or a ragged cut.
- **Subject controls** — size, position, opacity, flip, white outline, drop shadow.
- **Canvas sizes** — original, 1:1, 4:5, 9:16, 16:9.
- **Save** — PNG (transparent), JPG or WEBP, straight into `Pictures/GlassCut`.
- **Glass UI** — transparency, blur and bounce are real sliders, not decoration.
- **Hinglish / English** — the whole UI switches, and the choice is remembered.

Undo/redo is 15 steps deep, and `Ctrl/⌘ + Z`, `Ctrl/⌘ + Y`, `Esc` work on desktop. Drag-and-drop or paste an image into the page on desktop.

## Screenshots

| Empty | Photo loaded | Auto cutout |
|---|---|---|
| ![empty](screenshots/01-empty.jpg) | ![photo](screenshots/02-photo.jpg) | ![cutout](screenshots/03-cutout.jpg) |

| Background sheet | Gradient applied | Remove tools |
|---|---|---|
| ![bg](screenshots/04-background.jpg) | ![gradient](screenshots/05-gradient.jpg) | ![refine](screenshots/06-refine.jpg) |

| Options | Final render | Saved |
|---|---|---|
| ![options](screenshots/07-options.jpg) | ![final](screenshots/08-final.jpg) | ![saved](screenshots/09-saved.jpg) |

---

## What was broken, and what changed

The starting point was a single HTML file that looked finished but didn't work end to end. The design is untouched — same layout, same glass, same springs. Everything below is logic.

### Save was fake
The old `save()` called `window.claude.use('downloads')` — an API that only exists inside one particular preview environment. Everywhere else it silently fell through to an overlay that told you to *long-press the image and save it manually*. No file was ever written.

| Before | After |
|---|---|
| ![before](screenshots/before-02-save-broken.jpg) | ![saved](screenshots/09-saved.jpg) |

Now `save()` picks the best real path in order:

1. `GlassCutAndroid.saveImage()` — the APK writes into the gallery through MediaStore (`Pictures/GlassCut`), with a storage-permission request on Android 7–9.
2. Web Share API on phones, so iOS Safari can put the file in Photos.
3. A normal `<a download>` blob download on desktop.
4. The long-press overlay only if all three are unavailable — which is now rare.

It also falls back to PNG if the browser refuses `image/webp`, instead of failing with "Save nahi hua".

### Auto-removal left half the background behind
The old algorithm compared every pixel to one fixed colour with a single tolerance. On a portrait with a blurred backdrop it removed the flat areas and left every bokeh blob floating in the cutout.

The new cutout is coarse-to-fine:

1. Downscale 4× — a blurred background collapses into one nearly uniform colour.
2. Flood the border colour model at low resolution, then **drop leftover islands**: any foreground blob smaller than half the largest one becomes background again.
3. Upsample the mask and run a **level-limited flood at full resolution** (max 7 px) so the edge snaps to the real pixel boundary instead of the blocky 4× version.

Opaque pixel coverage on the same test photo: **70 % → 31 %**, with the subject intact and no bokeh left over.

| Before | After |
|---|---|
| ![before](screenshots/before-01-old-cutout.jpg) | ![cutout](screenshots/03-cutout.jpg) |

## Objects inside the background

The first version kept only the **largest** blob and threw away every other island it found. On a photo with a plant, a stool and a ball, the plant survived and everything else vanished — the exact thing a background remover must not do.

1.0.1 keeps them. Two changes:

1. **A second flood pass at 1.7× tolerance**, seeded from the background that was already removed. Shadowed patches, bokeh rims and other off-shade pieces of the *same* background get absorbed, because they sit within reach of a background colour.
2. **A smarter island rule.** Under the new *Objects bachao* switch (on by default) an island is only dropped when it is a speck (under 0.15% of the photo) or when its average colour is within 1.15× tolerance of a background colour — i.e. it really is leftover background. Anything else survives, whatever its size or position.

| 1.0.0 — only the plant survived | 1.0.1 — everything survived |
|---|---|
| ![lost](screenshots/before-03-objects-lost.jpg) | ![kept](screenshots/12-objects-kept.jpg) |

And once the background is gone, the new one goes behind **every** object, not just the main subject:

![new background](screenshots/13-objects-new-background.jpg)

**The switch.** *Refine → Objects bachao*. Leave it on and the app keeps everything that isn't background; turn it off for the old behaviour, where only the main subject is kept. Turn it off for portraits where you only want the person and the bokeh behind them can go. The switch lives in the Refine sheet, so the top bar and the dock are unchanged.

![switch](screenshots/11-refine-english.jpg)

## 3D tilt, depth and 4D motion

Three new sliders under **Options → 3D & Depth**. All three are baked into the saved file, not just the preview.

| Control | What it actually does |
|---|---|
| **Depth** 0–100 | Subject scales up to +7%, the drop shadow deepens and moves, and a radial vignette darkens the background. This is what makes the person read as a separate layer instead of a sticker. |
| **3D Tilt** −30…+30 | A perspective shear around the subject's own centre — a vertical-axis rotation, `cos` on the horizontal scale plus a `sin` shear, so it reads as 3D rather than as a skew. |
| **4D Motion** 0–100 | Live parallax. The subject layer and the background move in opposite directions. Driven by `deviceorientation` when the phone reports it, otherwise a slow automatic sway. Runs at ~16 fps so it costs nothing while you edit. |

| 3D & Depth controls | Result with depth 78, tilt 20, motion 60 |
|---|---|
| ![controls](screenshots/19-3d-depth-controls.jpg) | ![depth](screenshots/20-depth-effect.jpg) |

Save captures whatever the effect is showing at that moment, so the exported PNG/JPG has the tilt, shadow and vignette in it.

## Blue blur — one tap, only the person

The quickest result in the app, and the one that survives a bad cutout.

**Background → Blue blur.** It does three things at once:

1. Sets the blur amount to 48 — a 1/79 downscale, so the original background becomes an unreadable wash.
2. Lays a strong blue tint (`#0a5cff` at 72%) over it.
3. Switches to subject-only mode and re-cuts, so leftover background islands are dropped instead of floating in the blue.

| Background sheet | Result |
|---|---|
| ![sheet](screenshots/16-blue-blur-sheet.jpg) | ![blue](screenshots/17-blue-blur-result.jpg) |

The Blur Amount slider now goes to **60** instead of 20, so "Blur original" can also be pushed until nothing is recognisable — without the blue.

Save works the same as always: PNG, JPG or WEBP into `Pictures/GlassCut`.

## Background change is automatic

The complaint that started 1.0.3: *pick an image, change its background, and leave the person exactly as they are.* Two things were in the way.

**Auto did not run on its own.** You had to press Auto before the Background sheet did anything — until then the new background was painted *behind an opaque photo*, so nothing appeared to change. Now the cutout runs the moment a photo loads. Pick photo → background already gone → open Background → pick a colour, gradient, blur or your own image. One tap, and the person is untouched.

![auto on load](screenshots/12-auto-on-load.jpg)

**The edge was being eaten.** The final edge-snapping pass could creep 7 px into the subject at a soft boundary, which is what chewed hair and left a white halo around faces. It is now capped at 3 px with a tighter colour step, and the coarse pass runs at half resolution instead of a quarter.

| before 1.0.3 — halo, chewed edge | 1.0.3 — clean edge |
|---|---|
| ![halo](screenshots/before-01-old-cutout.jpg) | ![clean](screenshots/03-cutout.jpg) |

**What still needs a hand.** If the subject's hair or clothing is almost the same colour as the background, no colour-based flood can tell them apart — that is a job for the **Keep** brush: paint the hair once, press Auto, done. Honest limit, not a bug.

### The Keep brush — the guaranteed one

Automatic removal is a guess. *Keep* is not. Paint over anything with the Keep tool and it is locked: the flood cannot cross it, Auto cannot take it, Grow/Shrink/Invert/Smooth all respect it. It survives even at maximum tolerance.

| Keep selected, painted over the object | Auto at tolerance 110 — object survives, everything else gone |
|---|---|
| ![keep](screenshots/14-keep-tool.jpg) | ![kept](screenshots/15-keep-new-background.jpg) |

What you paint stays, halo included — so paint tight. If you painted too much, switch to **Erase** and go over the extra: erasing clears the protection as well as the pixels. Keep strokes are part of the undo history, so `Undo` takes the protection back too.

**How to use it for your case:** Keep the object → Auto → the object and its own edges are untouched → *Background* → pick a colour, gradient or photo. The new background goes behind the object.

Honest limitation: a soft out-of-focus highlight and a soft-edged object look identical to a pixel test. With the switch **on**, an out-of-focus highlight in the background is kept, because the app would rather keep an object than delete one. Tap it away with the Wand — that is what the Wand is for.

### Smaller fixes

- **Undo/redo could corrupt state** — the edge-smooth pass read from the live mask while writing to it.
- **Canvas upscaling** — picking a crop ratio forced a 1400 px canvas even for a small photo, so the subject went soft. The canvas is now capped at the photo's own long edge.
- **Brush ring was the wrong size** — it was drawn in screen pixels from an image-pixel radius, so it ballooned on small canvases. It now tracks the live zoom.
- **Wand left an empty undo step** when nothing matched, so one tap of Undo appeared to do nothing.
- **Blur background re-rendered every frame** — now cached by canvas size and blur amount.
- **Language toggle ate whitespace** — text nodes were replaced with trimmed strings, shifting the layout on every switch. Only the words are swapped now.
- **Pointer capture was never released**, so a stroke could stick after leaving the canvas; `pointerleave` now ends it.
- **Memory** — undo history trimmed to 15 full-resolution masks.
- **Objects in the background were deleted** — the island filter kept only the biggest blob. Fixed in 1.0.1, see above.
- **Safe areas** — the page reads `--sat` / `--sab` CSS variables, which the Android shell feeds from real window insets, so the header clears a notch and the dock clears the gesture bar.

Verified in headless Chrome: load → auto → wand → erase → undo → redo → reset → re-cut → save → language switch → close, with zero console errors, and the saved file checked on disk.

---

## Install the APK

Download [`GlassCut.apk`](GlassCut.apk) on the phone, tap it, allow "install unknown apps" for your browser or file manager once. Android 7.0 (API 24) or newer.

**This build has a new signing key** (the keystore was lost again when the build sandbox was reset on 1 Nov 2026), so Android will refuse to install it over 1.1.6 or earlier — uninstall the old app once, then install. Certificate SHA-256 for 1.1.7 and later — no uninstall needed after this one. Self-signed 2048-bit RSA, v1+v2+v3, valid 30 years. SHA-256 of the certificate:

```
6b75ef837103ce4c54660a8ac027f6a278517e0c2bcad1ae38fc1924a569bb5e
```

## Build it yourself

The APK is built without Gradle — `aapt2`, `javac`, `d8`, `zipalign`, `apksigner` straight from the Android SDK, which keeps the whole toolchain under 300 MB and the build under a minute.

```bash
# needs: JDK 17, Android SDK build-tools 34, platform 34
bash build.sh            # -> GlassCut.apk
```

`build.sh` expects the SDK at `/scratch/work/sdk` and the JDK at `/scratch/work/jdk`; edit the two paths at the top for your machine.

Layout:

```
index.html                      the whole app — UI, canvas engine, mask tools
app/assets/index.html           the same file, shipped inside the APK
app/java/com/glasscut/app/      WebView shell: file chooser + gallery bridge + insets
app/res/                        icons, theme, strings
build.sh                        aapt2 → javac → d8 → zipalign → apksigner
```

### How the shell talks to the page

```js
// page → native
GlassCutAndroid.saveImage(dataUrl, "glasscut-123.png", "image/png");  // "ok:<uri>" | "err:<why>"
GlassCutAndroid.toast("message");

// native → page, on every inset change
document.documentElement.style.setProperty('--sat', '28px');
```

## Licence

MIT. Do what you like with it.
