# Mc2048

2048 inside Minecraft, built only from text display entities. No resource pack, no client mod.

Type `/2048` and the screen goes black, the board appears in front of you and you play with the keys you already use to walk. Made for lobbies, so players have something to do while they wait.

## Features

- Looks like the original game: same colours, layout, score and best boxes
- Tiles slide, new tiles pop in, merged tiles pulse, points float up from the score, tiles from 128 up glow, confetti when you reach 2048
- Mouse pointer that stays on the board, buttons light up when you hover them
- Undo: one step back, then two moves before it can be used again
- Win and game over screens with keep going, try again and undo
- Game and best score are saved per player, `/2048` continues where you left off
- Board size per player with the + and - buttons, so it fits any FOV
- No crosshair, hotbar or hearts while playing, only the board on black
- Short note block sounds for moves, merges, buttons, win and game over, all set in `config.yml`
- English and German built in, the language follows the player's client. Drop a `messages_<language>.yml` into the plugin folder to add more
- Every colour, size, timing and text is in `config.yml` and `messages.yml`

## Requirements

- Paper or Folia 26.2
- Java 25

The plugin talks to the server internals for the camera and the hidden hotbar, so a jar only runs on the Minecraft version it was built for.

## Controls

| Key | Action |
|---|---|
| W A S D | Slide the tiles |
| Mouse | Move the pointer |
| Left click | Press a button |
| Sneak | Leave (the Leave button does the same) |

The + and - buttons change the board size. The chat message at the start shows your own key bindings.

## Command and permission

| Command | Permission | Default |
|---|---|---|
| `/2048` | `mc2048.play` | everyone |

Running `/2048` again while playing leaves the game.

## Placeholders

With [PlaceholderAPI](https://modrinth.com/plugin/placeholderapi) installed, for TAB, scoreboards and so on:

| Placeholder | Value |
|---|---|
| `%2048_best%` | Best score |
| `%2048_score%` | Score of the current or last game |
| `%2048_highest%` | Highest tile on the board |

PlaceholderAPI is optional, the plugin runs fine without it.

## How it works

- Every rectangle is a text display with a single space as text and a coloured background, scaled to size. Numbers and labels are text displays without a background. All of them are only shown to the player who plays.
- The player sits on an invisible seat, so they cannot walk away and the client keeps sending mouse movement for the pointer. The camera is moved onto an invisible marker armor stand in front of them. Its camera distance is 0, so third person looks exactly like first person. A copy of the board behind the player covers the front view.
- Behind each board hangs a huge black text display. Blocks between the camera and the board are replaced with air for that player only. Nothing in the world changes and everything is sent back when the game ends.
- Only the player's client is told it is in spectator mode, which hides crosshair, hotbar and hearts. On the server the game mode never changes.
- The hand is hidden by marking the player invisible and sending an empty hotbar, only to that player. The real inventory is never touched.
- Taking damage, teleporting, changing worlds or quitting ends the game and saves it.

Saves are in `plugins/2048/players/<uuid>.yml`.

## Limits

- F5 cannot be blocked from the server. Both third person views show the same board.
- Text display backgrounds are rectangles, so tiles have square corners.
- Display backgrounds are drawn in one batch in no fixed order, so nothing on the board is see-through. Glow and overlays use colours mixed with the board colour instead.

## Building

```
./gradlew build
```

Needs JDK 25. The jar ends up in `build/libs`. The first build downloads and sets up the Paper dev bundle, which takes a few minutes.

## Credits

Game design, colours and layout are from [2048](https://github.com/gabrielecirulli/2048) by Gabriele Cirulli (MIT).

## License

MIT, see [LICENSE](LICENSE).
