package name.boyle.chris.sgtpuzzles.engine

/**
 * The portable part of upstream `midend.c`.
 *
 * A midend owns the parameters, the seed, the game description, the
 * history of states, and the position in that history. It is the
 * semantic layer: it serialises to the same `SAVEFILE` text as C, it
 * adds and removes history entries the same way, and it exposes the
 * same public flags.
 *
 * The shell-facing parts of upstream `midend.c` (menus, timers, size
 * negotiation, animations) are not here yet. Preferences are: the
 * midend owns `one-key-shortcuts` and the game's [Game.prefs], applies
 * them to the UI, and writes the same file text as C. They have no
 * meaning without the Android shell and they do not affect a saved
 * state. `ADR 0006` records that the midend is ported with its first
 * game, and that the semantic subset is the part the parity matrix
 * checks.
 */
class Midend private constructor(
    val game: Game,
    val params: Game.Params,
    val currentParams: Game.Params,
    val seed: String?,
    initialDesc: String,
    initialPrivDesc: String? = null,
    val auxInfo: String?,
    private val entries: MutableList<Entry>,
    var statePos: Int,
    initialElapsed: Float = 0f,
    initialUi: Game.Ui? = null,
    private val platformSeed: ByteArray = byteArrayOf(0),
) {

    enum class MoveType { NEWGAME, MOVE, SOLVE, RESTART }

    data class Entry(val state: Game.State, val move: String?, val type: MoveType)

    val state: Game.State get() = entries[statePos - 1].state

    /**
     * The current public description, the `DESC` save record and the
     * description in [id]. A move can replace it through
     * `midend_supersede_game_desc`: [processKey] reads
     * [Game.publicDesc] after it appends the move, and a game that
     * resolves a pending description returns a new one. The getter is
     * public and the setter is private, so only the midend changes it.
     */
    var desc: String = initialDesc
        private set

    /**
     * The private description, the `PRIVDESC` save record, or null when
     * the game has none. It is set together with [desc] from
     * [Game.privateDesc]. A load rebuilds state 0 from it and then
     * applies the public [desc] with [Game.setPublicDesc].
     */
    var privDesc: String? = initialPrivDesc
        private set

    /**
     * The frontend state, mirroring upstream `game_ui`. It is not in
     * [State], the history, [id], [status], or [textFormat]. A game
     * that provides `encodeUi` stores it in the save file's `UI`
     * record; a load restores it through `decodeUi`.
     *
     * [platformSeed] is the frontend `get_random_seed` value, not the
     * game seed. It is not saved and does not reach [id], [desc], or
     * [serialise].
     */
    var ui: Game.Ui = initialUi ?: game.newUi(state, platformSeed)
        private set

    /**
     * The preferences of this midend, the counterpart of `me->be_prefs`.
     * It holds `one-key-shortcuts` first and the [Game.prefs] items in
     * schema order, with the values that were last applied. A new midend
     * starts at the schema defaults, as `midend_new` does
     * (`midend.c:240`), and the factories apply their `prefs` argument
     * after the UI exists.
     */
    private var prefs: Prefs = Prefs.defaults(
        listOf(ONE_KEY_SHORTCUTS) + game.prefs,
    )

    /**
     * `me->one_key_shortcuts`: true when the bare letters `n`, `u`,
     * `r`, and `q` act as shortcuts. The `*`, `#`, control-character,
     * and `UI_*` aliases are always active, as in
     * `midend.c:1006-1034`.
     */
    var oneKeyShortcuts: Boolean
        get() = prefs.bool(ONE_KEY_SHORTCUTS.kw)
        set(value) {
            prefs = prefs.withValues(listOf(ONE_KEY_SHORTCUTS.copy(value = value)))
        }

    /**
     * `one-key-shortcuts` followed by [Game.prefs], with the applied
     * values. This is what the preferences dialog shows.
     */
    val prefItems: List<ConfigItem> get() = prefs.items

    /**
     * Parses and applies preferences text. Returns the C error text, or
     * null when the text was applied. A null text changes nothing, as
     * `midend_load_prefs` does. On an error the stored values stay as
     * they were, because C does not call `midend_set_prefs` after a
     * parse error (`midend.c:3289-3290`).
     */
    fun applyPrefs(text: String?): String? {
        if (text == null) return null
        val parsed = try {
            /* The loader starts from the current values. */
            Prefs.parse(prefItems, text)
        } catch (e: PrefsException) {
            return e.message
        }
        prefs = parsed
        game.setPrefs(ui, prefs)
        return null
    }

    /**
     * Applies edited preference items, as `midend_set_config(CFG_PREFS)`
     * does. The `one-key-shortcuts` item is midend state; every other
     * item is a [Game.prefs] value and reaches the game through
     * [Game.setPrefs].
     */
    fun applyPrefs(items: List<ConfigItem>) {
        prefs = prefs.withValues(items)
        game.setPrefs(ui, prefs)
    }

    /**
     * The C preferences file text, one `kw=value` line per item.
     *
     * C's `midend_save_prefs` reads the values back from the live UI
     * through `get_prefs`, so a game that toggles a UI preference in
     * `interpret_move` keeps the toggle in the saved text. The port has
     * no `get_prefs` seam, so this writes the stored [Prefs] instead.
     * `bridges`, `guess`, `map`, `singles`, and `undead` toggle a live
     * UI field directly (`bridges.c:2626`, `guess.c:850`,
     * `map.c:2532`/`:2537`, `singles.c:1571`, `undead.c:1829`), which
     * this text does not see. That is unreachable today: the preferences
     * dialog applies every edit to [Prefs] first, and nothing saves after
     * a live toggle.
     */
    fun serialisePrefs(): String = prefs.encode()

    /**
     * Applies the optional `prefs` argument of a factory.
     *
     * The stored blob is frontend data that the user or an older build
     * may have left behind. C loads it with `midend_load_prefs` and
     * ignores the error (`android.c:648-656`, `:830-849`), so a blob
     * that no longer parses leaves the current values in place and the
     * game still starts. The Kotlin factories do the same; [applyPrefs]
     * returns the C message but nothing here throws it.
     */
    private fun applyInitialPrefs(text: String?) {
        applyPrefs(text)
    }

    /**
     * The drawing cache, mirroring upstream `me->drawstate`. The midend
     * owns one at a time. It is not part of the save file.
     *
     * A new game, a forced redraw (a resize or a zoom change), a tile
     * size change, or a different sink frees the old cache through its
     * own sink before the next one is created. An ordinary move, undo,
     * redo, solve, and restart keep it, as C does: those paths call
     * `midend_redraw` and do not touch `me->drawstate`. The shell frees
     * the last one with [freeDrawState] when the game view goes away.
     */
    var drawState: Game.DrawState? = null
        private set

    /** The sink that created [drawState], so a free reaches the same one. */
    private var drawStateSink: DrawSink? = null

    /** The tile size [drawState] was created for. */
    private var drawStateTileSize = 0

    /**
     * True when the next draw must free the cache and create a new one:
     * after [forceRedraw] or after a new game.
     */
    private var drawStateDirty = true

    /**
     * True when [drawState] has not been drawn yet, mirroring upstream
     * `me->first_draw`. The first redraw of a drawstate clears the whole
     * window and updates the whole window, because the front end does
     * not guarantee the initial contents. Every later redraw of the same
     * drawstate leaves both out and draws only the game's operations.
     */
    private var firstDraw = true

    /** Set when `changedState` reports a completion; cleared by a take. */
    private var completedEvent = false

    /**
     * The elapsed seconds of a timed game, the counterpart of
     * `me->elapsed`. A save stores it in the `TIME` record and the
     * status text shows it as `[m:ss] `. An untimed game keeps 0.
     */
    var elapsed: Float = initialElapsed
        private set

    /**
     * True while the clock runs, the counterpart of `me->timing`.
     * [refreshTimer] keeps it equal to `game.isTimed &&
     * game.timingState(state, ui)`.
     */
    var timing: Boolean = false
        private set

    /**
     * The shell's timer switch, the counterpart of `activate_timer` and
     * `deactivate_timer`.
     *
     * The setter calls the callback with the current [timing] value, so
     * a shell that attaches after a new game or a load starts in the
     * right state instead of waiting for the next transition.
     * [refreshTimer] calls it again only when the value changes.
     */
    var timerCallback: ((Boolean) -> Unit)? = null
        set(value) {
            field = value
            value?.invoke(timing)
        }

    init {
        // `midend_new_game` and `midend_load` call `midend_set_timer`
        // before they return, so the shell starts in the loaded state.
        refreshTimer()
    }

    /**
     * The mouse button that is currently held, or 0 when none is. It is
     * frontend state, as upstream `me->pressed_mouse_button` is: it is
     * not serialised, and a new or a loaded midend starts at 0.
     */
    private var pressedMouseButton = 0

    val stateCount: Int get() = entries.size

    val canUndo: Boolean get() = statePos > 1

    val canRedo: Boolean get() = statePos < entries.size

    /** The tile size the oracle reports for a large window. */
    val tileSize: Int get() = game.preferredTileSize

    val size: Size get() = game.computeSize(params, ui, tileSize)

    /**
     * `midend_size(..., user_size = true)`: the largest tile size that
     * fits in [width] x [height]. The doubling and the binary search are
     * the same as C, so the result is identical for odd and even tiles.
     */
    fun largestTileFor(width: Int, height: Int): Int {
        var maximum = 1
        while (true) {
            val candidate = game.computeSize(params, ui, maximum)
            if (candidate.width > width || candidate.height > height) break
            maximum *= 2
        }
        var minimum = 1
        while (maximum - minimum > 1) {
            val middle = (maximum + minimum) / 2
            val candidate = game.computeSize(params, ui, middle)
            if (candidate.width <= width && candidate.height <= height) {
                minimum = middle
            } else {
                maximum = middle
            }
        }
        return minimum
    }

    /**
     * The geometry the app should draw and read input with for a view of
     * [viewWidth] x [viewHeight]. The board is centred in the view.
     */
    fun geometryFor(viewWidth: Int, viewHeight: Int): BoardGeometry {
        val tile = largestTileFor(viewWidth, viewHeight)
        val board = game.computeSize(params, ui, tile)
        return BoardGeometry(
            tileSize = tile,
            originX = (viewWidth - board.width) / 2,
            originY = (viewHeight - board.height) / 2,
            width = board.width,
            height = board.height,
        )
    }

    /** `midend_get_game_id`: short parameters, a colon, and the description. */
    fun id(): String = game.encodeParams(currentParams, false) + ":" + desc

    fun setCursorVisible(visible: Boolean) = game.setCursorVisible(ui, visible)

    fun cursorLocation(tileSize: Int): Game.CursorRect? =
        game.cursorLocation(ui, state, tileSize)

    fun status(): Int = game.status(state)

    /**
     * `midend_rewrite_statusbar`: the game text, prefixed with the
     * elapsed clock as `[%d:%02d] ` when the game is timed. The prefix
     * is always present for a timed game, even before the first second.
     */
    fun statusText(): String {
        val text = game.statusText(state, ui)
        if (!game.isTimed) return text
        val seconds = elapsed.toInt()
        val minutes = seconds / 60
        return "[$minutes:${(seconds % 60).toString().padStart(2, '0')}] $text"
    }

    /**
     * `midend_timer`: adds [tplus] seconds to the clock while [timing]
     * is true. Returns true when the whole second shown by [statusText]
     * changed, so the shell can redraw the status line.
     *
     * The C midend also advances its animation and flash clocks here.
     * The seam has neither, so this call only advances the game time.
     * The timer state is refreshed on every call, as C does.
     */
    fun timerTick(tplus: Float): Boolean {
        var secondChanged = false
        if (timing) {
            val oldElapsed = elapsed
            elapsed += tplus
            // The C test is `(int)oldelapsed != (int)me->elapsed`.
            secondChanged = oldElapsed.toInt() != elapsed.toInt()
        }
        refreshTimer()
        return secondChanged
    }

    /**
     * `midend_set_timer`: recomputes whether the clock runs and tells
     * the shell only when the value changes. The midend calls it after
     * every move, undo, redo, solve, restart, a new game, and a load,
     * as C does.
     */
    private fun refreshTimer() {
        val next = game.isTimed && game.timingState(state, ui)
        if (next == timing) return
        timing = next
        timerCallback?.invoke(next)
    }

    /**
     * `midend_text_format`: the state as text.
     *
     * C returns NULL for a game that cannot format as text
     * (`can_format_as_text_ever` is false) and for a game whose
     * params-aware gate rejects the current parameters
     * (`can_format_as_text_now`, `midend.c:2087-2094`), so the seam uses
     * the empty string for both cases and does not call the game's
     * `textFormat`. A caller that must tell "no text form" from "an
     * empty board" checks [Game.canFormatAsTextNow] with the current
     * parameters before it calls this.
     */
    fun textFormat(): String =
        if (game.canFormatAsText && game.canFormatAsTextNow(currentParams))
            game.textFormat(state)
        else
            ""

    /**
     * Consumes the "the last transition completed the game" event. The
     * midend sets it where upstream calls `android_completed`, so each
     * completion is observed exactly once: a pending event is returned
     * by one call and cleared by that call, and a later call returns
     * false until a new completion is raised.
     */
    fun takeCompletedEvent(): Boolean {
        val raised = completedEvent
        completedEvent = false
        return raised
    }

    fun undo(): Boolean {
        if (!canUndo) return false
        if (game.changedState(ui, state, entries[statePos - 2].state)) {
            completedEvent = true
        }
        statePos--
        refreshTimer()
        return true
    }

    fun redo(): Boolean {
        if (!canRedo) return false
        if (game.changedState(ui, state, entries[statePos].state)) {
            completedEvent = true
        }
        statePos++
        refreshTimer()
        return true
    }

    /**
     * Starts the same description again, as `midend_restart_game`. The
     * new state is a `RESTART` entry, so undo returns to where the
     * restart was pressed.
     *
     * At the first state upstream returns immediately: there is nothing
     * to restart, so no entry is appended, the redo branch is kept, and
     * the history flags do not change. This call then returns false.
     *
     * The cache is kept, as `midend_restart_game` keeps `me->drawstate`
     * and reaches only `midend_redraw`. A caching game must refresh the
     * cached parts that the new state invalidates in `redraw`, as the C
     * games do.
     */
    fun restart(): Boolean {
        if (statePos == 1) return false
        val previous = state
        val restarted = game.newState(params, desc)
        purgeRedo()
        entries += Entry(restarted, desc, MoveType.RESTART)
        statePos++
        if (game.changedState(ui, previous, restarted)) completedEvent = true
        refreshTimer()
        return true
    }

    /** Drops the redo branch, as `midend_purge_states`. */
    fun purgeStates() = purgeRedo()

    /**
     * `midend_solve`: appends the `SOLVE` entry and returns null, or
     * returns the C message. The messages are the three branches of
     * `midend.c:2096-2123`: no solver, no move (with the game's text when
     * it has one), and a move that `execute_move` rejects.
     *
     * The game also receives `states[0]`, because C passes it as the
     * `orig` argument (`midend.c:2109-2111`). A game that solves from
     * the first state, such as `solo`, uses it.
     */
    fun solveAttempt(): String? {
        if (!game.canSolve) {
            return "This game does not support the Solve operation"
        }
        val previous = state
        val outcome = game.solveGame(previous, auxInfo, entries[0].state)
        val move = outcome.move ?: return outcome.error ?: "Solve operation failed"
        val solved = game.executeMove(previous, move)
            ?: return "Solve unavailable (already solved?)"
        purgeRedo()
        entries += Entry(solved, move, MoveType.SOLVE)
        statePos++
        // `midend_solve` calls changed_state for its UI effect but ignores
        // the return value, so a solve never raises the completion event.
        game.changedState(ui, previous, solved)
        refreshTimer()
        return null
    }

    /** The `SOLVE` history entry, or false when the solve failed. */
    fun solve(): Boolean = solveAttempt() == null

    /**
     * Applies one frontend key, as `midend_process_key`.
     *
     * The button is canonicalised before the game sees it: a drag or a
     * release belongs to the button that is held, an overlapping press
     * first releases the previous button, `CTRL`+ASCII becomes a control
     * character, `CTRL+SHFT+Z` becomes redo, a modifier that the game
     * does not expect is rejected or stripped, and Enter, Space, and DEL
     * become the cursor select buttons and backspace. The rules and
     * their order follow `midend.c`.
     *
     * The state history follows upstream: a real move truncates the redo
     * branch and appends one entry; a no-effect key changes nothing.
     */
    fun processKey(x: Int, y: Int, button: Int, tileSize: Int): KeyOutcome {
        var key = button
        var outcome = KeyOutcome.Unused

        /*
         * Harmonise mouse drag and release messages. A frontend can
         * switch the button or the modifier half way through a drag, so
         * the midend maps the event to the button it knows is held. An
         * event with no held button is ignored.
         */
        if (Buttons.isMouseDrag(key) || Buttons.isMouseRelease(key)) {
            if (pressedMouseButton == 0) return KeyOutcome.Unused
            key = pressedMouseButton + if (Buttons.isMouseDrag(key)) {
                Buttons.LEFT_DRAG - Buttons.LEFT_BUTTON
            } else {
                Buttons.LEFT_RELEASE - Buttons.LEFT_BUTTON
            }
        } else if (Buttons.isMouseDown(key) && pressedMouseButton != 0) {
            /*
             * A game can give one button priority over another. No
             * ported game declares the flag, so the hook below is false
             * and the press is not ignored.
             */
            if (buttonBeats(pressedMouseButton, key)) return KeyOutcome.Unused

            /*
             * The presses overlapped. Invent a release for the button
             * that was held, so the game sees press, release, press.
             */
            outcome = minOutcome(
                outcome,
                reallyProcessKey(
                    x,
                    y,
                    pressedMouseButton + (Buttons.LEFT_RELEASE - Buttons.LEFT_BUTTON),
                    tileSize,
                ),
            )
        }

        /* CTRL+ASCII becomes the matching control character. */
        if ((key and Buttons.MOD_CTRL) != 0 &&
            Buttons.stripModifiers(key) in 0x40..0x7F
        ) {
            key = key and (0x1F or (Buttons.MOD_MASK and Buttons.MOD_CTRL.inv()))
        }
        /* CTRL+SHFT+Z is redo, as in every other desktop program. */
        if ((key and (Buttons.MOD_MASK.inv() or Buttons.MOD_SHFT)) ==
            (Buttons.MOD_SHFT or 0x1A)
        ) {
            key = Buttons.UI_REDO
        }
        /*
         * A game expects CTRL and SHFT only on a cursor move, and TAB.
         * CTRL on any other key with a printable code is a mistake, not
         * a game key, so reject it. Otherwise drop both modifiers.
         */
        if (!Buttons.isCursorMove(Buttons.stripModifiers(key))) {
            if ((key and Buttons.MOD_CTRL) != 0 &&
                Buttons.stripModifiers(key) >= 0x20
            ) {
                return KeyOutcome.Unused
            }
            if (Buttons.stripModifiers(key) != '\t'.code) {
                key = key and (Buttons.MOD_CTRL or Buttons.MOD_SHFT).inv()
            }
        }
        /* A game expects MOD_NUM_KEYPAD only on a digit. */
        val canonical = Buttons.stripModifiers(key)
        if (canonical < '0'.code || canonical > '9'.code) {
            key = key and Buttons.MOD_NUM_KEYPAD.inv()
        }
        /* Keyboard Enter, Return, and Space act as the cursor selections. */
        if (key == '\n'.code || key == '\r'.code) key = Buttons.CURSOR_SELECT
        if (key == ' '.code) key = Buttons.CURSOR_SELECT2

        /* Both backspace characters (8 and 127) become 8. */
        if (key == 0x7F) key = '\b'.code

        outcome = minOutcome(outcome, reallyProcessKey(x, y, key, tileSize))

        /* The final button decides what is held. */
        if (Buttons.isMouseRelease(key)) {
            pressedMouseButton = 0
        } else if (Buttons.isMouseDown(key)) {
            pressedMouseButton = key
        }

        refreshTimer()
        return outcome
    }

    /**
     * The key body of `midend_really_process_key`: the game call and the
     * one-key shortcuts, with the history and `changedState` effects.
     * [processKey] canonicalises the button before this, and a
     * fabricated button-up also goes through here.
     *
     * The order follows C. A UI fake key is a menu action and never
     * reaches the game. Every other button goes to `interpretMove`
     * first, and only a `MOVE_UNUSED` result applies the shortcuts. A
     * game that consumes a key keeps it, so `'u'` is not an undo when
     * the game uses it.
     */
    private fun reallyProcessKey(x: Int, y: Int, button: Int, tileSize: Int): KeyOutcome {
        if (!Buttons.isUiFakeKey(button)) {
            when (val result = game.interpretMove(state, ui, x, y, button, tileSize)) {
                is MoveResult.Move -> {
                    val previous = state
                    val next = game.executeMove(state, result.text)
                        ?: return KeyOutcome.NoEffect
                    purgeRedo()
                    entries += Entry(next, result.text, MoveType.MOVE)
                    statePos++
                    if (game.changedState(ui, previous, next)) completedEvent = true
                    supersedeDesc(next)
                    return KeyOutcome.SomeEffect
                }

                MoveResult.UiUpdate -> return KeyOutcome.SomeEffect
                MoveResult.NoEffect -> return KeyOutcome.NoEffect
                MoveResult.Unused -> Unit
            }
        }
        return applyShortcut(button)
    }

    /**
     * The shortcut chain of `midend_really_process_key`, in C order:
     * new game, undo, redo, solve, quit. The bare letters `n`, `u`,
     * `r`, and `q` are gated by [oneKeyShortcuts]; `*`, `#`, control
     * characters, and `UI_*` keys are always active, as in
     * `midend.c:1006-1034`.
     *
     * The shell owns the new game and quit actions, so their keys have
     * no engine action here. They return [KeyOutcome.Unused] instead of
     * starting or quitting a game.
     */
    private fun applyShortcut(button: Int): KeyOutcome = when (button) {
        'n'.code, 'N'.code, 0x0E, Buttons.UI_NEWGAME -> KeyOutcome.Unused
        'u'.code, 'U'.code ->
            if (oneKeyShortcuts) undoOutcome() else KeyOutcome.Unused

        '*'.code, 0x1A, 0x1F, Buttons.UI_UNDO -> undoOutcome()
        'r'.code, 'R'.code ->
            if (oneKeyShortcuts) redoOutcome() else KeyOutcome.Unused

        '#'.code, 0x12, 0x19, Buttons.UI_REDO -> redoOutcome()

        // C sets SOME_EFFECT before it tries the solver, so a solvable
        // game reports an effect even when the solver returns null.
        0x13, Buttons.UI_SOLVE ->
            if (!game.canSolve) KeyOutcome.Unused else {
                solve()
                KeyOutcome.SomeEffect
            }

        'q'.code, 'Q'.code, 0x11, Buttons.UI_QUIT -> KeyOutcome.Unused
        else -> KeyOutcome.Unused
    }

    private fun undoOutcome(): KeyOutcome =
        if (undo()) KeyOutcome.SomeEffect else KeyOutcome.NoEffect

    private fun redoOutcome(): KeyOutcome =
        if (redo()) KeyOutcome.SomeEffect else KeyOutcome.NoEffect

    /**
     * `midend_supersede_game_desc` for a move: a game can resolve a
     * pending description, and the midend then replaces `DESC` and
     * `PRIVDESC`. The C game calls the midend from inside
     * `execute_move`; the seam keeps the game pure and asks it for the
     * new text after the move is appended. A null public text, or the
     * text the midend already holds, changes nothing. The save writer
     * rejects non-printable text, as the C assertion does.
     */
    private fun supersedeDesc(newState: Game.State) {
        val public = game.publicDesc(newState) ?: return
        if (public == desc) return
        desc = public
        privDesc = game.privateDesc(newState)
    }

    /**
     * The `BUTTON_BEATS` priority of `game.flags`: `flags &
     * BUTTON_BEATS(pressed, button)`. A game may declare that one held
     * button beats another, so an overlapping press of the weaker
     * button is ignored instead of released. The midend reads the flags
     * word itself rather than [Game.buttonBeats], so a test game that
     * implements `Game` by delegation cannot hide the flags of its own
     * declaration. `mines` is the first game that sets the bit.
     */
    private fun buttonBeats(pressed: Int, button: Int): Boolean =
        (game.flags and Game.buttonBeatsBit(pressed, button)) != 0

    /** `min(ret, ret2)`: the outcome that shows the most effect. */
    private fun minOutcome(a: KeyOutcome, b: KeyOutcome): KeyOutcome =
        if (a.ordinal <= b.ordinal) a else b

    /**
     * Draws a complete board into [sink], including the operations the
     * midend adds around a game redraw, as `midend_redraw` does.
     *
     * The first redraw of a drawstate clears the window with colour 0
     * before the game draws and updates the whole area afterwards. A
     * redraw of a drawstate that was already drawn, after a move for
     * example, emits neither: the front end keeps the window contents
     * and the game reports its own changes with [DrawSink.update].
     */
    fun recordRedraw(sink: DrawSink) {
        val drawState = ensureDrawState(tileSize, sink)
        val first = takeFirstDraw()
        sink.startDraw()
        if (first) sink.rect(0, 0, size.width, size.height, 0)
        game.redraw(state, ui, drawState, sink, tileSize)
        if (game.wantsStatusBar) sink.status(statusText())
        if (first) sink.update(0, 0, size.width, size.height)
        sink.endDraw()
    }

    /** The first redraw of a new game: `changed_state` comes before the draw. */
    fun recordNewGameTrace(sink: DrawSink) {
        // `midend_new_game` calls `changed_state` before `midend_size`
        // allocates the first blitter, so a caching game emits
        // changed_state, then blitter_new, then start_draw. Recreate an
        // existing cache, as a new game also does.
        forceRedraw()
        sink.changedState(false, false)
        recordRedraw(sink)
    }

    /**
     * Ensures the drawstate matches [tileSize] and [sink] before a draw
     * starts.
     *
     * The first call creates the cache, so a game's `blitter_new` is
     * recorded before `start_draw`, as a fresh C drawstate is. A tile-size
     * change, a [forceRedraw], or a different sink frees the old one
     * through the sink that created it, then creates a new one. The shell
     * calls this before its own `startDraw`; [recordRedraw] calls it too.
     *
     * The new drawstate is built from the first state, as `midend_size`
     * and `midend_force_redraw` build it from `states[0].state` in C.
     * A cache that depends on the state refreshes it in `redraw`.
     */
    fun ensureDrawState(tileSize: Int, sink: DrawSink): Game.DrawState {
        val existing = drawState
        if (existing != null &&
            !drawStateDirty &&
            drawStateTileSize == tileSize &&
            drawStateSink === sink
        ) {
            return existing
        }
        if (existing != null) {
            // The cache and its sink are set together, so a cache always
            // has the sink that allocated its blitters. A free therefore
            // never reaches a trace that does not own the id.
            game.freeDrawState(existing, checkNotNull(drawStateSink))
        }
        val created = game.newDrawState(entries[0].state, tileSize, sink)
        drawState = created
        drawStateSink = sink
        drawStateTileSize = tileSize
        drawStateDirty = false
        // A fresh drawstate has never been drawn, so its first redraw
        // clears the window, as `midend_size`, `midend_new_game`, and
        // `midend_load` set `first_draw` after they create one.
        firstDraw = true
        return created
    }

    /**
     * Reports whether [drawState] still needs its first-draw clear, and
     * marks it as drawn. This is the read-and-clear of `me->first_draw`
     * in `midend_redraw`, for a shell that draws the game itself.
     */
    fun takeFirstDraw(): Boolean {
        val first = firstDraw
        firstDraw = false
        return first
    }

    /**
     * Marks the cache for replacement, as `midend_force_redraw` replaces
     * a drawstate. The next [ensureDrawState] frees the current cache
     * through its own sink and creates a new one from the first state.
     *
     * The shell calls this on a resize, including a zoom change at an
     * unchanged tile size. A C drawstate is built once for one bitmap
     * size, so it cannot be resized in place.
     *
     * An ordinary move, undo, redo, solve, or restart does not call
     * this, so the cache survives them as it does in C.
     */
    fun forceRedraw() {
        drawStateDirty = true
        // `midend_force_redraw` sets `first_draw` before it reaches
        // `midend_redraw`, so the new drawstate clears the window.
        firstDraw = true
    }

    /**
     * Frees the cache through the sink that created it, as
     * `midend_free_game` frees `me->drawstate`. A midend that never drew
     * has no cache, so this does nothing. The shell calls this when the
     * game view goes away. A second call frees nothing.
     */
    fun freeDrawState() {
        val existing = drawState ?: return
        game.freeDrawState(existing, checkNotNull(drawStateSink))
        drawState = null
        drawStateSink = null
        drawStateTileSize = 0
        drawStateDirty = true
    }

    /** Serialises the state exactly as upstream `midend_serialise`. */
    fun serialise(): SgtpSave {
        val records = ArrayList<SaveRecord>()
        records += SaveRecord("SAVEFILE", SgtpSave.MAGIC)
        records += SaveRecord("VERSION", SgtpSave.VERSION)
        records += SaveRecord("GAME", game.name)
        records += SaveRecord("PARAMS", game.encodeParams(params, true))
        records += SaveRecord("CPARAMS", game.encodeParams(currentParams, true))
        seed?.let { records += seedRecord(it) }
        records += SaveRecord("DESC", desc)
        // `midend_supersede_game_desc` writes the private description
        // directly after the public one and before `AUXINFO`.
        privDesc?.let { records += SaveRecord("PRIVDESC", it) }
        auxInfo?.let { records += SaveRecord("AUXINFO", AuxInfo.encode(it)) }
        // `encode_ui` writes its record only when it has one, between
        // `AUXINFO` and `NSTATES`, as `midend_serialise` does.
        game.encodeUi(ui)?.let { records += SaveRecord("UI", it) }
        // The game time, if it is a timed game, goes between `UI` and
        // `NSTATES`, as `midend_serialise` writes it. C writes `%g`.
        if (game.isTimed) records += SaveRecord("TIME", cFloatG(elapsed))
        records += SaveRecord("NSTATES", entries.size.toString())
        records += SaveRecord("STATEPOS", statePos.toString())
        for (index in 1 until entries.size) {
            val entry = entries[index]
            val move = entry.move ?: error("history entry $index has no move")
            val key = when (entry.type) {
                MoveType.MOVE -> "MOVE"
                MoveType.SOLVE -> "SOLVE"
                MoveType.RESTART -> "RESTART"
                MoveType.NEWGAME -> error("only the first entry may be NEWGAME")
            }
            records += SaveRecord(key, move)
        }
        return SgtpSave.of(records)
    }

    private fun purgeRedo() {
        while (entries.size > statePos) entries.removeAt(entries.size - 1)
    }

    /**
     * C scans the seed for a character outside printable ASCII and
     * writes `HEXSEED` only when it finds one (`midend.c:2274-2294`).
     * An empty seed has no such character, so it is a zero-length
     * `SEED` record, not `HEXSEED`.
     */
    private fun seedRecord(value: String): SaveRecord {
        val printable = value.all { it.code in 32..126 }
        return if (printable) {
            SaveRecord("SEED", value)
        } else {
            SaveRecord("HEXSEED", value.map { (it.code and 0xFF).toString(16).padStart(2, '0') }.joinToString(""))
        }
    }

    companion object {
        /**
         * The oracle's frontend seed: one NUL byte
         * (`tools/oracle/oracle.c:101-107`). A seed source that has no
         * clock, such as a test or a parity run, uses it so the
         * frontend random state is deterministic and the Jumble move
         * of `net` matches the oracle.
         */
        private val DEFAULT_PLATFORM_SEED = byteArrayOf(0)

        /**
         * `midend_get_prefs` always prepends this item
         * (`midend.c:3007-3028`). It is midend state, not a game
         * preference.
         */
        private val ONE_KEY_SHORTCUTS = ConfigItem.Bool(
            "Keyboard shortcuts without Ctrl",
            true,
            "one-key-shortcuts",
        )

        /**
         * The `one-key-shortcuts` item that every preferences page shows
         * first. It is midend state, not a [Game.prefs] entry, but a page
         * that edits the stored text without a midend needs the same
         * item and the same name.
         */
        val ONE_KEY_SHORTCUTS_ITEM: ConfigItem get() = ONE_KEY_SHORTCUTS

        /**
         * `midend_game_id_int(..., validate_only = true)`: null when
         * [text] is a valid game ID or random-seed text, else the C
         * error message. [isSeed] selects `DEF_SEED`; false selects
         * `DEF_DESC`.
         *
         * [baseParams] is the midend's long-term parameter set, which C
         * uses as the starting point for a descriptive id
         * (`midend.c:1878-1881`) and for a bare description. The app
         * passes its current parameters; a caller without a midend can
         * keep the default. A parameter string in [text] is decoded over
         * [baseParams] for a descriptive id, so a field that the text
         * omits keeps the base value, and from the defaults for a
         * parameter-only id (`midend.c:1890-1907`).
         */
        fun validateGameId(
            game: Game,
            text: String,
            isSeed: Boolean,
            baseParams: Game.Params = game.defaultParams(),
        ): String? {
            val hash = text.indexOf('#')
            val colon = text.indexOf(':')
            var paramsText: String? = null
            var desc: String? = null
            var seed: String? = null
            when {
                colon >= 0 && (hash < 0 || colon < hash) -> {
                    paramsText = text.substring(0, colon)
                    desc = text.substring(colon + 1)
                }

                hash >= 0 && (colon < 0 || hash < colon) -> {
                    paramsText = text.substring(0, hash)
                    seed = text.substring(hash + 1)
                }

                isSeed -> seed = text
                else -> desc = text
            }

            var newParams = baseParams
            if (paramsText != null) {
                /*
                 * C starts from `me->params` for a descriptive id, and
                 * from `default_params()` for a parameter-only id
                 * (`midend.c:1890-1907`). The decode must therefore see
                 * the base fields that the text omits.
                 */
                val current = if (desc != null) {
                    game.decodeParams(paramsText, baseParams)
                } else {
                    game.decodeParams(paramsText)
                }
                // C validates a seed or a parameter-only id with the full
                // rule, and a descriptive id with the short rule, because
                // the description can determine a parameter that the
                // short form leaves out (`midend.c:1886`).
                game.validateParams(current, desc == null)?.let { return it }
                newParams = if (seed != null || desc != null) {
                    game.decodeParams(game.encodeParams(current, false), baseParams)
                } else {
                    current
                }
            }
            if (desc != null) {
                game.validateDesc(newParams, desc)?.let { return it }
            }
            return null
        }

        /** Creates a new game and generates its description from [seed]. */
        fun newGame(
            game: Game,
            paramsText: String,
            seed: String,
            platformSeed: ByteArray = DEFAULT_PLATFORM_SEED,
            prefs: String? = null,
            cancel: Cancellation = Cancellation.NEVER,
        ): Midend {
            val effective = if (paramsText.isEmpty()) {
                game.encodeParams(game.defaultParams(), false)
            } else {
                paramsText
            }
            val currentParams = game.decodeParams(effective)
            game.validateParams(currentParams, true)?.let { throw IllegalArgumentException(it) }
            val params = game.decodeParams(game.encodeParams(currentParams, false))
            val random = RandomState.fromSeed(seed)
            val generated = game.newDesc(currentParams, random, cancel)
            // C validates the generated description with the filtered
            // `newparams`, not with `curparams` (`midend.c:1931`). No
            // ported game reads a parameter in `validateDesc` that its
            // short encoding drops, so the two sets give the same result
            // for every port; the filtered set is the C input.
            game.validateDesc(params, generated.text)?.let {
                throw IllegalArgumentException(it)
            }
            // `midend_new_game` builds the state from the long-term
            // parameters, not the full ones, on purpose: a game that
            // fails to encode a play-time parameter in its short form
            // then shows the behavior at play time. `midend.c:611-628`.
            val state = game.newState(params, generated.text)
            val midend = Midend(
                game = game,
                params = params,
                currentParams = currentParams,
                seed = seed,
                initialDesc = generated.text,
                auxInfo = generated.auxInfo,
                entries = mutableListOf(Entry(state, null, MoveType.NEWGAME)),
                statePos = 1,
                platformSeed = platformSeed,
            )
            midend.applyInitialPrefs(prefs)
            return midend
        }

        /** Builds a game from `params:desc`, as a game ID does. */
        fun fromGameId(
            game: Game,
            gameId: String,
            platformSeed: ByteArray = DEFAULT_PLATFORM_SEED,
            prefs: String? = null,
        ): Midend {
            val colon = gameId.indexOf(':')
            require(colon >= 0) { "game ID has no colon: $gameId" }
            val paramsText = gameId.substring(0, colon)
            val desc = gameId.substring(colon + 1)
            val currentParams = game.decodeParams(paramsText)
            // `midend_game_id_int` validates with `desc == NULL`, and a
            // game ID always has a description. A short id may leave a
            // parameter out on purpose: the description then determines
            // it, so only the full validation belongs to a new game.
            game.validateParams(currentParams, false)?.let { error(it) }
            val params = game.decodeParams(game.encodeParams(currentParams, false))
            // C validates the description with the filtered `newparams`
            // (`midend.c:1931`), the same set the state is built from.
            game.validateDesc(params, desc)?.let { error(it) }
            val midend = Midend(
                game = game,
                params = params,
                currentParams = currentParams,
                seed = null,
                initialDesc = desc,
                auxInfo = null,
                entries = mutableListOf(
                    Entry(game.newState(params, desc), null, MoveType.NEWGAME),
                ),
                statePos = 1,
                platformSeed = platformSeed,
            )
            midend.applyInitialPrefs(prefs)
            return midend
        }

        /** Reads a save file. The game must already be known. */
        fun fromSave(
            game: Game,
            save: SgtpSave,
            platformSeed: ByteArray = DEFAULT_PLATFORM_SEED,
            prefs: String? = null,
        ): Midend {
            require(save.value("GAME") == game.name) {
                "save file is from a different game: ${save.value("GAME")}"
            }
            val paramsText = requireNotNull(save.value("PARAMS")) {
                "long-term parameters in save file are missing"
            }
            val currentText = requireNotNull(save.value("CPARAMS")) {
                "short-term parameters in save file are missing"
            }
            val params = game.decodeParams(paramsText)
            game.validateParams(params, true)?.let { error("long-term parameters: $it") }
            val currentParams = game.decodeParams(currentText)
            game.validateParams(currentParams, false)?.let { error("short-term parameters: $it") }

            val seed = when {
                save.value("HEXSEED") != null -> decodeHexSeed(save.value("HEXSEED")!!)
                else -> save.value("SEED")
            }
            val desc = requireNotNull(save.value("DESC")) {
                "game description in save file is missing"
            }
            game.validateDesc(currentParams, desc)?.let { error("game description: $it") }
            val privDesc = save.value("PRIVDESC")
            if (privDesc != null) {
                game.validateDesc(currentParams, privDesc)?.let {
                    error("game private description: $it")
                }
            }

            val stateCount = requireNotNull(save.value("NSTATES")).toInt()
            require(stateCount > 0) { "state count is not positive" }
            val statePos = requireNotNull(save.value("STATEPOS")).toInt()
            require(statePos in 1..stateCount) { "state position is out of range" }

            // C builds state 0 from `PRIVDESC` when the record is
            // present, then applies the public description to it with
            // `set_public_desc`. Without the record it builds the state
            // from `DESC`, as a save from before S6 does.
            val first = if (privDesc != null) {
                game.setPublicDesc(
                    game.newState(currentParams, privDesc),
                    desc,
                )
            } else {
                game.newState(currentParams, desc)
            }

            val entries = ArrayList<Entry>(stateCount)
            entries += Entry(first, null, MoveType.NEWGAME)

            val history = save.records.filter {
                it.key == "MOVE" || it.key == "SOLVE" || it.key == "RESTART"
            }
            require(history.size == stateCount - 1) {
                "save file has ${history.size} history records for $stateCount states"
            }
            for (record in history) {
                val previous = entries.last().state
                val type = when (record.key) {
                    "MOVE" -> MoveType.MOVE
                    "SOLVE" -> MoveType.SOLVE
                    else -> MoveType.RESTART
                }
                val next = if (type == MoveType.RESTART) {
                    if (game.validateDesc(currentParams, record.value) != null) {
                        error("Save file contained an invalid restart move")
                    }
                    game.newState(currentParams, record.value)
                } else {
                    game.executeMove(previous, record.value)
                        ?: error("save file contained an invalid move: ${record.value}")
                }
                entries += Entry(next, record.value, type)
            }

            // C creates the fresh UI from the first state before it
            // reads the record (`midend.c:2669`), then `decode_ui`
            // overlays the record on that same UI with the current
            // state. A save without the record keeps the base UI.
            val baseUi = game.newUi(entries[0].state, platformSeed)
            val midend = Midend(
                game = game,
                params = params,
                currentParams = currentParams,
                seed = seed,
                initialDesc = desc,
                initialPrivDesc = privDesc,
                auxInfo = save.value("AUXINFO")?.let { AuxInfo.decode(it) },
                entries = entries,
                statePos = statePos,
                // C reads `TIME` with `atof`, which takes the numeric
                // prefix; a save without the record keeps the elapsed
                // time at 0.
                initialElapsed = save.value("TIME")?.let { cAtof(it) } ?: 0f,
                initialUi = save.value("UI")?.let {
                    game.decodeUi(entries[statePos - 1].state, it, baseUi)
                } ?: baseUi,
                platformSeed = platformSeed,
            )
            midend.applyInitialPrefs(prefs)
            return midend
        }

        private fun decodeHexSeed(text: String): String {
            require(text.length % 2 == 0) { "hex seed has an odd length" }
            val out = StringBuilder(text.length / 2)
            var i = 0
            while (i < text.length) {
                val value = text.substring(i, i + 2).toIntOrNull(16)
                    ?: error("hex seed has a non-hex digit: $text")
                out.append((value and 0xFF).toChar())
                i += 2
            }
            return out.toString()
        }
    }
}

enum class KeyOutcome { SomeEffect, NoEffect, Unused }
