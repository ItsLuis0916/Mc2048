# Mc2048

2048 inside Minecraft, built only from text display entities. No resource pack, no client mod.

Type `/2048` and the screen goes black, the board appears in front of you and you play with the keys you already use to walk. Made for lobbies, so players have something to do while they wait.

## Features

- Looks like the original game: same colours, layout, score and best boxes
- Tiles slide, new tiles pop in, merged tiles pulse, points float up from the score
- Mouse pointer that stays on the board, buttons light up when you hover them
- Undo: one step back, then two moves before it can be used again
- Win and game over screens with keep going, try again and undo
- Game and best score are saved per player, `/2048` continues where you left off
- Board size per player with the scroll wheel, so it fits any FOV
- Every colour, size, timing and text is in `config.yml` and `messages.yml`

## Requirements

- Paper 26.2
- Java 25

The plugin talks to the server internals for the camera and the hidden hotbar, so a jar only runs on the Minecraft version it was built for.

## Controls

| Key | Action |
|---|---|
| W A S D | Slide the tiles |
| Mouse | Move the pointer |
| Left click | Press a button |
| Scroll wheel | Make the board bigger or smaller |
| Sneak | Leave (the Leave button does the same) |
| F5 | Hides the crosshair, the view stays the same |

The chat message at the start shows your own key bindings.

## Command and permission

| Command | Permission | Default |
|---|---|---|
| `/2048` | `mc2048.play` | everyone |

Running `/2048` again while playing leaves the game.

## How it works

- Every rectangle is a text display with a single space as text and a coloured background, scaled to size. Numbers and labels are text displays without a background. All of them are only shown to the player who plays.
- The player sits on an invisible seat, so they cannot walk away. The camera is moved onto an invisible marker armor stand in front of them. Its camera distance is 0, so third person looks exactly like first person. A copy of the board behind the player covers the front view.
- The black room is made of fake blocks that only the player sees. Nothing in the world changes and everything is sent back when the game ends.
- Hand and hotbar are hidden by marking the player invisible and sending an empty hotbar, only to that player. The real inventory is never touched.
- Taking damage, teleporting, changing worlds or quitting ends the game and saves it.

Saves are in `plugins/2048/players/<uuid>.yml`.

## Limits

- F5 cannot be blocked from the server. Both third person views show the same board, in the front view the mouse moves up and down the other way.
- The crosshair stays in the middle of the screen in first person. F5 hides it.
- Text display backgrounds are rectangles, so tiles have square corners.
- The code uses Folia's schedulers, but it has only been tested on Paper.

## Building

```
./gradlew build
```

Needs JDK 25. The jar ends up in `build/libs`. The first build downloads and sets up the Paper dev bundle, which takes a few minutes.

## Credits

Game design, colours and layout are from [2048](https://github.com/gabrielecirulli/2048) by Gabriele Cirulli (MIT).

## License

MIT, see [LICENSE](LICENSE).
