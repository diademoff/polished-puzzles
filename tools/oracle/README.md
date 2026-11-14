# Host oracle

The oracle is the vendored C engine built for the host. The parity
tests compare the app against it. It is a development tool: it does
not ship in the APK.

## Build

```sh
tools/oracle/build.sh
```

Output: `build/oracle/puzzles-oracle`.

The script reads `app/src/main/jni/CMakeLists.txt` on every run:

- the game list uses the same rule as `GenerateBackendsTask`, so the
  oracle and the app always agree on the set of games;
- the core source list is the `core_obj` object library.

There is no second copy of either list. `hat.c`, `spectre.c`, and
`list.c` are added by `CMakeLists.txt` because upstream keeps them
outside `core_obj`.

The build needs `cmake`, a C compiler, and `perl`. It does not need
GTK, SDL, the Android SDK, or the network.

## Compile definitions

The oracle uses the same definitions as `cmake/platforms/android.cmake`:
`ANDROID`, `COMBINED`, `SMALL_SCREEN`, `STYLUS_BASED`, `NO_PRINTING`,
`VIVID_COLOURS`.

This is deliberate. The parity contract is between the app and the
oracle, and these definitions change observable behavior:

- `SMALL_SCREEN` removes the largest preset in several games;
- `ANDROID` changes key and pencil handling in some games;
- `VIVID_COLOURS` changes the board palette.

Without them the oracle would describe the desktop build, and every
preset and key test would compare two different programs.

## Protocol

Line-based JSON, one request per line, one response per line. The
oracle is stateless: a request that needs a game carries the serialised
save file.

Requests:

```
{"op":"games"}
{"op":"presets","game":"mines"}
{"op":"new","game":"fifteen","params":"4x4","seed":"12345"}
{"op":"key","state":"...","x":90,"y":210,"k":512}
{"op":"undo","state":"..."}
{"op":"redo","state":"..."}
{"op":"solve","state":"..."}
{"op":"text","state":"..."}
{"op":"status","state":"..."}
{"op":"id","state":"..."}
{"op":"keys","state":"...","seq":"108 108 522"}
{"op":"trace","state":"..."}
```

`keys` replays a whole key sequence inside one midend. The cursor of a
cursor game lives in the frontend state, not in the save file, so a
sequence must not be split into separate `key` requests. The sequence is
a space- or comma-separated list of button codes; the pointer is `0,0`.

`trace` records one redraw through the recording drawing API. It is the
strict trace described in `docs/adr/0003-strict-draw-traces.md`.

Responses:

```
{"ok":true,"state":"...","id":"...","game":"Fifteen","w":240,"h":240,
 "canundo":true,"canredo":false,"status":0}
{"ok":true,"trace":{"traceSchema":1,"game":"Fifteen","id":"...",
 "params":"4x4","w":240,"h":240,"palette":[[800,800,800],...],
 "ops":[{"op":"start_draw"},...]}}
{"ok":false,"error":"..."}
```

Notes:

- `state` is the output of `midend_serialise`. It carries parameters,
  the description, the move history, and the position in that history.
  Two states are equal only if the whole history is equal; compare
  `text` for the current position.
- `id` is `midend_get_game_id()`, that is `params:DESC`. It is the
  golden for `params + seed -> DESC` and it does not change with moves.
- `w` and `h` are the pixel size of the preferred board. Key positions
  are pixels on that board, so tests can derive them from this size.
- `k` is a button code: `LEFT_BUTTON` is 512, `RIGHT_BUTTON` is 514,
  and cursor keys are in `puzzles.h`.
- `status` follows `midend_status()`: `1` is a win, `-1` is a loss, and
  `0` is an open game.

## Limits

- The oracle frontend is written against the drawing API of the
  vendored tree. It does not compile against an older upstream
  revision, and it may need a small change after an upstream sync that
  alters `puzzles.h`. That failure is part of the sync signal.
- Ordinary operations use a no-op drawing API. `trace` uses a recording
  API and returns the primitive stream. There is no rasterizer, so
  pixels are not compared.
- A trace is one first redraw of a fresh midend. The initial clear with
  colour 0 is part of the contract. A game that draws differently on a
  later repaint needs its own trace scenario.
- The trace starts with a fresh `game_ui`: the cursor is hidden and at
  its default position. Cursor position is frontend state and is not
  part of the save file, so it is not part of the trace.
- A crash is a test failure, not a skip.
