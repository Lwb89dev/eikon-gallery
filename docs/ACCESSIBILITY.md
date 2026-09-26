# Accessibility

This is a review of the code against what a screen reader (TalkBack), large text and reduced motion need. **It was not tried with TalkBack, a switch device or large text on a phone**: what follows is what the code
now does and what was fixed after reading it, not a test result. Whoever tries it on a device should expect to find more.

## What the app does

- **Names.** Grid cells are read as "Photo, date" (or "Video, date, length"), with "Favorite" and "Edited" where they apply, and announce whether they are selected. Every icon button has a label.
- **Labels for sliders.** Each editing slider is read with its name and its value ("Exposure, plus 0.50"), and offers "Reset to neutral" as an action.
- **Things you do with two fingers or a drag have another way.**
  - Zoom in the viewer (pinch, double tap): an action "Zoom in / Zoom out" on the photo.
  - Drag the crop: the crop area is read ("from 10% to 90% across…") with actions to make it smaller or larger and to move it in four directions, using the same rules as a drag. The shape presets, rotate, flip and the straighten and perspective sliders
    are ordinary controls.
  - Hold the photo in the editor to see the original: a chip "Original" does the same with a tap.
  - Drag down to close the viewer: the back button and the system back do the same.
  - Swipe between photos: the pager's own scroll actions.
- **Roles and state.** The editor's tools are tabs with a selected state, filters are radio buttons, the "Edited"/"Original" chip in the viewer is a button that says what it will do ("Show the original").
- **Text size.** The editor's tool row scrolls sideways when it does not fit; the tool options scroll vertically; text is in `sp`. The viewer's top bar has a fixed height (56 dp) and one line of text.
- **Motion.** Animations follow the system "Remove animations" setting: the photo flight, zoom and the memory slideshow finish at once.
- **Colour.** White text and icons sit on a dark scrim over photos. Light and dark themes come from Material 3.
- **Progress and failure are said in words**: "Preparing the edited photos to share" (with a moving bar), "This photo can't be displayed", "The edit could not be completed. The photo was not changed."

- **The backup's settings** (in the `backup` build) use ordinary labelled fields, radio buttons and switches; the credential field is a password field and says when one is saved. The certificate to be trusted is shown as text (its fingerprint, in fixed-width type), with the two buttons after it. Progress is a bar and a sentence.

## Known gaps

- The **date scrubber** at the edge of the grid is a touch gesture (it has a description, but no actions). A screen reader user scrolls the grid the normal way.
- The **map** in Places is drawn on a canvas with one description for the whole map: its markers are not reachable one by one. The places *list* has the same content.
- **Memories** play as a slideshow whose photos have no description of their own.
- The **zoomed photo** cannot be panned with a screen reader (only zoomed in and out).
- Touch targets: the small badges on cells are not buttons. The name above each slider (tap to reset) is smaller than 48 dp, but the same reset is on the slider itself as an action.
- No support for a hardware keyboard beyond what Compose gives (Tab and Enter on buttons); the editor has no shortcuts.
- Languages: English and Italian only.
