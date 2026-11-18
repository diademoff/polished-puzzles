package name.boyle.chris.sgtpuzzles.chooser

import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import name.boyle.chris.sgtpuzzles.R
import name.boyle.chris.sgtpuzzles.backend.BackendName

/** Test tag of the scrollable grid. */
const val CHOOSER_GRID_TAG = "chooser-grid"

/** Test tag of one game in the grid. */
fun chooserGameTag(game: BackendName): String = "game-${game.sourceName}"

/**
 * One row of the chooser: a section label or a game.
 *
 * The list is flat because a lazy grid needs an indexable model. Section
 * labels span the full width.
 */
sealed interface ChooserEntry {
    data class Section(@StringRes val title: Int) : ChooserEntry
    data class Game(val game: BackendName) : ChooserEntry
}

/**
 * Builds the chooser rows. Starred games come first, in the order they
 * have in [games]; the rest keep that order too, so the chooser does not
 * reshuffle when the user stars a game.
 */
internal fun chooserSections(
    games: List<BackendName>,
    starred: Set<BackendName>,
): List<ChooserEntry> {
    val (starredGames, others) = games.partition { it in starred }
    return buildList {
        if (starredGames.isNotEmpty()) {
            add(ChooserEntry.Section(R.string.games_starred))
            starredGames.forEach { add(ChooserEntry.Game(it)) }
        }
        add(
            ChooserEntry.Section(
                if (starredGames.isNotEmpty()) R.string.games_others else R.string.games_others_none_starred,
            ),
        )
        others.forEach { add(ChooserEntry.Game(it)) }
    }
}

/**
 * The game chooser screen.
 *
 * [compact] shows icons only; otherwise each game shows its name and one
 * line of description. Long press toggles the starred state; the long
 * press is exposed to TalkBack with a label.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChooserScreen(
    games: List<BackendName>,
    starred: Set<BackendName>,
    current: BackendName?,
    compact: Boolean,
    isNight: Boolean,
    scrollToken: Int,
    onGameClick: (BackendName) -> Unit,
    onStarToggle: (BackendName) -> Unit,
    onLoad: () -> Unit,
    onSettings: () -> Unit,
    onHelp: () -> Unit,
    onFeedback: () -> Unit,
) {
    val entries = remember(games, starred) { chooserSections(games, starred) }
    val gridState = rememberLazyGridState()
    var helpMenuOpen by remember { mutableStateOf(false) }

    ScrollToCurrentGame(scrollToken, current, entries, gridState)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onLoad, modifier = Modifier.size(48.dp)) {
                        Icon(
                            painterResource(R.drawable.ic_action_load),
                            contentDescription = stringResource(R.string.Load_),
                        )
                    }
                    IconButton(onClick = onSettings, modifier = Modifier.size(48.dp)) {
                        Icon(
                            painterResource(R.drawable.ic_action_settings),
                            contentDescription = stringResource(R.string.Settings),
                        )
                    }
                    Box {
                        IconButton(
                            onClick = { helpMenuOpen = true },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_action_help),
                                contentDescription = stringResource(R.string.Help),
                            )
                        }
                        DropdownMenu(
                            expanded = helpMenuOpen,
                            onDismissRequest = { helpMenuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.Help)) },
                                leadingIcon = {
                                    Icon(
                                        painterResource(R.drawable.ic_action_help),
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    helpMenuOpen = false
                                    onHelp()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.Send_feedback)) },
                                leadingIcon = {
                                    Icon(
                                        painterResource(R.drawable.ic_feedback),
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    helpMenuOpen = false
                                    onFeedback()
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = if (compact) 72.dp else 298.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag(CHOOSER_GRID_TAG),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = 16.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(
                items = entries,
                span = { entry ->
                    if (entry is ChooserEntry.Section) GridItemSpan(maxLineSpan) else GridItemSpan(1)
                },
                key = { entry ->
                    when (entry) {
                        is ChooserEntry.Section -> "section-${entry.title}"
                        is ChooserEntry.Game -> chooserGameTag(entry.game)
                    }
                },
            ) { entry ->
                when (entry) {
                    is ChooserEntry.Section -> SectionHeader(entry.title)
                    is ChooserEntry.Game -> GameItem(
                        game = entry.game,
                        isCurrent = entry.game === current,
                        isStarred = entry.game in starred,
                        compact = compact,
                        isNight = isNight,
                        onStarToggle = { onStarToggle(entry.game) },
                        onClick = { onGameClick(entry.game) },
                    )
                }
            }
        }
    }
}

/**
 * Brings the current game into view when the screen resumes, and only if
 * it is not visible already. [scrollToken] changes on every resume, so
 * returning to the same game scrolls back to it.
 */
