# Changelog

1.1.7 — mask scratch buffers reused instead of reallocated (wand flood + coarse-to-fine cutout, island colour sums folded into the labelling pass, per-pixel closure in the full-res snap removed); a no-op Auto/Wand no longer wipes the redo stack; a second finger can no longer fork a stroke — measured in headless Chrome at 1400x1200: Auto 23.1 MB -> 5.05 MB allocated and 861 ms -> 632 ms median, wand tap 11.76 MB -> 3.36 MB, two-finger gesture 2 undo entries -> 1, redo survives a no-op Auto, zero console errors.