@Composable
private fun ScrollToCurrentGame(
    scrollToken: Int,
    current: BackendName?,
    entries: List<ChooserEntry>,
    gridState: LazyGridState,
) {
    val latestEntries by rememberUpdatedState(entries)
    LaunchedEffect(scrollToken) {
        if (current == null) return@LaunchedEffect
        val index = latestEntries.indexOfFirst {
            it is ChooserEntry.Game && it.game === current
        }
        if (index < 0) return@LaunchedEffect
        val visible = gridState.layoutInfo.visibleItemsInfo.any { it.index == index }
        if (!visible) gridState.animateScrollToItem(index)
    }
}

@Composable
private fun SectionHeader(@StringRes title: Int) {
    Text(
        text = stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { heading() }
            .testTag("section-$title")
            .padding(top = 16.dp, bottom = 4.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GameItem(
    game: BackendName,
    isCurrent: Boolean,
    isStarred: Boolean,
    compact: Boolean,
    isNight: Boolean,
    onStarToggle: () -> Unit,
    onClick: () -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    val background = if (isCurrent) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val starLabel = stringResource(if (isStarred) R.string.unstar_game else R.string.star_game)
    val currentLabel = stringResource(R.string.current_game)
    val starredLabel = stringResource(R.string.starred)
    val spoken = annotatedDescription(game).text

    val modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 72.dp)
        .clip(shape)
        .background(background)
        .then(
            if (isCurrent) {
                Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
            } else {
                Modifier
            },
        )
        .combinedClickable(
            role = Role.Button,
            onLongClickLabel = starLabel,
            onLongClick = onStarToggle,
            onClick = onClick,
        )
        .semantics(mergeDescendants = true) {
            // One talkable item: the name and the description are on the
            // clickable node itself, not on a child beside it.
            contentDescription = spoken
            val states = listOfNotNull(
                if (isStarred) starredLabel else null,
                if (isCurrent) currentLabel else null,
            )
            if (states.isNotEmpty()) stateDescription = states.joinToString(", ")
            // The badge is visual only, so the state needs an action
            // that a screen reader can find without a long press.
            customActions = listOf(
                CustomAccessibilityAction(starLabel) {
                    onStarToggle()
                    true
                },
            )
        }
        .testTag(chooserGameTag(game))

    if (compact) {
        Column(
            modifier = modifier.padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            GameIcon(game, isNight, contentDescription = game.displayName, starred = isStarred)
        }
    } else {
        Row(
            modifier = modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GameIcon(game, isNight, contentDescription = null, starred = isStarred)
            Spacer(Modifier.width(12.dp))
            Text(
                text = annotatedDescription(game),
                // The descriptions are English; in an RTL layout they still
                // read left to right and keep their punctuation at the end.
                style = MaterialTheme.typography.bodyMedium.copy(
                    textDirection = TextDirection.Content,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                // The row already speaks these words, so the drawn text
                // is not a second stop.
                modifier = Modifier.fillMaxWidth().clearAndSetSemantics { },
            )
        }
    }
}

@Composable
private fun annotatedDescription(game: BackendName) = buildAnnotatedString {
    withStyle(SpanStyle(fontWeight = FontWeight.Medium)) {
        append(game.displayName)
    }
    append(": ")
    append(stringResource(game.description))
}

@Composable
private fun GameIcon(
    game: BackendName,
    isNight: Boolean,
    contentDescription: String?,
    starred: Boolean,
) {
    Box {
        // Game icons have day and night variants. This activity handles
        // uiMode changes itself, so the painter must be rebuilt by hand.
        key(isNight) {
            Icon(
                painter = painterResource(game.icon),
                contentDescription = contentDescription,
                // Game icons are full-color art, not glyphs.
                tint = Color.Unspecified,
                modifier = Modifier.size(64.dp),
            )
        }
        if (starred) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(20.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_star),
                    contentDescription = null,
                    tint = colorResource(R.color.shell_primary),
                    modifier = Modifier.padding(3.dp),
                )
            }
        }
    }
}
